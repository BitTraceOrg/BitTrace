package org.bittrace.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.awt.EventQueue

/** One log line, stamped with its receipt time. */
data class LogRecord(
    /** Monotonic id, unique for the session — stable identity for list rendering. */
    val seq: Long,
    val timeMillis: Long,
    val level: String,
    val source: String,
    val message: String,
)

/**
 * In-memory log buffer backing the log panel. Records are Compose snapshot state
 * so the panel and the status-bar counts recompose as lines arrive. Additions
 * hop to the AWT/Compose UI thread (proxy callbacks fire off reader threads).
 */
class LogStore(private val capacity: Int = 2000) {

    private val backing = mutableStateListOf<LogRecord>()

    /** Next [LogRecord.seq]; only touched on the UI thread, like [backing]. */
    private var nextSeq = 0L

    /** Observable log records, oldest first. */
    val records: SnapshotStateList<LogRecord> get() = backing

    val warnCount: Int get() = backing.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount: Int get() = backing.count { it.level.equals("error", ignoreCase = true) }

    fun add(level: String, source: String, message: String): Unit = onUi {
        backing.add(LogRecord(nextSeq++, System.currentTimeMillis(), level, source, message))
        while (backing.size > capacity) backing.removeAt(0)
    }

    fun clear(): Unit = onUi { backing.clear() }

    private inline fun onUi(crossinline block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
    }
}
