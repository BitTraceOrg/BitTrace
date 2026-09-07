package org.bittrace.ui.components

import org.bittrace.ui.ChipShape
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.revealed
import kotlin.math.roundToInt
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.geometry.Offset
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.MenuScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.awt.Cursor
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The shared column grid behind the flow table and the proxy log: a resizable
 * column header over a lazily-scrolled body, with optional reordering, filter
 * funnels, row selection and tail-following.
 *
 * Everything a specific grid differs in is a parameter, so a caller supplies
 * only its columns, its rows and the behaviours it wants.
 */

/** East-west resize cursor shown when hovering a column edge. */
private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/** Minimum on-screen column width, in px, enforced while resizing. */
private const val MIN_COL_PX = 28f

/** How tall a filter popup's tick list gets before it scrolls. */
private val FACET_LIST_HEIGHT = 200.dp

/**
 * Tick lists the grid renders but does not own.
 *
 * The flow table's Code, Method and Type funnels and the overview band's facet
 * columns are the same three sets of ticks. Held separately they were two
 * filters that happened to agree until somebody used either one: ticking 4xx in
 * a header left the band showing nothing selected, and the grid then applied the
 * two independently, so the row count answered to a query neither panel had
 * drawn. This is the seam that makes them one — the band keeps the state, the
 * header edits it, and the grid stops filtering on it entirely because the owner
 * already has.
 */
class GridFacetBinding(
    /** Column keys this binding covers. */
    val keys: Set<String>,
    val selected: (String) -> Set<String>,
    val toggle: (String, String) -> Unit,
    val clear: (String) -> Unit,
)

/** Whether [key]'s header should read as filtered, wherever its ticks live. */
private fun columnIsFiltered(
    key: String,
    filters: SnapshotStateMap<String, ColumnFilter>?,
    bound: GridFacetBinding?,
): Boolean {
    return filters?.get(key)?.isActive == true || bound != null && key in bound.keys && bound.selected(key).isNotEmpty()
}

/** The popup floats over the grid, so it is shaped like a card rather than a cell. */
private val POPUP_SHAPE = RoundedCornerShape(6.dp)

/** Its inputs and comparison chips, at the radius the rest of the app uses. */

/** The controls that sit inside a field, sized to fit its 24dp without touching the frame. */
private val TRAILING = 18.dp

/**
 * One grid column over rows of type [T].
 *
 * [weight] means one of two things, selected by [fixed]: a *flexible* column
 * (the default) takes that share of the leftover width, so the grid always
 * fills its container and reflows on resize; a *fixed* column is exactly that
 * many dp wide and keeps its size as the grid grows. Dragging a column edge
 * adjusts whichever applies.
 *
 * [value] backs filtering (and needs an implementation only for filterable
 * columns); [cell] renders the value.
 */
class GridColumn<T>(
    val key: String,
    val label: String,
    initialWeight: Float,
    val fixed: Boolean = false,
    val end: Boolean = false,
    val filterable: Boolean = true,
    val presets: List<String> = emptyList(),
    /**
     * The tick list, for a column whose values are a closed set.
     *
     * Only a closed set gets one. A list built from the data can offer solely
     * what has already arrived — so you could not filter for 5xx before the
     * first one showed up, and by then you were looking at it — and on an open
     * set like host or URL it grows without bound, turning the popup into a
     * scrolling directory you have to search before you can filter with it.
     * Open sets get the text field, which is the right tool for a value you have
     * to describe rather than pick.
     */
    val facets: List<String> = emptyList(),
    /**
     * This column's value as a number, when it has one.
     *
     * Present only so a column can be compared rather than matched — "bigger
     * than 100 KB" is not a substring of anything, and no set of ticks
     * expresses it either.
     */
    val numeric: ((T) -> Long?)? = null,
    val value: (T) -> String = { "" },
    /**
     * The facet a row falls under, when this column filters by picking from a
     * list rather than by typing. The filter popup offers every distinct value
     * present in the data, and a row matches if its facet is among those
     * ticked — so the HOST column can offer the hosts actually captured, and
     * match on the host alone rather than on a substring of the whole URL.
     */
    val facet: ((T) -> String)? = null,
    val cell: @Composable (T) -> Unit,
) {
    var weight by mutableStateOf(initialWeight)
}

