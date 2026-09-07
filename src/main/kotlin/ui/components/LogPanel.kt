package org.bittrace.ui.components

import org.bittrace.ui.clockOf
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.bittrace.data.LogRecord
import org.bittrace.data.LogStore

import org.bittrace.ui.P

private fun levelColor(level: String): Color = when (level.lowercase()) {
    "info" -> P.info
    "debug" -> P.dim
    "warn" -> P.warn
    "error" -> P.err
    else -> P.dim
}

/** TIME/LEVEL/SOURCE keep their dp width as the panel grows; MESSAGE absorbs the rest. */
private fun logColumns(): List<GridColumn<LogRecord>> = listOf(
    GridColumn("time", "Time", 96f, fixed = true, filterable = false) {
        LogText(clockOf(it.timeMillis), P.faint)
    },
    GridColumn("level", "Level", 60f, fixed = true, filterable = false) {
        LogText(it.level.uppercase(), levelColor(it.level))
    },
    GridColumn("source", "Source", 76f, fixed = true, filterable = false) { LogText(it.source, P.dim) },
    GridColumn("message", "Message", 1f, filterable = false) { LogText(it.message, P.text) },
)

/**
 * The log grid is denser than the flow table and labels are one step quieter.
 *
 * A getter, not a `val`: a top-level property would read the palette once at
 * class-init and freeze the label colour to whatever theme happened to be
 * active at startup.
 */
private val LogGridStyle: GridStyle
    @Composable get() = GridStyle(
        headerHeight = 20.dp,
        rowHeight = 19.dp,
        cellPadding = 10.dp,
        labelStyle = Typo.caption,
        labelColor = P.faint,
    )

/**
 * Floating log panel (DESIGN.md §6.11) — a bottom-docked overlay holding a grid
 * of log lines from every source the app has: the proxy sidecar, session
 * import/export, the plugin loader, the Forge and its git layer. Toggled from
 * the status-bar LOGS button
 * (which also closes it).
 * The TIME/LEVEL/SOURCE columns are resizable; MESSAGE fills the rest. Follows
 * the tail as new lines arrive and can be filtered by level.
 */
@Composable
fun LogPanel(logStore: LogStore, height: Dp, onResizeTop: (Dp) -> Unit) {
    var filter by remember { mutableStateOf("ALL") }
    val columns = remember { mutableStateListOf(*logColumns().toTypedArray()) }

    val visible = logStore.records.filter { r ->
        when (filter) {
            "WARN" -> r.level.equals("warn", true) || r.level.equals("error", true)
            "ERROR" -> r.level.equals("error", true)
            else -> true
        }
    }

    Column(
        Modifier.fillMaxWidth().height(height).background(P.chrome),
    ) {
        // Drag the top edge to resize the panel (grows upward).
        HorizontalSplitter { onResizeTop(it) }

        // Header: title and level filter.
        PaneHeader(title = "Log") {
            Spacer(Modifier.weight(1f))
            SegmentedToggle(
                segments = listOf(Segment("ALL", "All"), Segment("WARN", "Warn"), Segment("ERROR", "Error")),
                selected = filter,
            ) { filter = it }
        }

        // No markers here, so the marker type has nothing to infer from.
        DataGrid<LogRecord, Nothing>(
            columns = columns,
            rows = visible,
            key = { it.seq },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            followTail = true,
            selectableText = true,
            style = LogGridStyle,
        )
    }
}

@Composable
private fun LogText(text: String, color: Color) = CellText(text, color, Typo.label)
