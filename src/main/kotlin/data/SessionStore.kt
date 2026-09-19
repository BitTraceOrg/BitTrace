package org.bittrace.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.awt.EventQueue

/** The live capture's session id. Imported sessions get 1, 2, 3… */
const val LIVE_SESSION = 0

/** A note in the flow list that a session was written to disk. */
class ExportMark(val id: Long, val label: String, val afterRowCount: Int)

/**
 * The four decoded messages that make up one imported flow. Mirrors what the
 * sidecar delivers across four frames, so an imported row is indistinguishable
 * from a captured one once it is in the store.
 */
class ImportedFlow(
    val request: InitialRequestData,
    val response: InitialResponseData? = null,
    val completeRequest: CompleteRequestMessage? = null,
    val completeResponse: CompleteResponseMessage? = null,
)

/**
 * In-memory store for one debugging session: the flows captured live, plus any
 * imported from a HAR.
 *
 * Live flows arrive as four separate messages (initial request, initial
 * response, complete request, complete response) that share a flow id; the
 * store merges them into one [TrafficRow] per flow and exposes the rows as a
 * Compose [SnapshotStateList] so the UI recomposes as they arrive.
 *
 * Proxy events come off a sidecar reader thread, so every mutation hops onto the
 * AWT event thread (which is also Compose Desktop's UI thread). Read the list
 * only from a composition.
 *
 * Each row carries the [TrafficRow.sessionId] it belongs to. The grid derives
 * its session banner lines from runs of that tag rather than from stored
 * positions, so the banners survive filtering, eviction and [clear].
 */
