package org.bittrace.proxy

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sidecar's stdin: commands from this end, in the same framing its stdout
 * uses (see [FrameWriter], [Commands]).
 *
 * One writer thread, mirroring `FrameChannel` on the Python side. A frame is
 * several writes, and the callers — the rule store on the UI thread, the
 * breakpoint queue — must never interleave halves of two frames, nor block on
 * a pipe the sidecar has momentarily stopped draining. So every write is a task
 * on a single-thread executor and the caller returns at once.
 *
 * A failed write (the sidecar died, or was stopped) marks the channel closed
 * and later sends are dropped. That is safe for rules, because they are files:
 * the next sidecar reads the folder before it binds its port and needs nothing
 * resent. Paused breakpoints die with the process that held them.
 */
class ControlChannel(
    private val out: OutputStream,
    private val onLog: (LogEntry) -> Unit = {},
) {
    private val open = AtomicBoolean(true)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "proxy-stdin").apply { isDaemon = true }
    }

    val isOpen: Boolean get() = open.get()

    fun send(tag: Int, json: String, body: ByteArray = ByteArray(0)) {
        if (!open.get()) return
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        try {
            writer.execute {
                if (!open.get()) return@execute
                try {
                    FrameWriter.write(out, tag, bytes, body)
                } catch (e: IOException) {
                    if (open.getAndSet(false)) {
                        onLog(LogEntry("warn", "proxy", "control channel closed: ${e.message}"))
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closed between the check and the submit.
        }
    }

    /**
     * Ends the stream. The sidecar reads EOF as "nobody is left to answer a
     * breakpoint" and releases every paused flow, which is exactly what should
     * happen when this end goes away.
     */
    fun close() {
        if (writer.isShutdown) return
        // Behind whatever is already queued, so a command sent just before the
        // close still goes out — a resume answered as the app quits included.
        runCatching {
            writer.execute {
                open.set(false)
                runCatching { out.close() }
            }
        }
        writer.shutdown()
        writer.awaitTermination(1, TimeUnit.SECONDS)
        open.set(false)
    }
}
