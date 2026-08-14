package org.bittrace.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the MITMConnect sidecar and turns its stdout into typed traffic events.
 *
 * Kotlin equivalent of `src-tauri/proxy/src/proxy.rs`. The sidecar is spawned
 * with the listen port as its only argument; stdout carries the binary frame
 * protocol (see [FrameReader]) and stderr carries plain log lines. Each stream
 * is drained by its own daemon thread, so neither can block the other or the
 * caller.
 *
 * The binary itself ships as a classpath resource and is unpacked on demand;
 * see [SidecarBinary].
 *
 * @param port the port passed to the sidecar
 * @param listener receives decoded frames; called on the reader threads
 */
class ProxyProcess(
    private val port: String,
    private val listener: ProxyListener,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val processRef = AtomicReference<Process?>()
    private val reportedPid = AtomicReference<String?>()
    private val startedAt = AtomicReference<Instant?>()

    /** The unpacked executable this instance will run, extracting it if needed. */
    val executable: File get() = SidecarBinary.resolve().toFile()

    val isRunning: Boolean get() = processRef.get()?.isAlive == true

    /** The PID as the sidecar reported it on stdout, once the PID frame arrives. */
    val pid: String? get() = reportedPid.get()

    /** Seconds since the sidecar reported its PID, or null before that. */
    val uptimeSeconds: Long?
        get() = startedAt.get()?.let { Duration.between(it, Instant.now()).seconds }

    /**
     * Spawns the sidecar and starts draining its streams.
     *
     * @throws IllegalStateException if this instance is already running
     * @throws java.io.FileNotFoundException if the binary is not packaged
     * @throws java.io.IOException if it cannot be started
     */
    fun start(): Process {
        check(!isRunning) { "proxy already running (pid ${processRef.get()?.pid()})" }

        val binary = executable

        // Piping both streams also keeps the console-subsystem sidecar from
        // getting a window of its own, which is what CREATE_NO_WINDOW does on
        // the Rust side.
        val process = ProcessBuilder(binary.absolutePath, port)
            .directory(binary.parentFile)
            .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        processRef.set(process)

        thread("proxy-stdout") { readFrames(process.inputStream) }
        thread("proxy-stderr") { readStderr(process.errorStream) }
        thread("proxy-waiter") {
            val code = process.waitFor()
            processRef.compareAndSet(process, null)
            listener.onExit(code)
        }

        return process
    }

    /**
     * Asks the sidecar to exit, escalating to a kill if it does not stop within
     * [graceMillis], then kills the proxy PID it reported. Returns the exit
     * code, or null if it was not running.
     */
    fun stop(graceMillis: Long = 3_000): Int? {
        val process = processRef.get()
        if (process == null) {
            killReportedProcess()
            return null
        }
        process.destroy()
        if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly().waitFor()
        }
        processRef.compareAndSet(process, null)
        killReportedProcess()
        return process.exitValue()
    }

    /**
     * Kills the PID the sidecar reported on stdout.
     *
     * That process is the proxy itself, which is not necessarily our direct
     * child — if it outlives the launcher it keeps holding the listen port and
     * the next start fails. Killed outright rather than asked politely, since
     * the launcher has already had its grace period by this point.
     *
     * PIDs are recycled, so a PID that started before the sidecar did belongs
     * to something else by now and is left alone.
     */
    private fun killReportedProcess(): Boolean {
        val pid = reportedPid.getAndSet(null)?.toLongOrNull() ?: return false
        if (pid == ProcessHandle.current().pid()) return false

        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false

        val launched = startedAt.get()
        val started = handle.info().startInstant().orElse(null)
        if (launched != null && started != null && started.isBefore(launched.minusSeconds(60))) {
            logError("refusing to kill pid $pid: started before the proxy did, PID was reused")
            return false
        }

        val killed = handle.destroyForcibly()
        listener.onLog(
            LogEntry(
                level = if (killed) "info" else "error",
                source = "proxy",
                message = if (killed) "sent SIGKILL to pid $pid" else "failed to kill pid $pid",
            )
        )
        return killed
    }

    // -----------------------------------------------------------------------
    // Stream handling
    // -----------------------------------------------------------------------

    private fun readFrames(stdout: InputStream) {
        stdout.buffered().use { stream ->
            FrameReader.read(stream) { frame -> dispatch(frame) }
        }
    }

    private fun dispatch(frame: Frame) {
//        println(String(frame.json))
        when (frame.tag) {
            Tags.PID -> {
                val pid = String(frame.json, StandardCharsets.UTF_8).trim()
                reportedPid.set(pid)
                startedAt.set(Instant.now())
                listener.onPid(pid)
            }

            Tags.LOG -> listener.onLog(parseLog(frame.json))

            Tags.INITIAL_REQUEST ->
                decode<InitialRequestData>("proxy-initial-request", frame.json)
                    ?.let(listener::onInitialRequest)

            Tags.INITIAL_RESPONSE ->
                decode<InitialResponseData>("proxy-initial-response", frame.json)
                    ?.let(listener::onInitialResponse)

            Tags.COMPLETE_REQUEST ->
                decode<CompleteRequestMessage>("proxy-complete-request", frame.json)
                    ?.let { listener.onCompleteRequest(it, frame.body) }

            Tags.COMPLETE_RESPONSE ->
                decode<CompleteResponseMessage>("proxy-complete-response", frame.json)
                    ?.let { listener.onCompleteResponse(it, frame.body) }

            else -> logError("unknown frame tag: ${frame.tag}")
        }
    }

    /**
     * Parses a sidecar log frame tolerantly. MITMConnect's Python logger emits
     * `{date, type, message, exception?}` while other sources use
     * `{level, source, message}` — accept either, and fall back to the raw text
     * so a log line always surfaces rather than being dropped as a parse error.
     */
    private fun parseLog(bytes: ByteArray): LogEntry {
        val text = String(bytes, StandardCharsets.UTF_8)
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            fun str(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull }
            val level = (str("level", "type") ?: "info").lowercase()
            val source = str("source") ?: "mitmconnect"
            val message = str("message", "msg") ?: text
            val exception = str("exception")
            LogEntry(level, source, if (exception.isNullOrBlank()) message else "$message — $exception")
        } catch (e: Exception) {
            LogEntry("info", "mitmconnect", text)
        }
    }

    /** Decodes a metadata segment, reporting failures as error logs like the Rust side. */
    private inline fun <reified T> decode(event: String, bytes: ByteArray): T? =
        try {
            json.decodeFromString<T>(String(bytes, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logError("parsing $event failed: $e")
            null
        }

    private fun readStderr(stderr: InputStream) {
        stderr.bufferedReader().useLines { lines ->
            lines.forEach { line -> listener.onStderr(line) }
        }
    }

    private fun logError(message: String) =
        listener.onLog(LogEntry(level = "error", source = "proxy", message = message))

    private fun thread(name: String, block: () -> Unit) {
        Thread({
            try {
                block()
            } catch (e: Exception) {
                logError("$name failed: $e")
            }
        }, name).apply { isDaemon = true }.start()
    }

    private companion object {
        fun nullFile(): File = File(if (SidecarBinary.isWindows) "NUL" else "/dev/null")
    }
}
