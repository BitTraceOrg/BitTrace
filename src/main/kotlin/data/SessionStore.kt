package org.bittrace.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
        onLiveFlow(data.startedDateTime)
        val row = TrafficRow(nextRowCount++, data, LIVE_SESSION)
        byId[data.id] = row
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
    }

    fun onCompleteResponse(message: CompleteResponseMessage): Unit = onUi {
        byId[message.id]?.completeResponse = message
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

    private inline fun onUi(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
    }

    /** Like [onUi] but waits, for the few callers that need a result back. */
    private inline fun onUiBlocking(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait { block() }
    }

    private var nextMarkId = 1L

    private companion object {
        /** Live rows held during an import before giving up and letting the run split. */
        const val MAX_DEFERRED = 5_000

        /** A row plus its nested heads, references and snapshot state holders. */
        private const val ROW_OVERHEAD = 512L

        /** A name/value pair: two object headers and a list slot. */
        private const val PAIR_OVERHEAD = 64L

        /** A String object, before its characters. */
        private const val STRING_OVERHEAD = 40L
    }
}
