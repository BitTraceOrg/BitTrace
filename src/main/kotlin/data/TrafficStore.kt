package org.bittrace.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.awt.EventQueue

/**
 * In-memory store for captured traffic.
 *
 * Flows arrive as four separate messages (initial request, initial response,
 * complete request, complete response) that share a flow id; the store merges
 * them into one [TrafficRow] per flow and exposes the rows as a Compose
 * [SnapshotStateList] so the UI recomposes as they arrive.
 *
 * Proxy events come off a sidecar reader thread, so every mutation hops onto the
 * AWT event thread (which is also Compose Desktop's UI thread). Read the list
 * only from a composition.
 */
class TrafficStore(
    /** Oldest rows are dropped once the list grows past this. 0 = unbounded. */
    private val capacity: Int = 0,
) {
    private val backing = mutableStateListOf<TrafficRow>()
    private val byId = HashMap<String, TrafficRow>()
    private var nextRowCount = 1

    /** Observable, snapshot-backed view of the captured rows in arrival order. */
    val rows: SnapshotStateList<TrafficRow> get() = backing

    val size: Int get() = backing.size

    fun get(id: String): TrafficRow? = byId[id]

    fun onInitialRequest(data: InitialRequestData): Unit = onUi {
        if (byId.containsKey(data.id)) return@onUi
        val row = TrafficRow(nextRowCount++, data)
        byId[data.id] = row
        backing.add(row)
        trim()
    }

    fun onInitialResponse(data: InitialResponseData): Unit = onUi {
        byId[data.id]?.response = data
    }

    fun onCompleteRequest(message: CompleteRequestMessage): Unit = onUi {
        byId[message.id]?.completeRequest = message
    }

    fun onCompleteResponse(message: CompleteResponseMessage): Unit = onUi {
        byId[message.id]?.completeResponse = message
    }

    fun clear(): Unit = onUi {
        backing.clear()
        byId.clear()
        nextRowCount = 1
    }

    private fun trim() {
        if (capacity <= 0) return
        while (backing.size > capacity) {
            val evicted = backing.removeAt(0)
            byId.remove(evicted.id)
        }
    }

    private inline fun onUi(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
    }
}
