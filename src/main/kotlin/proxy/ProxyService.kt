package org.bittrace.proxy

import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.TrafficStore

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
    private val store: TrafficStore,
    val bodies: BodyCache = BodyCache(),
    private val onLog: (LogEntry) -> Unit = {},
    private val onExit: (Int) -> Unit = {},
) {

    private var process: ProxyProcess? = null

    val isRunning: Boolean get() = process?.isRunning == true

    /** PID as reported by the sidecar itself, once its first frame arrives. */
    val pid: String? get() = process?.pid

    /** Seconds since the sidecar reported its PID. */
    val uptimeSeconds: Long? get() = process?.uptimeSeconds

    /** Starts the sidecar on [port]. Throws if it is already running or missing. */
    fun start(port: Int) {
        check(!isRunning) { "proxy already running" }
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
            if (body.isNotEmpty()) bodies.put(message.id, BodySide.RESPONSE, body)
            store.onCompleteResponse(message)
        }

        override fun onStderr(line: String) =
            this@ProxyService.onLog(LogEntry("error", "sidecar", line))

        override fun onExit(exitCode: Int) =
            this@ProxyService.onExit(exitCode)
    }
}