/**
 * One column's filter: typed text, a set of ticked facets, or both.
 *
 * Two mechanisms because they answer different questions — "urls containing
 * /api/" is a substring, "any of these four hosts" is a set, and neither
 * expresses the other.
 */
class ColumnFilter(
    val text: String = "",
    val selected: Set<String> = emptySet(),
    /** One of [OP_NONE], [OP_LARGER], [OP_SMALLER], [OP_EQUAL]. */
    val op: String = OP_NONE,
    /** What to compare against, as typed. Kept as text so a half-typed number is not a filter. */
    val operand: String = "",
    /**
     * Whether the typed text excludes rather than includes.
     *
     * "Everything except the CDN" is a question the grid could not previously
     * ask at all: you could tick the hosts you wanted, but only after listing
     * them, and the one you wanted gone was usually the one with hundreds of
     * rows. Negation applies to the text alone — ticks stay positive, since a
     * tick list that sometimes meant "not these" would need to say which.
     */
    val negated: Boolean = false,
) {
    val isActive: Boolean
        get() = text.isNotBlank() || selected.isNotEmpty() || (op != OP_NONE && operand.isNotBlank())

    fun withText(value: String) = ColumnFilter(value, selected, op, operand, negated)

    fun withNegated(value: Boolean) = ColumnFilter(text, selected, op, operand, value)

    fun withComparison(newOp: String, newOperand: String) =
        ColumnFilter(text, selected, newOp, newOperand, negated)

    /** Ticks or unticks one facet. */
    fun toggle(facet: String) =
        ColumnFilter(text, if (facet in selected) selected - facet else selected + facet, op, operand, negated)

    /** Whether [value] passes the typed text, negation included. */
    fun textOk(value: String): Boolean {
        return text.isBlank() || value.contains(text, ignoreCase = true) != negated
    }

    /** Whether [value] passes the comparison, or true when there is none to make. */
    fun comparisonOk(value: Long?): Boolean {
        val threshold = parseSize(operand) ?: return true
        if (op == OP_NONE) return true
        // A row with no number cannot satisfy a comparison. Treating a missing
        // size as zero would put every in-flight request under "smaller than".
        val actual = value ?: return false
        return when (op) {
            OP_LARGER -> actual > threshold
            OP_SMALLER -> actual < threshold
            OP_EQUAL -> actual == threshold
            else -> true
        }
    }
}

const val OP_NONE = ""
const val OP_LARGER = ">"
const val OP_SMALLER = "<"
const val OP_EQUAL = "="

/**
 * A size as typed: `2048`, `64kb`, `1.5 MB`.
 *
 * Units because the column shows them — a table reading "1.4 MB" and a filter
 * demanding "1468006" is asking the reader to do arithmetic the machine could
 * do. Null when it does not parse, which is how a half-typed number stays out
 * of the filter instead of matching nothing.
 */
fun parseSize(text: String): Long? {
    val trimmed = text.trim().lowercase()
    if (trimmed.isEmpty()) return null
    val match = Regex("^([0-9]*\\.?[0-9]+)\\s*(b|kb|mb|gb|k|m|g)?$").find(trimmed) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2]) {
        "k", "kb" -> 1024L
        "m", "mb" -> 1024L * 1024
        "g", "gb" -> 1024L * 1024 * 1024
        else -> 1L
    }
    return (amount * multiplier).toLong()
}

/**
 * Full-width lines interleaved with the data rows — session banners in the flow
 * table, and nothing at all in the log panel.
 *
 * The grid stays ignorant of what a marker *is*: [content] renders it, so the
 * caller owns the vocabulary and the styling. [key] must be unique across every
 * marker in the grid, because these become lazy-list items — two markers keyed
 * alike (importing the same file twice, say) would fail the list's key check.
 *
 * [afterKey] maps a row's key to the markers that follow it; a marker attached
 * to a row that is filtered out simply does not render.
 */
class GridMarkers<M>(
    val leading: List<M> = emptyList(),
    val trailing: List<M> = emptyList(),
    val afterKey: Map<Any, List<M>> = emptyMap(),
    val key: (M) -> Any,
    val content: @Composable (M) -> Unit,
)