class SessionStore(
    /** Oldest rows are dropped once the list grows past this. 0 = unbounded. */
    private val capacity: Int = 0,
    /**
     * Called with the `startedDateTime` of each **live** flow as it arrives.
     *
     * Imported flows deliberately do not fire it: a HAR is someone else's
     * traffic, often from another machine and another week, and counting it as
     * activity here would make the dashboard describe a file rather than this
     * proxy. Kept as a callback so the store still owes nothing to whatever is
     * keeping the tally.
     */
    private val onLiveFlow: (String) -> Unit = {},
) {
    private val backing = mutableStateListOf<TrafficRow>()
    private val byId = HashMap<String, TrafficRow>()
    private var nextRowCount = 1
    private var nextSessionId = LIVE_SESSION

    /**
     * While a session is importing, live rows land here instead of [backing],
     * so an imported session stays one contiguous run. They are still
     * registered in [byId] the moment they arrive, so the three merge-by-id
     * callbacks keep finding their row.
     */
    private var importing: Int? = null
    private val deferred = mutableListOf<TrafficRow>()

    /**
     * Bumped by [clear]. An import checks it between batches and abandons the
     * rest rather than repopulating a list the user just emptied.
     */
    var generation = 0
        private set

    /** Observable, snapshot-backed view of the captured rows in arrival order. */
    val rows: SnapshotStateList<TrafficRow> get() = backing

    /** Export notes, in the order they were made. */
    val exportMarks: SnapshotStateList<ExportMark> = mutableStateListOf()

    /** Imported session id -> the name its banners carry. */
    private val sessionNames = mutableStateMapOf<Int, String>()

    val size: Int get() = backing.size

    /**
     * Roughly how much heap the captured rows occupy, in bytes.
     *
     * An estimate, and labelled as one wherever it is shown. Java gives no way
     * to ask an object graph its size without instrumentation, so this counts
     * what actually dominates — the strings — at two bytes a character, and adds
     * a flat allowance per row and per header for the object headers, references
     * and list slots around them. It is deliberately walked on demand rather
     * than maintained incrementally: it is read when somebody opens a readout,
     * which is rare, and a running total would have to be right on every path
     * that ever touches a row.
     *
     * Bodies are not counted here; they live in `BodyCache`, which knows its own
     * size exactly.
     */
    fun estimatedBytes(): Long {
        var total = 0L
        backing.forEach { row ->
            val head = row.request.request
            total += ROW_OVERHEAD
            total += chars(row.request.id) + chars(row.request.startedDateTime) + chars(row.request.tls)
            total += chars(head.method) + chars(head.url) + chars(head.httpVersion)
            row.completeRequest?.request?.let { complete ->
                complete.headers.forEach { total += PAIR_OVERHEAD + chars(it.name) + chars(it.value) }
                complete.cookies.forEach { total += PAIR_OVERHEAD + chars(it.name) + chars(it.value) }
            }
            row.completeResponse?.response?.let { complete ->
                complete.headers.forEach { total += PAIR_OVERHEAD + chars(it.name) + chars(it.value) }
                complete.cookies.forEach { total += PAIR_OVERHEAD + chars(it.name) + chars(it.value) }
            }
            // A WebSocket transcript is held on the row rather than in
            // `BodyCache`, so it is counted here or it is not counted anywhere.
            row.webSocketMessages.forEach { total += ROW_OVERHEAD + it.payload.size }
        }
        return total
    }

    /** UTF-16, so two bytes a character plus the string object around them. */
    private fun chars(text: String): Long = text.length * 2L + STRING_OVERHEAD

    fun get(id: String): TrafficRow? = byId[id]

    /** The newest row's number, for anchoring an [ExportMark]. */
    val lastRowCount: Int get() = backing.lastOrNull()?.rowCount ?: 0

    fun onInitialRequest(data: InitialRequestData): Unit = onUi {
        if (byId.containsKey(data.id)) return@onUi
        add(TrafficRow(nextRowCount++, data, LIVE_SESSION))
    }

    /**
     * Adds the CONNECT that opened a tunnel as a row of its own.
     *
     * It is a flow like any other once it is here — the frame carries the same
     * HAR request head, and its response arrives through [onInitialResponse]
     * under the same id. Its headers come with it rather than on a later frame,
     * so the row is complete on the request side the moment it lands.
     */
    fun onConnectRequest(data: ConnectRequestData): Unit = onUi {
        if (byId.containsKey(data.id)) return@onUi
        add(
            TrafficRow(
                nextRowCount++,
                data.toInitialRequest(),
                LIVE_SESSION,
                isConnect = true,
                clientAddress = data.clientAddress,
            ).apply { completeRequest = data.toCompleteRequest() }
        )
    }

    /** Registers a fresh live row and places it. Call on the UI thread. */
    private fun add(row: TrafficRow) {
        onLiveFlow(row.request.startedDateTime)
        byId[row.id] = row
        // Mid-import, hold the row back so the imported run stays contiguous —
        // but never drop it, and never delay its byId registration.
        if (importing != null && deferred.size < MAX_DEFERRED) deferred.add(row) else backing.add(row)
        trim()
    }

    fun onInitialResponse(data: InitialResponseData): Unit = onUi {
        byId[data.id]?.response = data
    }

    // Headers and cookies arrive already pooled (see TrafficStrings), so these
    // store the decoded message as-is.
    fun onCompleteRequest(message: CompleteRequestMessage): Unit = onUi {
        byId[message.id]?.completeRequest = message
        byId[message.id]?.streamedRequestBytes = 0
    }

    fun onCompleteResponse(message: CompleteResponseMessage): Unit = onUi {
        byId[message.id]?.completeResponse = message
        // The body finished with it, whatever the count had reached.
        byId[message.id]?.streamedResponseBytes = 0
    }

    /**
     * How much of a streamed body has arrived, while it is still arriving.
     *
     * Called as chunks land rather than once at the end, because a live stream
     * has no end worth waiting for — the caller throttles, since chunks are
     * small and frequent by design and this hops to the UI thread.
     */
    fun onStreamProgress(id: String, request: Boolean, received: Long): Unit = onUi {
        val row = byId[id] ?: return@onUi
        if (request) row.streamedRequestBytes = received else row.streamedResponseBytes = received
    }

    /**
     * Appends one WebSocket message to its flow's transcript.
     *
     * Bounded on both counts, because a WebSocket is the one flow with no
     * natural end: a feed that pushes a message a second all afternoon would
     * otherwise grow a row without limit, and unlike a body there is no
     * completing frame to stop it. Past either cap the oldest messages go and
     * [TrafficRow.webSocketEvicted] counts them, so the transcript says it is a
     * tail rather than quietly appearing to be the whole conversation.
     *
     * The newest are the ones kept. For a connection being watched live, what
     * just arrived is the reason the pane is open.
     */
    fun onWebSocketMessage(id: String, record: WebSocketRecord): Unit = onUi {
        val row = byId[id] ?: return@onUi
        row.webSocketMessages.add(record)
        row.webSocketBytes += record.payload.size

        var evicted = 0L
        // The size guard on the byte cap keeps the newest message, however
        // large: a single 4 MiB frame is still the thing being looked at, and
        // evicting it would leave a transcript that drops every message it
        // receives.
        while (row.webSocketMessages.size > MAX_SOCKET_MESSAGES ||
            (row.webSocketBytes > MAX_SOCKET_BYTES && row.webSocketMessages.size > 1)
        ) {
            row.webSocketBytes -= row.webSocketMessages.removeAt(0).payload.size
            evicted++
        }
        if (evicted > 0) row.webSocketEvicted += evicted
    }

    /** The connection closed; its totals stay on the row beside the transcript. */
    fun onWebSocketEnd(message: WebSocketEndData): Unit = onUi {
        byId[message.id]?.webSocketEnd = message
    }

    // --- import ---

    /**
     * Opens an imported session and returns its id. Live flows that arrive
     * before the matching [endSession] are held back so the import lands as one
     * unbroken run.
     */
    fun beginSession(label: String): Int {
        var id = LIVE_SESSION
        onUiBlocking {
            id = ++nextSessionId
            sessionNames[id] = label
            importing = id
        }
        return id
    }

    /** The name an imported session was given, for its banner lines. */
    fun sessionName(id: Int): String? = sessionNames[id]

    /**
     * Appends one batch of imported flows. Batching matters: a HAR with tens of
     * thousands of entries posted one row at a time would do nothing but
     * marshal runnables, and each `add` is its own snapshot write.
     */
    fun importBatch(sessionId: Int, flows: List<ImportedFlow>): Unit = onUi {
        val fresh = ArrayList<TrafficRow>(flows.size)
        for (flow in flows) {
            if (byId.containsKey(flow.request.id)) continue
            val row = TrafficRow(nextRowCount++, flow.request, sessionId).apply {
                response = flow.response
                completeRequest = flow.completeRequest
                completeResponse = flow.completeResponse
            }
            byId[flow.request.id] = row
            fresh.add(row)
        }
        backing.addAll(fresh)
        trim()
    }

    /** Closes the session and releases any live rows held back during it. */
    fun endSession(sessionId: Int): Unit = onUi {
        if (importing != sessionId) return@onUi
        importing = null
        if (deferred.isNotEmpty()) {
            backing.addAll(deferred)
            deferred.clear()
            trim()
        }
    }

    // --- markers ---

    /** Records that the session was exported, anchored after the newest row. */
    fun addExportMark(label: String): Unit = onUi {
        exportMarks.add(ExportMark(nextMarkId++, label, lastRowCount))
    }

    fun clear(): Unit = onUi {
        backing.clear()
        byId.clear()
        deferred.clear()
        exportMarks.clear()
        sessionNames.clear()
        importing = null
        nextRowCount = 1
        nextSessionId = LIVE_SESSION
        generation++
    }

    private fun trim() {
        if (capacity <= 0) return
        while (backing.size > capacity) {
            val evicted = backing.removeAt(0)
            byId.remove(evicted.id)
        }
        // An export note whose row is gone has nothing left to point at.
        val oldest = backing.firstOrNull()?.rowCount ?: 0
        exportMarks.removeAll { it.afterRowCount < oldest }
    }

    /**
     * Mutations handed over by the reader thread, waiting for the event thread.
     *
     * The reason this exists is the same one [importBatch] gives for batching a
     * HAR: posting one runnable per message does little but marshal runnables,
     * and each write is its own snapshot. A flow is four messages, a streamed
     * body adds a progress message per throttle window, and the sidecar can
     * emit thousands of frames a second — so the live path was paying exactly
     * the cost the import path was written to avoid.
     *
     * Guarded by [queueLock] rather than being a concurrent queue: the drain
     * has to take everything and reset [drainScheduled] in the same breath, or
     * a mutation handed over between the two would sit in the queue with
     * nothing scheduled to come back for it.
     */
    private val queueLock = Any()
    private val queued = ArrayDeque<() -> Unit>()
    private var drainScheduled = false

    /**
     * Runs [block] on the event thread, batched with whatever else is waiting.
     *
     * Already on the event thread, it runs now — but only after draining what
     * is queued, so a UI action cannot overtake capture events that were handed
     * over before it. That ordering is the whole reason this is not simply a
     * post: `clear()` arrives this way, and rows added ahead of it that landed
     * behind it would survive being cleared.
     */
    private inline fun onUi(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            drainQueued()
            block()
            return
        }
        val needsSchedule = synchronized(queueLock) {
            queued.addLast { block() }
            if (drainScheduled) false else { drainScheduled = true; true }
        }
        if (needsSchedule) EventQueue.invokeLater(::drain)
    }

    /** Like [onUi] but waits, for the few callers that need a result back. */
    private inline fun onUiBlocking(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            drainQueued()
            block()
        } else {
            EventQueue.invokeAndWait { drainQueued(); block() }
        }
    }

    /**
     * Applies one batch, then re-posts if more arrived while it was applying.
     *
     * Re-posting rather than looping until empty: a capture fast enough to
     * refill the queue mid-drain would otherwise hold the event thread for as
     * long as it kept up, and the rows being added would never be painted. A
     * fresh event gives the frame a turn between batches.
     */
    private fun drain() {
        drainQueued()
        val more = synchronized(queueLock) {
            if (queued.isEmpty()) drainScheduled = false
            drainScheduled
        }
        if (more) EventQueue.invokeLater(::drain)
    }

    /**
     * Applies everything queued at the moment of the call, in arrival order.
     *
     * One mutable snapshot around the batch, so a hundred rows arriving
     * together commit once instead of a hundred times. Call on the event thread.
     */
    private fun drainQueued() {
        val batch = synchronized(queueLock) {
            if (queued.isEmpty()) return
            val taken = ArrayList<() -> Unit>(queued)
            queued.clear()
            taken
        }
        Snapshot.withMutableSnapshot { batch.forEach { it() } }
    }

    private var nextMarkId = 1L

    private companion object {
        /** Live rows held during an import before giving up and letting the run split. */
        const val MAX_DEFERRED = 5_000

        /** Messages kept per WebSocket; see [onWebSocketMessage]. */
        const val MAX_SOCKET_MESSAGES = 5_000

        /** Payload bytes kept per WebSocket, across all of its messages. */
        const val MAX_SOCKET_BYTES = 16L * 1024 * 1024

        /** A row plus its nested heads, references and snapshot state holders. */
        private const val ROW_OVERHEAD = 512L

        /** A name/value pair: two object headers and a list slot. */
        private const val PAIR_OVERHEAD = 64L

        /** A String object, before its characters. */
        private const val STRING_OVERHEAD = 40L
    }
}
