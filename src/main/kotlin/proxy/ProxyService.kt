package org.bittrace.proxy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.EventQueue
import java.util.concurrent.ConcurrentHashMap
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.ConnectRequestData
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.SessionStore
import org.bittrace.data.WebSocketEndData
import org.bittrace.data.WebSocketMessageData
import org.bittrace.data.WebSocketRecord

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

    /**
     * When each in-flight body last reported its progress, by side and flow id.
     * Written from the sidecar's reader thread and cleared from the UI thread,
     * so it is concurrent rather than plain.
     */
    private val lastProgress = ConcurrentHashMap<Pair<String, String>, Long>()

    val isRunning: Boolean get() = process?.isRunning == true

    /** PID as reported by the sidecar itself, once its first frame arrives. */
    val pid: String? get() = process?.pid

    /** Seconds since the sidecar reported its PID. */
    val uptimeSeconds: Long? get() = process?.uptimeSeconds

    /**
     * The sidecar's last account of itself, as snapshot state so whatever shows
     * it repaints when a frame lands. Null before the first one, and again
     * after a stop.
     */
    var status: ProxyStatus? by mutableStateOf(null)
        private set

    /**
     * The keep-alive has stopped arriving: the sidecar is wedged or gone even
     * if the process object still looks alive. Not snapshot state — it is a
     * function of the clock, so a caller that wants it on screen has to look
     * again rather than wait to be told.
     */
    val isStale: Boolean get() = isRunning && process?.isStale == true

    /** Frames the sidecar dropped, already reported; only a rise is news. */
    private var reportedDrops = 0L
    private var reportedPortLost = false

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

    fun stop(): Int? {
        val code = process?.stop()
        onUi { status = null }
        reportedDrops = 0
        reportedPortLost = false
        return code
    }

    /** Drops captured traffic and the bodies that go with it. */
    fun clear() {
        store.clear()
        bodies.clear()
        streamed.clear()
        lastProgress.clear()
    }

    /**
     * Raw body for a flow: the finished one, or what has arrived so far while
     * it is still streaming. Null once it has been evicted from the cache, or
     * before any of it exists.
     *
     * The cache is asked first — a body that has ended is decoded and complete,
     * and the partial bytes behind it are gone by then anyway.
     */
    fun body(id: String, side: BodySide): ByteArray? =
        bodies.get(id, side) ?: streamed.partial(id, side)

    private companion object {
        /**
         * How much of one WebSocket message is retained.
         *
         * The sidecar already caps a message at 4 MiB, but it caps each one
         * independently — a connection is a stream of them, and the transcript
         * is held on the row rather than in the evicting [BodyCache]. This is
         * the per-message share of that; [SessionStore] bounds the total.
         */
        const val MESSAGE_LIMIT = 256 * 1024

        /**
         * The shortest gap between two progress reports for one body. Below
         * what reads as delay on a figure that is only ever a progress
         * indicator, and far above the rate the frames themselves arrive at.
         */
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    }

    /** Snapshot state is published from the UI thread, as [SessionStore] does. */
    private inline fun onUi(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
    }

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

        override fun onBodyChunk(message: BodyChunkMessage, body: ByteArray) {
            streamed.chunk(message, body)
            val side = BodySide.fromString(message.side) ?: return
            // Throttled: the sidecar emits a frame as soon as bytes arrive and
            // caps one at 16 KiB, so a fast transfer produces thousands a
            // second, and every publish hops to the UI thread. A tenth of a
            // second is far below what reads as delay on a progress figure and
            // far above the rate the frames arrive at.
            val now = System.nanoTime()
            val last = lastProgress[message.side to message.id]
            if (last != null && now - last < PROGRESS_INTERVAL_NANOS) return
            lastProgress[message.side to message.id] = now
            store.onStreamProgress(
                message.id,
                request = side == BodySide.REQUEST,
                received = streamed.received(message.id, side),
            )
        }

        override fun onBodyEnd(message: BodyEndMessage) {
            // Closed first, unconditionally: an unrecognised side would
            // otherwise leave the assembled bytes pending forever.
            val body = streamed.end(message)
            val side = BodySide.fromString(message.side)
            if (body != null && side != null) bodies.put(message.id, side, body)
            lastProgress.remove(message.side to message.id)
            // An aborted live stream never reaches its Complete* frame, so this
            // is the only thing that will ever clear the counter.
            side?.let { store.onStreamProgress(message.id, it == BodySide.REQUEST, 0) }
        }

        /**
         * Keeps one message, clipped to [MESSAGE_LIMIT].
         *
         * A message belongs to the handshake flow's row, so one that arrives
         * for a row the store never saw — evicted by capacity, or cleared
         * mid-connection — is simply dropped there.
         */
        override fun onWebSocketMessage(message: WebSocketMessageData, payload: ByteArray) {
            val clipped = payload.size > MESSAGE_LIMIT
            store.onWebSocketMessage(
                message.id,
                WebSocketRecord(
                    message = message,
                    payload = if (clipped) payload.copyOf(MESSAGE_LIMIT) else payload,
                    clipped = clipped,
                ),
            )
        }

        override fun onWebSocketEnd(message: WebSocketEndData) {
            store.onWebSocketEnd(message)
            // Worth saying out loud for the same reason dropped frames are: the
            // transcript looks whole either way, and nothing else will mention
            // that part of the conversation was never captured.
            if (message.dropped > 0 || message.truncated > 0) {
                val lost = buildList {
                    if (message.dropped > 0) add("${message.dropped} message(s) dropped")
                    if (message.truncated > 0) add("${message.truncated} cut short")
                }
                onLog(
                    LogEntry(
                        "warn", "proxy",
                        "websocket ${message.id} transcript is incomplete: " +
                            lost.joinToString(", "),
                    )
                )
            }
        }

        /**
         * Keeps the published status current, and says out loud the two things
         * a status frame can report that the app cannot otherwise notice: a
         * proxy that is up without a port, and frames dropped because this end
         * was not draining stdout fast enough. Both are silent failures — the
         * app looks healthy and the capture is simply incomplete.
         */
        override fun onStatus(status: ProxyStatus) {
            onUi { this@ProxyService.status = status }

            if (status.portLost && !reportedPortLost) {
                reportedPortLost = true
                onLog(
                    LogEntry(
                        "error", "proxy",
                        "sidecar is running but bound no address — port ${status.port} " +
                            "is taken, and nothing is being captured",
                    )
                )
            } else if (status.bound) {
                reportedPortLost = false
            }

            val dropped = status.counters.droppedFrames
            if (dropped > reportedDrops) {
                val delta = dropped - reportedDrops
                reportedDrops = dropped
                onLog(
                    LogEntry(
                        "warn", "proxy",
                        "sidecar dropped $delta frame(s) with its queue full " +
                            "($dropped since start) — those entries are incomplete",
                    )
                )
            }
        }

        override fun onStderr(line: String) =
            this@ProxyService.onLog(LogEntry("error", "sidecar", line))

        override fun onExit(exitCode: Int) =
            this@ProxyService.onExit(exitCode)
    }
}
