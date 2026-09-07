package org.bittrace.proxy

import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.ConnectRequestData
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.SessionStore

/**
 * Ties the sidecar to the app: traffic metadata goes into [store], raw bodies
 * into [bodies], and everything else is handed to [onLog] / [onExit] for the UI
 * to show.
 *
 * This is the piece the Tauri build did with `app.emit(...)` plus the frontend
 * listeners; here the store is updated directly and it does its own hop to the
 * JavaFX thread.
 */
class ProxyService(
    private val store: SessionStore,
    val bodies: BodyCache = BodyCache(),
    private val onLog: (LogEntry) -> Unit = {},
    private val onExit: (Int) -> Unit = {},
) {

    private var process: ProxyProcess? = null

    /** Rebuilds bodies that arrive as chunks; see [StreamedBodies]. */
    private val streamed = StreamedBodies(onLog = { onLog(it) })

    val isRunning: Boolean get() = process?.isRunning == true

    /** PID as reported by the sidecar itself, once its first frame arrives. */
    val pid: String? get() = process?.pid

    /** Seconds since the sidecar reported its PID. */
    val uptimeSeconds: Long? get() = process?.uptimeSeconds

    /**
     * Starts the sidecar on [port]. Throws if it is already running or missing.
     *
     * Sweeps orphaned sidecars first, because the port one of them is holding
     * is the port this is about to want — see [ProxyProcess.killOrphans] for
     * what counts as orphaned, which is narrower than "any MITMConnect".
     *
     * On every start, not only the first: a start can fail with the port taken
     * and be retried from the Proxy menu, and having to relaunch the app to get
     * the sweep would defeat it.
     */
    fun start(port: Int) {
        check(!isRunning) { "proxy already running" }
        ProxyProcess.killOrphans(onLog)
        ProxyProcess(port.toString(), listener).also {
            process = it
            it.start()
        }
    }

    fun stop(): Int? = process?.stop()

    /** Drops captured traffic and the bodies that go with it. */
    fun clear() {
        store.clear()
        bodies.clear()
        streamed.clear()
    }

    /** Raw body for a flow, or null once it has been evicted from the cache. */
    fun body(id: String, side: BodySide): ByteArray? = bodies.get(id, side)

    private val listener = object : ProxyListener {
        override fun onLog(entry: LogEntry) =
            this@ProxyService.onLog(entry)

        override fun onInitialRequest(data: InitialRequestData) =
            store.onInitialRequest(data)

        override fun onInitialResponse(data: InitialResponseData) =
            store.onInitialResponse(data)

        override fun onCompleteRequest(message: CompleteRequestMessage, body: ByteArray) {
            if (body.isNotEmpty()) bodies.put(message.id, BodySide.REQUEST, body)
            store.onCompleteRequest(message)
        }

        override fun onCompleteResponse(message: CompleteResponseMessage, body: ByteArray) {
            // A streamed body arrives empty here, having already been assembled
            // from its chunks and cached — so the emptiness check is not just an
            // optimisation, it is what keeps that body from being overwritten.
            if (body.isNotEmpty()) bodies.put(message.id, BodySide.RESPONSE, body)
            store.onCompleteResponse(message)
        }

        override fun onConnectRequest(data: ConnectRequestData) =
            store.onConnectRequest(data)

        override fun onConnectResponse(data: InitialResponseData) =
            store.onInitialResponse(data)

        override fun onBodyChunk(message: BodyChunkMessage, body: ByteArray) =
            streamed.chunk(message, body)

        override fun onBodyEnd(message: BodyEndMessage) {
            // Closed first, unconditionally: an unrecognised side would
            // otherwise leave the assembled bytes pending forever.
            val body = streamed.end(message)
            val side = BodySide.fromString(message.side)
            if (body != null && side != null) bodies.put(message.id, side, body)
        }

        override fun onStderr(line: String) =
            this@ProxyService.onLog(LogEntry("error", "sidecar", line))

        override fun onExit(exitCode: Int) =
            this@ProxyService.onExit(exitCode)
    }
}
