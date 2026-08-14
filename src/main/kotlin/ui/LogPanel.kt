package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.bittrace.data.LogRecord
import org.bittrace.data.LogStore
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
private const val MIN_COL = 36f

private fun levelColor(level: String): Color = when (level.lowercase()) {
    "info" -> P.info
    "debug" -> P.dim
    "warn" -> P.warn
    "error" -> P.err
    else -> P.dim
}

/**
 * Floating log panel (DESIGN.md §6.11) — a bottom-docked overlay holding a grid
 * of log lines. Toggled from the status-bar LOGS button (which also closes it).
 * The TIME/LEVEL/SOURCE columns are resizable; MESSAGE fills the rest. Follows
 * the tail as new lines arrive and can be filtered by level.
 */
@Composable
fun LogPanel(logStore: LogStore, height: Dp, onResizeTop: (Dp) -> Unit) {
    var filter by remember { mutableStateOf("ALL") }
    // Resizable widths (dp) for TIME / LEVEL / SOURCE; MESSAGE is flexible.
    val widths = remember { mutableStateListOf(96f, 60f, 76f) }

    val visible = logStore.records.filter { r ->
        when (filter) {
            "WARN" -> r.level.equals("warn", true) || r.level.equals("error", true)
            "ERROR" -> r.level.equals("error", true)
            else -> true
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Column(
        Modifier.fillMaxWidth().height(height).shadow(10.dp).background(P.chrome),
    ) {
        // Drag the top edge to resize the panel (grows upward).
        HorizontalSplitter { onResizeTop(it) }

        // Header: title, level filter, clear.
        Row(
            Modifier.fillMaxWidth().height(26.dp).background(P.head).bottomBorder(P.line).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PzText("PROXY LOG", color = P.dim, size = 11, family = P.Ui)
            Spacer(Modifier.weight(1f))
            Row(Modifier.border1(P.line), verticalAlignment = Alignment.CenterVertically) {
                listOf("ALL", "WARN", "ERROR").forEachIndexed { i, f ->
                    val on = filter == f
                    Box(
                        Modifier.background(if (on) P.accent else Color.Transparent)
                            .then(if (i > 0) Modifier.leftBorder(P.line) else Modifier)
                            .pointerHoverIcon(PointerIcon.Hand).clickable { filter = f }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { PzText(f, color = if (on) P.bg else P.dim, size = 11, family = P.Ui) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.border1(P.line).pointerHoverIcon(PointerIcon.Hand).clickable { logStore.clear() }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) { PzText("CLEAR", color = P.dim, size = 11, family = P.Ui) }
        }

        // Column header with resize handles.
        Row(
            Modifier.fillMaxWidth().height(20.dp).background(P.head).bottomBorder(P.line),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeadCell("TIME", widths, 0)
            HeadCell("LEVEL", widths, 1)
            HeadCell("SOURCE", widths, 2)
            HeadFlex("MESSAGE")
        }

        // Rows.
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(visible) { record -> LogRow(record, widths) }
            }
        }
    }
}

@Composable
private fun LogRow(r: LogRecord, widths: SnapshotStateList<Float>) {
    Row(
        Modifier.fillMaxWidth().height(19.dp).bottomBorder(P.line2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(widths[0]) { RowText(CLOCK.format(Instant.ofEpochMilli(r.timeMillis)), P.faint) }
        Cell(widths[1]) { RowText(r.level.uppercase(), levelColor(r.level)) }
        Cell(widths[2]) { RowText(r.source, P.dim) }
        FlexCell { RowText(r.message, P.text) }
    }
}

@Composable
private fun RowText(text: String, color: Color) =
    PzText(text, color = color, size = 12, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)

@Composable
private fun RowScope.Cell(width: Float, content: @Composable () -> Unit) {
    Row(Modifier.width(width.dp).fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun RowScope.FlexCell(content: @Composable () -> Unit) {
    Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun RowScope.HeadCell(text: String, widths: SnapshotStateList<Float>, index: Int) {
    Box(Modifier.width(widths[index].dp).fillMaxHeight()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PzText(text, color = P.faint, size = 11, family = P.Ui)
        }
        // Right-edge resize handle: grows/shrinks this column; MESSAGE absorbs.
        Box(
            Modifier.align(Alignment.CenterEnd).width(6.dp).fillMaxHeight()
                .pointerHoverIcon(ResizeCursor)
                .pointerInput(index) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        widths[index] = (widths[index] + dragAmount.toDp().value).coerceAtLeast(MIN_COL)
                    }
                },
        )
    }
}

@Composable
private fun RowScope.HeadFlex(text: String) {
    Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PzText(text, color = P.faint, size = 11, family = P.Ui)
    }
}
