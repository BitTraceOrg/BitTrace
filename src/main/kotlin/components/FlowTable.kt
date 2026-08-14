package org.bittrace.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.border1
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.leftBorder
import org.bittrace.ui.rightBorder
import java.awt.Cursor

/** East-west resize cursor shown when hovering a column edge. */
private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/** Minimum on-screen column width, in px, enforced while resizing. */
private const val MIN_COL_PX = 28f

/**
 * One flow-table column. Columns are laid out by [weight] so they always fill
 * the table width and reflow when the window resizes; dragging a column edge
 * shifts weight between neighbours. Held in a snapshot list so headers can also
 * be dragged to reorder. [value] backs filtering; [cell] renders the value.
 */
class Col(
    val key: String,
    val label: String,
    initialWeight: Float,
    val end: Boolean = false,
    val filterable: Boolean = true,
    val presets: List<String> = emptyList(),
    val value: (TrafficRow) -> String,
    val cell: @Composable (TrafficRow) -> Unit,
) {
    var weight by mutableStateOf(initialWeight)
}

/** The default column set (DESIGN.md §6.8). `#` is not filterable. */
fun defaultColumns(): List<Col> = listOf(
    Col("id", "#", 36f, end = true, filterable = false, value = { it.rowCount.toString() }) {
        CText(it.rowCount.toString(), P.faint)
    },
    Col("st", "CODE", 40f, presets = listOf("200", "301", "404", "ERR"), value = { statusOf(it).first }) {
        val (t, c) = statusOf(it); CText(t, c)
    },
    Col("method", "METHOD", 60f, presets = listOf("GET", "POST", "PUT", "HEAD"),
        value = { it.request.request.method }) { CText(it.request.request.method, P.info) },
    Col("url", "HOST / PATH", 380f, value = { it.request.request.url }) {
        val url = it.request.request.url
        val scheme = url.substringBefore("://", "")
        val (host, path) = hostPath(url)
        Row {
            if (scheme.isNotEmpty()) {
                CText(scheme, if (scheme == "https") P.ok else P.warn)
                CText("://", P.faint)
            }
            CText(host, P.faint)
            CText(path, P.text)
        }
    },
    Col("type", "TYPE", 50f, presets = listOf("html", "css", "js", "img", "text"),
        value = { kindOf(it.request.request.url) }) { CText(kindOf(it.request.request.url), P.dim) },
    Col("tls", "TLS", 56f, presets = listOf("1.3", "1.2", "none"), value = { tlsText(it) }) {
        val t = tlsText(it); CText(t, if (t == "—") P.faint else P.ok)
    },
    Col("size", "SIZE", 64f, end = true, value = { bytesStr(it.response?.response?.bodySize) }) {
        val s = it.response?.response?.bodySize
        CText(bytesStr(s), if (s == null || s < 0) P.err else P.text)
    },
    Col("time", "TIME", 60f, end = true, value = { durStr(it) }) {
        CText(durStr(it), if (it.response?.error == true) P.err else P.text)
    },
    Col("start", "START", 72f, value = { startStr(it) }) { CText(startStr(it), P.dim) },
    Col("end", "END", 72f, value = { endStr(it) }) { CText(endStr(it), P.dim) },
)

/** Rows passing every active column filter (case-insensitive substring). */
fun applyFilters(rows: List<TrafficRow>, cols: List<Col>, filters: Map<String, String>): List<TrafficRow> {
    val active = cols.mapNotNull { c -> filters[c.key]?.takeIf { it.isNotBlank() }?.let { c to it } }
    if (active.isEmpty()) return rows
    return rows.filter { row -> active.all { (c, term) -> c.value(row).contains(term, ignoreCase = true) } }
}