/** Metrics that separate a dense log grid from a roomier table. */
class GridStyle(
    val headerHeight: Dp = PANE_HEADER_HEIGHT,
    val rowHeight: Dp = 24.dp,
    val cellPadding: Dp = 10.dp,
    val labelStyle: TextStyle? = null,
    val labelColor: Color = P.dim,
)

/**
 * Rows passing every active column filter.
 *
 * Within one column the two mechanisms are ANDed (typed text narrows the ticked
 * facets); the ticked facets themselves are ORed, and columns are ANDed with
 * each other.
 */
fun <T> applyGridFilters(rows: List<T>, cols: List<GridColumn<T>>, filters: Map<String, ColumnFilter>): List<T> {
    val active = cols.mapNotNull { c -> filters[c.key]?.takeIf { it.isActive }?.let { c to it } }
    if (active.isEmpty()) return rows
    return rows.filter { row ->
        active.all { (column, filter) ->
            val textOk = filter.textOk(column.value(row))
            val numberOk = column.numeric?.let { filter.comparisonOk(it(row)) } ?: true
            val facetOk = filter.selected.isEmpty() || run {
                // Without a facet the ticked values are matched against the
                // cell's own text, which is what a preset list means.
                val facet = column.facet?.invoke(row) ?: column.value(row)
                facet in filter.selected
            }
            textOk && facetOk && numberOk
        }
    }
}

/**
 * Renders [rows] under [columns].
 *
 * @param key stable identity per row, for lazy reuse and selection.
 * @param filters when non-null, filterable columns get a funnel writing here.
 * @param selectedKey the [key] of the highlighted row, if any.
 * @param onSelect when non-null, rows are clickable.
 * @param reorderable when true, header cells can be dragged to reorder columns.
 * @param followTail when true, the body sticks to the newest row as rows arrive.
 * @param selectableText when true, row text is selectable/copyable.
 */