@Composable
fun FlowTable(
    cols: SnapshotStateList<Col>,
    filters: SnapshotStateMap<String, String>,
    rows: List<TrafficRow>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(P.panel)) {
        Column(Modifier.fillMaxSize()) {
            HeaderRow(cols, filters)
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { row -> DataRow(cols, row, row.id == selectedId, onSelect) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header — drag body to reorder, drag right edge to resize, funnel to filter.
// ---------------------------------------------------------------------------

@Composable
private fun HeaderRow(cols: SnapshotStateList<Col>, filters: SnapshotStateMap<String, String>) {
    val bounds = remember { mutableStateMapOf<String, ClosedFloatingPointRange<Float>>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointerX by remember { mutableStateOf(0f) }
    var openFilter by remember { mutableStateOf<String?>(null) }
    var tableWidthPx by remember { mutableStateOf(0f) }

    Row(
        Modifier.fillMaxWidth().height(24.dp).background(P.head).bottomBorder(P.line)
            .onGloballyPositioned { tableWidthPx = it.size.width.toFloat() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEachIndexed { index, col ->
            Box(
                Modifier.weight(col.weight).fillMaxHeight()
                    .then(if (index < cols.lastIndex) Modifier.rightBorder(P.line2) else Modifier)
                    .background(if (dragging == col.key) P.accentFill else Color.Transparent)
                    .onGloballyPositioned { c ->
                        val x = c.positionInRoot().x
                        bounds[col.key] = x..(x + c.size.width.toFloat())
                    }
                    .pointerInput(col.key, cols.size) {
                        detectDragGestures(
                            onDragStart = { off ->
                                dragging = col.key
                                pointerX = (bounds[col.key]?.start ?: 0f) + off.x
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, amount ->
                            change.consume()
                            pointerX += amount.x
                            val targetKey = bounds.entries.firstOrNull { pointerX in it.value }?.key
                            val from = cols.indexOfFirst { it.key == dragging }
                            val to = cols.indexOfFirst { it.key == targetKey }
                            if (from >= 0 && to >= 0 && from != to) cols.add(to, cols.removeAt(from))
                        }
                    },
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PzText(
                        col.label,
                        // Match the inspector tab label: SansSerif 12sp, dim (accent when filtered).
                        color = if (filters[col.key]?.isNotBlank() == true) P.accent else P.dim,
                        size = 12, family = P.Ui, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                        // Fill the row so the funnel is pushed to the right corner.
                        modifier = Modifier.weight(1f),
                    )
                    if (col.filterable) {
                        FilterFunnel(col, filters, open = openFilter == col.key,
                            onToggle = { openFilter = if (openFilter == col.key) null else col.key },
                            onDismiss = { openFilter = null })
                    }
                }
                // Resize handle on internal column boundaries; grows this column,
                // shrinks the next, so the table always fills the width.
                if (index < cols.lastIndex) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(6.dp).fillMaxHeight()
                            .pointerHoverIcon(ResizeCursor)
                            .pointerInput(col.key) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    resize(cols, col.key, dragAmount, tableWidthPx)
                                }
                            },
                    )
                }
            }
        }
    }
}

/** Moves [dragAmount] px of width from the column after [key] into it (or back). */
private fun resize(cols: SnapshotStateList<Col>, key: String, dragAmount: Float, tableWidthPx: Float) {
    if (tableWidthPx <= 0f) return
    val i = cols.indexOfFirst { it.key == key }
    val self = cols.getOrNull(i) ?: return
    val next = cols.getOrNull(i + 1) ?: return
    val total = cols.fold(0f) { a, c -> a + c.weight }
    val minW = MIN_COL_PX / tableWidthPx * total
    var d = dragAmount / tableWidthPx * total
    if (self.weight + d < minW) d = minW - self.weight
    if (next.weight - d < minW) d = next.weight - minW
    self.weight += d
    next.weight -= d
}

@Composable
private fun FilterFunnel(
    col: Col,
    filters: SnapshotStateMap<String, String>,
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = filters[col.key]?.isNotBlank() == true
    Box {
        Box(
            Modifier.size(15.dp)
                .then(if (active || open) Modifier.border1(P.accent) else Modifier)
                .background(if (active) P.accentFill else Color.Transparent)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(9.dp)) {
                val p = Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f)
                    lineTo(size.width * 0.6f, size.height * 0.5f)
                    lineTo(size.width * 0.6f, size.height); lineTo(size.width * 0.4f, size.height)
                    lineTo(size.width * 0.4f, size.height * 0.5f); close()
                }
                drawPath(p, if (active) P.accent else if (open) P.text else P.faint)
            }
        }
        if (open) FilterPopup(col, filters, onDismiss)
    }
}

@Composable
private fun FilterPopup(col: Col, filters: SnapshotStateMap<String, String>, onDismiss: () -> Unit) {
    Popup(
        offset = IntOffset(-8, 18),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(if (col.presets.isNotEmpty()) 128.dp else 176.dp)
                .background(P.chrome).border1(P.accent),
        ) {
            Row(
                Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PzText(col.label, color = P.faint, size = 11, family = P.Ui)
                Spacer(Modifier.weight(1f))
                Box(Modifier.clickable { filters.remove(col.key); onDismiss() }) {
                    PzText("CLEAR", color = P.dim, size = 11)
                }
            }
            Box(Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 7.dp, vertical = 5.dp)) {
                val current = filters[col.key] ?: ""
                BasicTextField(
                    value = current,
                    onValueChange = { filters[col.key] = it },
                    singleLine = true,
                    textStyle = TextStyle(color = P.text, fontSize = 13.sp, fontFamily = P.Mono),
                    cursorBrush = SolidColor(P.accent),
                    modifier = Modifier.fillMaxWidth().height(20.dp).background(P.bg).border1(P.line)
                        .padding(horizontal = 5.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (current.isEmpty()) PzText("contains…", color = P.faint, size = 12)
                            inner()
                        }
                    },
                )
            }
            col.presets.forEach { preset ->
                val on = filters[col.key] == preset
                Box(
                    Modifier.fillMaxWidth().background(if (on) P.sel else Color.Transparent)
                        .clickable { filters[col.key] = preset; onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) { PzText(preset, color = if (on) P.accent else P.dim, size = 13) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Data rows
// ---------------------------------------------------------------------------

@Composable
private fun DataRow(cols: List<Col>, row: TrafficRow, selected: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(22.dp)
            .background(if (selected) P.sel else Color.Transparent)
            .then(if (selected) Modifier.leftBorder(P.accent, 2.dp) else Modifier)
            .bottomBorder(P.line2)
            .clickable { onSelect(row.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEachIndexed { index, col ->
            Row(
                Modifier.weight(col.weight).fillMaxHeight()
                    .then(if (index < cols.lastIndex) Modifier.rightBorder(P.line2) else Modifier)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (col.end) Arrangement.End else Arrangement.Start,
            ) { col.cell(row) }
        }
    }
}

@Composable
private fun CText(text: String, color: Color) =
    PzText(text, color = color, size = 13, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