@Composable
fun <T, M> DataGrid(
    columns: SnapshotStateList<GridColumn<T>>,
    rows: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    filters: SnapshotStateMap<String, ColumnFilter>? = null,
    /**
     * Rows the filter popups build their tick lists from. Defaults to [rows],
     * but a caller that filters before handing rows over should pass the
     * unfiltered list — otherwise ticking one host removes every other host
     * from the list, leaving no way to tick a second.
     */
    selectedKey: Any? = null,
    onSelect: ((T) -> Unit)? = null,
    /**
     * Rows picked out for an action that needs more than one of them.
     *
     * Kept apart from [selectedKey] rather than replacing it with a set: the
     * selection drives what the rest of the app is showing, and a view that
     * followed two selections at once would have to choose one anyway. Marking
     * is a side note on top of it — Ctrl+click, or the row's own menu.
     */
    markedKeys: Set<Any> = emptySet(),
    onToggleMark: ((T) -> Unit)? = null,
    reorderable: Boolean = false,
    followTail: Boolean = false,
    selectableText: Boolean = false,
    style: GridStyle = GridStyle(),
    markers: GridMarkers<M>? = null,
    /**
     * Right-click actions for a row, if the caller has any. Declared as menu
     * entries rather than a composable so the grid keeps deciding how a menu
     * looks and the caller only says what is in it.
     */
    rowMenu: (MenuScope.(T) -> Unit)? = null,
    /**
     * What to show instead of rows when there are none.
     *
     * A slot rather than a string because only the caller knows *why* the grid
     * is empty — nothing captured yet and everything filtered out look identical
     * from in here, and they need opposite advice.
     */
    empty: (@Composable () -> Unit)? = null,
    /**
     * Columns whose tick list is owned by somebody else.
     *
     * Keyed by column key. When a column appears here its popup reads and writes
     * this instead of its own [ColumnFilter], so a header funnel and whatever
     * else edits the same set stay one state rather than two that agree until
     * you touch either. The grid never applies these itself — the owner already
     * did, which is what makes it the owner.
     */
    boundFacets: GridFacetBinding? = null,
) {
    val listState = rememberLazyListState()
    if (followTail) {
        // Count list items, not rows: markers are items too, so rows.lastIndex
        // would stop short of the end once any are present.
        LaunchedEffect(rows.size, markers) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    Column(modifier) {
        GridHeader(columns, filters, boundFacets, reorderable, style)

        // The scrollbar overlays the rows' right edge, below the header.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val body: @Composable () -> Unit = {
                if (rows.isEmpty() && empty != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { empty() }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    markers?.leading?.forEach { marker ->
                        item(key = markers.key(marker)) { markers.content(marker) }
                    }
                    itemsIndexed(rows, key = { _, it -> key(it) }) { index, row ->
                        val selected = key(row) == selectedKey
                        GridRow(
                            columns, row, selected, onSelect, style,
                            striped = index % 2 == 1,
                            marked = key(row) in markedKeys,
                            onToggleMark = onToggleMark,
                            menu = rowMenu,
                        )
                        // Markers follow the row they were keyed to.
                        markers?.afterKey?.get(key(row))?.forEach { markers.content(it) }
                    }
                    markers?.trailing?.forEach { marker ->
                        item(key = markers.key(marker)) { markers.content(marker) }
                    }
                }
            }
            if (selectableText) SelectionContainer { body() } else body()

            VScrollbar(
                listState,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Header — drag body to reorder, drag right edge to resize, funnel to filter.
// ---------------------------------------------------------------------------

@Composable
private fun <T> GridHeader(
    cols: SnapshotStateList<GridColumn<T>>,
    filters: SnapshotStateMap<String, ColumnFilter>?,
    boundFacets: GridFacetBinding?,
    reorderable: Boolean,
    style: GridStyle,
) {
    val bounds = remember { mutableStateMapOf<String, ClosedFloatingPointRange<Float>>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointerX by remember { mutableStateOf(0f) }
    var openFilter by remember { mutableStateOf<String?>(null) }
    var gridWidthPx by remember { mutableStateOf(0f) }

    Row(
        Modifier.fillMaxWidth().height(style.headerHeight).background(P.head).bottomBorder(P.line)
            .onGloballyPositioned { gridWidthPx = it.size.width.toFloat() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEachIndexed { index, col ->
            Box(
                cellWidth(col)
                    .fillMaxHeight()
                    .background(if (dragging == col.key) P.accentFill else Color.Transparent)
                    .onGloballyPositioned { c ->
                        val x = c.positionInRoot().x
                        bounds[col.key] = x..(x + c.size.width.toFloat())
                    }
                    .then(
                        if (!reorderable) Modifier else Modifier.pointerInput(col.key, cols.size) {
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
                    ),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = style.cellPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CellText(
                        col.label,
                        color = if (columnIsFiltered(col.key, filters, boundFacets)) P.accent else style.labelColor,
                        style = style.labelStyle ?: Typo.label, family = P.Ui,
                        // Fill the row so the funnel is pushed to the right corner.
                        modifier = Modifier.weight(1f),
                    )
                    if (filters != null && col.filterable) {
                        FilterFunnel(col, filters, boundFacets, open = openFilter == col.key,
                            onToggle = { openFilter = if (openFilter == col.key) null else col.key },
                            onDismiss = { openFilter = null })
                    }
                }
                // Resize handle: a fixed column resizes itself, a flexible one
                // trades weight with its neighbour, so the grid keeps its width.
                if (col.fixed || index < cols.lastIndex) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(6.dp).fillMaxHeight()
                            .pointerHoverIcon(ResizeCursor)
                            .pointerInput(col.key) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (col.fixed) {
                                        col.weight = (col.weight + dragAmount.toDp().value)
                                            .coerceAtLeast(MIN_COL_PX)
                                    } else {
                                        resize(cols, col.key, dragAmount, gridWidthPx)
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

/** Moves [dragAmount] px of width from the column after [key] into it (or back). */
private fun <T> resize(
    cols: SnapshotStateList<GridColumn<T>>,
    key: String,
    dragAmount: Float,
    gridWidthPx: Float,
) {
    if (gridWidthPx <= 0f) return
    val i = cols.indexOfFirst { it.key == key }
    val self = cols.getOrNull(i)?.takeIf { !it.fixed } ?: return
    val next = cols.drop(i + 1).firstOrNull { !it.fixed } ?: return
    val total = cols.filter { !it.fixed }.fold(0f) { a, c -> a + c.weight }
    val minW = MIN_COL_PX / gridWidthPx * total
    var d = dragAmount / gridWidthPx * total
    if (self.weight + d < minW) d = minW - self.weight
    if (next.weight - d < minW) d = next.weight - minW
    self.weight += d
    next.weight -= d
}

@Composable
private fun <T> FilterFunnel(
    col: GridColumn<T>,
    filters: SnapshotStateMap<String, ColumnFilter>,
    boundFacets: GridFacetBinding?,
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box {
        // A plain icon button, not a selectable one: a selected state would
        // recolour the glyph and, before §6.4 was applied, ring it as well.
        // Whether the column is filtered is already said by its header label,
        // which turns accent — saying it twice in one 26dp strip is noise.
        IconActionButton(
            key = AllIconsKeys.General.Filter,
            contentDescription = "Filter ${col.label}",
            onClick = onToggle,
            modifier = Modifier.size(16.dp),
        )
        if (open) FilterPopup(col, filters, boundFacets, onDismiss)
    }
}

@Composable
private fun <T> FilterPopup(
    col: GridColumn<T>,
    filters: SnapshotStateMap<String, ColumnFilter>,
    boundFacets: GridFacetBinding?,
    onDismiss: () -> Unit,
) {
    Popup(
        offset = IntOffset(-8, 18),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val filter = filters[col.key] ?: ColumnFilter()
        // Where this column's ticks actually live. A bound column's ticks are
        // somebody else's state; an unbound one's are its own.
        val bound = boundFacets?.takeIf { col.key in it.keys }
        val ticked = bound?.selected(col.key) ?: filter.selected
        // Recomputed only while the popup is open, and keyed on the row count so
        // a live capture keeps the list current without rescanning every frame.
        val facets = col.facets
        val width = when {
            facets.isNotEmpty() -> 232.dp
            col.presets.isNotEmpty() -> 160.dp
            else -> 192.dp
        }

        // A card, not the header strip dropping open. The popup floats over the
        // grid and reads as a thing in front of it, so it gets rounded corners
        // and a clip — without the clip the tick list's own rows would square
        // the bottom two off again.
        Column(
            Modifier.width(width)
                .background(P.chrome, POPUP_SHAPE)
                .border(1.dp, P.line, POPUP_SHAPE)
                .clip(POPUP_SHAPE),
        ) {
            // Which column this is filtering. The header it dropped from says so
            // too, but the popup overlaps that strip and covers its neighbours —
            // and once you have scrolled a list of values, the column you opened
            // is the one thing no longer in front of you. Clear sits up here as
            // an icon, which is what it is: one verb, no label needed.
            Row(
                Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PzText(
                    "Filter for \u2018" + col.label + "\u2019",
                    color = P.dim, style = Typo.label, family = P.Ui, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconActionButton(
                    key = AllIconsKeys.General.Close,
                    contentDescription = "Clear this filter",
                    enabled = filter.isActive || ticked.isNotEmpty(),
                    onClick = {
                        filters.remove(col.key)
                        bound?.clear?.invoke(col.key)
                        onDismiss()
                    },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var focused by remember { mutableStateOf(false) }
                val negated = filter.negated
                // The focus ring, which is the only thing in the popup that says
                // where the keys are going: the tick list below takes clicks but
                // never the caret, so without it a popup with a list in it looks
                // like nothing is ready for typing.
                Row(
                    // Height after the frame, so the border is the field's own
                    // edge rather than a box drawn around a padded one.
                    Modifier.weight(1f).height(24.dp)
                        .background(P.bg, ChipShape)
                        .border(1.dp, if (focused) P.accent else P.line, ChipShape)
                        .padding(start = 8.dp, end = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Inside the field rather than beside it, so it reads as part
                    // of the input instead of a button next to one.
                    Icon(
                        key = AllIconsKeys.Actions.Search,
                        contentDescription = null,
                        tint = if (focused) P.accent else P.faint,
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = filter.text,
                        onValueChange = { filters[col.key] = filter.withText(it) },
                        singleLine = true,
                        textStyle = Typo.h2.copy(color = P.text, fontFamily = P.Mono),
                        cursorBrush = SolidColor(P.accent),
                        modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (filter.text.isEmpty()) {
                                    PzText(
                                        if (facets.isEmpty()) "contains…" else "search…",
                                        color = P.faint, style = Typo.label,
                                    )
                                }
                                inner()
                            }
                        },
                    )

                    // Clears the typed text and nothing else — not the ticks,
                    // not the comparison. The Close button in the header clears
                    // the whole filter and shuts the popup; these two look alike
                    // and must not do alike, so this one only ever empties the
                    // field it sits in.
                    //
                    // Always laid out and only sometimes visible: appearing as
                    // you type the first character would shove the caret
                    // sideways mid-word.
                    IconActionButton(
                        key = AllIconsKeys.General.Close,
                        contentDescription = "Clear the text",
                        enabled = filter.text.isNotEmpty(),
                        onClick = { filters[col.key] = filter.withText("") },
                        modifier = Modifier.size(TRAILING).revealed(filter.text.isNotEmpty()),
                    )

                    // Whether the text includes or excludes, in the field it
                    // governs. It used to sit outside as a bordered box of its
                    // own, which made it look like a second input rather than a
                    // switch on this one. Two states need no list to choose
                    // from — a dropdown here spent ninety pixels and still
                    // truncated its own label to "contai…".
                    Box(
                        Modifier.size(TRAILING)
                            .clip(ChipShape)
                            .background(if (negated) P.accentFill else Color.Transparent)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { filters[col.key] = filter.withNegated(!negated) },
                        contentAlignment = Alignment.Center,
                    ) {
                        PzText(
                            if (negated) "≠" else "=",
                            color = if (negated) P.accent else P.faint,
                            style = Typo.label,
                        )
                    }
                }
            }

            // A numeric column gets a comparison instead of a tick list: "bigger
            // than 100 KB" is not a substring and not a set, and it is the only
            // question anybody asks of a size.
            col.numeric?.let {
                Row(
                    Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        OP_LARGER to "Larger",
                        OP_SMALLER to "Smaller",
                        OP_EQUAL to "Equal",
                    ).forEach { (operator, label) ->
                        val on = filter.op == operator
                        Box(
                            Modifier.background(if (on) P.accentFill else Color.Transparent, ChipShape)
                                .pointerHoverIcon(PointerIcon.Hand)
                                // Clicking the chosen one clears it, so a
                                // comparison can be taken off without also
                                // clearing what was typed beside it.
                                .clickable {
                                    filters[col.key] = filter.withComparison(
                                        if (on) OP_NONE else operator,
                                        filter.operand,
                                    )
                                }
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            CellText(label, color = if (on) P.accent else P.dim, style = Typo.label)
                        }
                        Spacer(Modifier.width(3.dp))
                    }
                    BasicTextField(
                        value = filter.operand,
                        onValueChange = { filters[col.key] = filter.withComparison(filter.op, it) },
                        singleLine = true,
                        textStyle = Typo.h2.copy(color = P.text, fontFamily = P.Mono),
                        cursorBrush = SolidColor(P.accent),
                        modifier = Modifier.weight(1f).height(22.dp)
                            .background(P.bg, ChipShape)
                            .border(1.dp, P.line, ChipShape)
                            .padding(horizontal = 7.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (filter.operand.isEmpty()) {
                                    PzText("64kb", color = P.faint, style = Typo.label)
                                }
                                inner()
                            }
                        },
                    )
                }
            }

            // Tick list: every value present in the data, narrowed by whatever
            // has been typed above, with the ticked ones ORed together.
            if (facets.isNotEmpty()) {
                val shown = facets.filter { filter.text.isBlank() || it.contains(filter.text, ignoreCase = true) }
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxWidth().height(FACET_LIST_HEIGHT)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        if (shown.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp)) {
                                    PzText("no matches", color = P.faint, style = Typo.label)
                                }
                            }
                        }
                        items(shown, key = { it }) { facet ->
                            val on = facet in ticked
                            // One click target over the box and the label, not
                            // a row-level `clickable` with a checkbox sitting on
                            // top of it: a bare CheckBox has no handler, which
                            // makes it *disabled*, and a disabled checkbox eats
                            // the press instead of letting it through. Clicking
                            // the label worked and clicking the box — the one
                            // thing that looks like the control — did nothing.
                            CheckBoxRow(
                                checked = on,
                                modifier = Modifier.fillMaxWidth().height(26.dp)
                                    .background(if (on) P.sel else Color.Transparent)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 10.dp),
                                // Stays open: picking several is the point.
                                onCheckedChange = {
                                    if (bound != null) {
                                        bound.toggle(col.key, facet)
                                    } else {
                                        filters[col.key] = filter.toggle(facet)
                                    }
                                },
                            ) {
                                Spacer(Modifier.width(2.dp))
                                CellText(facet, color = if (on) P.text else P.dim, style = Typo.label)
                            }
                        }
                    }
                    VScrollbar(
                        listState,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }

            col.presets.forEach { preset ->
                val on = filter.text == preset
                Box(
                    Modifier.fillMaxWidth().background(if (on) P.sel else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { filters[col.key] = filter.withText(preset); onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) { PzText(preset, color = if (on) P.accent else P.dim, style = Typo.label) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Data rows
// ---------------------------------------------------------------------------

@Composable
private fun <T> GridRow(
    cols: List<GridColumn<T>>,
    row: T,
    selected: Boolean,
    onSelect: ((T) -> Unit)?,
    style: GridStyle,
    striped: Boolean = false,
    hovered: Boolean = false,
    marked: Boolean = false,
    onToggleMark: ((T) -> Unit)? = null,
    menu: (MenuScope.(T) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Where inside the row the right-click landed, so the menu can open there
    // rather than at the row's leading edge.
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    // Rows carry no rule of their own: separation is zebra striping, and
    // selection is the whole row filled rather than an edge bar beside it
    // (DESIGN.MD §9.3). A per-row border under a 24px row reads as noise once
    // there are hundreds of them.
    val background = when {
        selected -> P.sel
        // Above hover and striping but below selection: a marked row should
        // still look selected when it is, since that is what the panes are
        // showing, and the accent wash reads as "and this one too".
        marked -> P.accentFill
        hovered -> P.rowHover
        striped -> P.rowStripe
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().height(style.rowHeight)
            .background(background)
            .then(if (onSelect == null) Modifier else Modifier.clickable { onSelect(row) })
            .then(
                if (menu == null && onToggleMark == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(row, onToggleMark) {
                        awaitPointerEventScope {
                            while (true) {
                                // The Initial pass, so a Ctrl+click can be
                                // claimed here before the `clickable` above
                                // turns it into an ordinary selection.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Press) continue
                                when {
                                    event.buttons.isSecondaryPressed -> {
                                        // Opened on press rather than release, so
                                        // the menu is up before the button comes
                                        // back — and the row is selected first,
                                        // because most entries act on "the
                                        // selected flow".
                                        menuAt = event.changes.first().position
                                        onSelect?.invoke(row)
                                        menuOpen = true
                                    }

                                    onToggleMark != null && event.keyboardModifiers.isCtrlPressed -> {
                                        onToggleMark(row)
                                        // Consumed, so the row is marked instead
                                        // of also being selected.
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (menu != null && menuOpen) {
            PopupMenu(
                onDismissRequest = { menuOpen = false; true },
                popupPositionProvider = rememberCursorPositionProvider(menuAt),
            ) {
                menu(row)
            }
        }
        cols.forEach { col ->
            Row(
                cellWidth(col).fillMaxHeight().padding(horizontal = style.cellPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (col.end) Arrangement.End else Arrangement.Start,
            ) { col.cell(row) }
        }
    }
}

/** Fixed columns take their dp width; flexible ones share the remainder. */
private fun <T> RowScope.cellWidth(col: GridColumn<T>): Modifier =
    if (col.fixed) Modifier.width(col.weight.dp) else Modifier.weight(col.weight)

/**
 * Opens a popup at [offsetInAnchor] — the point inside the anchor where the
 * pointer was — instead of at one of the anchor's edges.
 *
 * Clamped to the window on both axes, so a right-click near the bottom or the
 * right edge still shows the whole menu rather than half of it.
 */
@Composable
private fun rememberCursorPositionProvider(offsetInAnchor: Offset): PopupPositionProvider =
    remember(offsetInAnchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left + offsetInAnchor.x.roundToInt()
                val y = anchorBounds.top + offsetInAnchor.y.roundToInt()
                return IntOffset(
                    x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }
