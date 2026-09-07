package org.bittrace.ui.layouts.inspector.components

import org.bittrace.ui.bytesStr
import org.bittrace.ui.durStr
import org.bittrace.ui.endStr
import org.bittrace.ui.hostPath
import org.bittrace.ui.kindOfRow
import org.bittrace.ui.startStr
import org.bittrace.ui.statusOf
import org.bittrace.ui.tlsText
import org.bittrace.ui.components.EmptyState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.bittrace.ui.copyToClipboard
import org.bittrace.plugin.flow.FlowActionContext
import org.bittrace.plugin.flow.FlowActionPlugin
import org.bittrace.plugin.flow.FlowBodySide
import org.bittrace.plugin.flow.FlowHeader
import org.bittrace.plugin.flow.FlowTarget
import org.bittrace.proxy.BodySide
import org.jetbrains.jewel.ui.component.separator
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bittrace.data.ExportMark
import org.bittrace.data.LIVE_SESSION
import org.bittrace.data.HTTP_METHODS
import org.bittrace.data.TrafficRow
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.ColumnFilter
import org.bittrace.ui.components.DataGrid
import org.bittrace.ui.components.GridFacetBinding
import org.bittrace.ui.components.GridColumn
import org.bittrace.ui.components.GridMarkers
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.topBorder

/**
 * A flow-table column — the shared [GridColumn] bound to traffic rows. Columns
 * are laid out by weight so they always fill the table width, are resizable by
 * dragging their edge, and reorderable by dragging the header.
 */
typealias Col = GridColumn<TrafficRow>

// --- filter vocabularies -----------------------------------------------------
//
// Declared here, above everything that builds a column, and that placement is
// load-bearing: `DEFAULT_COLUMN_KEYS` below calls `defaultColumns()` while this
// file is still initialising, so a facet list declared *after* it would still be
// null when a column asked for it — which fails as a null check on a parameter
// that has a default, several frames from the real cause.

/**
 * Status classes, as the Code column filters by.
 *
 * `ERR` is not a class the RFC knows: it is a flow that never got a status at
 * all — a reset, a DNS failure, a timeout — and it is the one people look for
 * most, so it sits with the others rather than in a control of its own.
 */
val STATUS_CLASSES = listOf("1xx", "2xx", "3xx", "4xx", "5xx", "ERR")

/** Which class a row's status falls in — the band's rule, under this list's name. */
fun statusClassOf(row: TrafficRow): String = statusBucket(row, reset = "ERR")

/**
 * The kinds the Type column sorts traffic into.
 *
 * Buckets rather than raw content types: a hundred distinct MIME strings is a
 * list nobody reads, and "is this an image" is the question being asked. Must
 * stay in step with `kindOfRow`, which is what assigns them.
 */
val CONTENT_KINDS = listOf("html", "css", "js", "json", "xml", "img", "font", "media", "text", "bin")

/**
 * Every column the flow table can show, in catalog order — the order the
 * settings list presents them in, and the order enabled ones are built in.
 *
 * Columns are opt-in: [DEFAULT_COLUMN_KEYS] is what a fresh install shows, and
 * the rest stay hidden until switched on in Settings.
 */
fun columnCatalog(): List<Col> = defaultColumns() + optionalColumns()

/** Keys shown when nothing has been chosen. */
val DEFAULT_COLUMN_KEYS: List<String> = defaultColumns().map { it.key }

/**
 * The enabled columns, in catalog order.
 *
 * An empty [enabledKeys] means "defaults"; so does a set that matches no known
 * column, so a stale settings file can never leave the table with no columns.
 */
fun columnsFor(enabledKeys: List<String>): List<Col> {
    val keys = enabledKeys.toSet()
    val chosen = columnCatalog().filter { it.key in keys }
    return chosen.ifEmpty { defaultColumns() }
}

/** The default column set. `#` is not filterable. */
fun defaultColumns(): List<Col> = listOf(
    Col("id", "#", 36f, end = true, filterable = false, value = { it.rowCount.toString() }) {
        CellText(it.rowCount.toString(), P.faint)
    },
    // Ticked by class rather than by code: nobody filters for 418, and a list
    // of every status seen so far cannot offer 5xx until one has arrived —
    // which is exactly when you have stopped needing to ask for it.
    Col(
        "st", "Code", 40f,
        facets = STATUS_CLASSES,
        value = { statusOf(it).first },
        facet = { statusClassOf(it) },
    ) {
        val (t, c) = statusOf(it); CellText(t, c)
    },
    Col(
        "method", "Method", 60f,
        facets = HTTP_METHODS,
        value = { it.request.request.method },
        facet = { it.request.request.method.uppercase() },
    ) { CellText(it.request.request.method, P.info) },
    // Typed text only. A host list here would be as long as the capture is
    // varied, and the overview band's Host column already offers that set with
    // cross-filtered counts — which is the version worth having.
    Col(
        "url", "Host / path", 380f,
        value = { it.request.request.url },
    ) {
        val url = it.request.request.url
        val scheme = url.substringBefore("://", "")
        val (host, path) = hostPath(url)
        Row {
            if (scheme.isNotEmpty()) {
                CellText(scheme, if (scheme == "https") P.ok else P.warn)
                CellText("://", P.faint)
            }
            // Marked, not just filtered: on a long path the point of a text
            // search is which part of it matched.
            CellText(highlighted(host, P.faint))
            CellText(highlighted(path, P.text))
        }
    },
    Col(
        "type", "Type", 50f,
        facets = CONTENT_KINDS,
        value = { kindOfRow(it) },
        facet = { kindOfRow(it) },
    ) { CellText(kindOfRow(it), P.dim) },
    Col("tls", "TLS", 56f, presets = listOf("1.3", "1.2", "none"), value = { tlsText(it) }) {
        val t = tlsText(it); CellText(t, if (t == "—") P.faint else P.ok)
    },
    Col(
        "size", "Size", 64f, end = true,
        value = { bytesStr(it.response?.response?.bodySize) },
        // Compared rather than matched: a size is the one column where the
        // question is always a threshold.
        numeric = { it.response?.response?.bodySize?.takeIf { size -> size >= 0 } },
    ) {
        val s = it.response?.response?.bodySize
        CellText(bytesStr(s), if (s == null || s < 0) P.err else P.text)
    },
    Col("time", "Time", 60f, end = true, value = { durStr(it) }) {
        CellText(durStr(it), if (it.response?.error == true) P.err else P.text)
    },
    Col("start", "Start", 72f, value = { startStr(it) }) { CellText(startStr(it), P.dim) },
    Col("end", "End", 72f, value = { endStr(it) }) { CellText(endStr(it), P.dim) },
)

/**
 * Columns that are off until switched on: the four halves of the transfer,
 * broken out for anyone sizing up header overhead against payload.
 */
fun optionalColumns(): List<Col> = listOf(
    Col("reqHeaderSize", "Req hdr", 64f, end = true,
        value = { bytesStr(it.request.request.headersSize) }) {
        CellText(bytesStr(it.request.request.headersSize), P.dim)
    },
    Col("reqBodySize", "Req body", 64f, end = true,
        value = { bytesStr(it.request.request.bodySize) }) {
        CellText(bytesStr(it.request.request.bodySize), P.dim)
    },
    Col("resHeaderSize", "Res hdr", 64f, end = true,
        value = { bytesStr(it.response?.response?.headersSize) }) {
        CellText(bytesStr(it.response?.response?.headersSize), P.dim)
    },
    // Same figure the default SIZE column shows, named for symmetry with the
    // other three so the four read as one group.
    Col("resBodySize", "Res body", 64f, end = true,
        value = { bytesStr(it.response?.response?.bodySize) }) {
        CellText(bytesStr(it.response?.response?.bodySize), P.dim)
    },
)

/**
 * The status bar's outcome filter, driven by clicking its OK / FAILED counts.
 *
 * Separate from the column filters because "failed" is not a substring of any
 * one column: a flow fails either by status code or by a reset connection, and
 * the CODE column shows those as `500` and `ERR` respectively.
 */
enum class Outcome {
    ALL,
    OK,
    FAILED,
    ;

    /**
     * A flow still awaiting its response is neither ok nor failed, so it shows
     * only under [ALL] — matching how the status bar counts them.
     */
    fun matches(row: TrafficRow): Boolean {
        if (this == ALL) return true
        val failed = row.failed ?: return false
        return if (this == FAILED) failed else !failed
    }
}

/** Applies the outcome filter; [Outcome.ALL] passes the list straight through. */
fun applyOutcome(rows: List<TrafficRow>, outcome: Outcome): List<TrafficRow> =
    if (outcome == Outcome.ALL) rows else rows.filter { outcome.matches(it) }

/** One full-width line in the flow list marking a session boundary. */
class SessionBanner(val id: String, val label: String)

/**
 * Derives the session banners for [visible].
 *
 * Boundaries come from runs of [TrafficRow.sessionId], not from stored
 * positions — so a banner cannot drift when rows are filtered out, evicted, or
 * when the row counter restarts, and a session with nothing visible produces no
 * orphaned START/END pair. Export notes are positional by nature and attach to
 * the last visible row at or before their anchor.
 */
fun sessionBanners(
    visible: List<TrafficRow>,
    sessionName: (Int) -> String?,
    exportMarks: List<ExportMark>,
): GridMarkers<SessionBanner> {
    val leading = mutableListOf<SessionBanner>()
    val trailing = mutableListOf<SessionBanner>()
    val after = HashMap<Any, MutableList<SessionBanner>>()

    fun emit(previous: TrafficRow?, banner: SessionBanner) {
        if (previous == null) leading.add(banner)
        else after.getOrPut(previous.id) { mutableListOf() }.add(banner)
    }

    fun label(id: Int, edge: String) = "SESSION ${sessionName(id) ?: id} $edge"

    var current = LIVE_SESSION
    var previous: TrafficRow? = null
    for (row in visible) {
        if (row.sessionId != current) {
            if (current != LIVE_SESSION) {
                emit(previous, SessionBanner("s$current-end", label(current, "End")))
            }
            if (row.sessionId != LIVE_SESSION) {
                emit(previous, SessionBanner("s${row.sessionId}-start", label(row.sessionId, "Start")))
            }
            current = row.sessionId
        }
        previous = row
    }
    if (current != LIVE_SESSION) {
        trailing.add(SessionBanner("s$current-end", label(current, "End")))
    }

    for (mark in exportMarks) {
        val anchor = visible.lastOrNull { it.rowCount <= mark.afterRowCount }
        val banner = SessionBanner("export-${mark.id}", "SESSION ${mark.label} END")
        if (anchor == null || anchor === visible.lastOrNull()) trailing.add(banner)
        else after.getOrPut(anchor.id) { mutableListOf() }.add(banner)
    }

    return GridMarkers(
        leading = leading,
        trailing = trailing,
        afterKey = after,
        key = { it.id },
        content = { BannerRow(it) },
    )
}

/** A session boundary: full width, label centred, clearly not a flow. */
@Composable
private fun BannerRow(banner: SessionBanner) {
    Row(
        Modifier.fillMaxWidth().height(20.dp).background(P.head)
            .topBorder(P.accent).bottomBorder(P.accent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PzText(banner.label, color = P.accent, style = Typo.caption, family = P.Ui, softWrap = false)
    }
}

@Composable
fun FlowTable(
    cols: SnapshotStateList<Col>,
    filters: SnapshotStateMap<String, ColumnFilter>,
    rows: List<TrafficRow>,
    /** Unfiltered rows, so the empty state can tell "nothing captured" from "nothing matched". */
    allRows: List<TrafficRow>,
    selectedId: String?,
    banners: GridMarkers<SessionBanner>?,
    /** Reads a captured body, for the clipboard actions. */
    bodyProvider: (String, BodySide) -> ByteArray?,
    /** Plugins contributing items to a row's context menu. */
    flowActions: List<FlowActionPlugin>,
    /** Flows picked out for a two-row action; see the grid's own `markedKeys`. */
    marked: Set<String>,
    onToggleMark: (TrafficRow) -> Unit,
    /** Opens the diff tool on the two marked flows. Null until there are two. */
    onDiffMarked: (() -> Unit)?,
    onNotice: (String) -> Unit,
    onSelect: (String) -> Unit,
    /** The overview band's facets, which three of these columns edit in place. */
    boundFacets: GridFacetBinding,
) {
    DataGrid(
        columns = cols,
        rows = rows,
        key = { it.id },
        modifier = Modifier.fillMaxSize().background(P.panel),
        filters = filters,
        selectedKey = selectedId,
        onSelect = { onSelect(it.id) },
        reorderable = true,
        markers = banners,
        boundFacets = boundFacets,
        markedKeys = marked,
        onToggleMark = onToggleMark,
        empty = {
            // Nothing captured and everything filtered out look the same from
            // inside the grid and need opposite advice, so the message says
            // which of the two this is.
            EmptyState(
                if (allRows.isEmpty()) {
                    "No traffic captured yet"
                } else {
                    "No flows match — press ESC to clear the query"
                },
                centred = true,
            )
        },
        rowMenu = { row ->
            // Every entry answers "give me this flow somewhere else" — so they
            // all copy, and the one that fails says so rather than silently
            // leaving the clipboard as it was.
            selectableItem(
                selected = false,
                iconKey = AllIconsKeys.Actions.Copy,
                onClick = { copyOrReport(row.request.request.url, "URL", onNotice) },
            ) { CellText("Copy URL", P.text) }
            selectableItem(
                selected = false,
                // A command you run, rather than a payload you keep.
                iconKey = AllIconsKeys.Actions.Execute,
                onClick = { copyOrReport(curlOf(row, bodyProvider), "cURL command", onNotice) },
            ) { CellText("Copy as cURL", P.text) }
            selectableItem(
                selected = false,
                iconKey = AllIconsKeys.General.Export,
                onClick = { copyOrReport(harOf(row, bodyProvider), "HAR", onNotice) },
            ) { CellText("Copy as HAR", P.text) }

            separator()
            // Marking is offered here as well as on Ctrl+click, because a
            // shortcut you have to already know about is not an affordance. One
            // entry covers both directions, since the row's own state is what
            // the label reads from.
            val isMarked = row.id in marked
            selectableItem(
                selected = false,
                iconKey = if (isMarked) AllIconsKeys.Actions.Cancel else AllIconsKeys.General.Add,
                onClick = { onToggleMark(row) },
            ) { CellText(if (isMarked) "Unmark for diff" else "Mark for diff", P.text) }
            selectableItem(
                selected = false,
                iconKey = AllIconsKeys.Actions.Diff,
                // Exactly two: a diff of one has nothing to compare against, and
                // a diff of three has no third column to put the third in.
                enabled = onDiffMarked != null,
                onClick = { onDiffMarked?.invoke() },
            ) {
                CellText(
                    if (onDiffMarked != null) "Diff the 2 marked flows" else "Diff — mark 2 flows first",
                    if (onDiffMarked != null) P.text else P.faint,
                )
            }

            // Plugin items, below the app's own. Built on open rather than per
            // recomposition: a shut menu costs nothing, and an item's
            // enablement is then read at the moment it is shown.
            val extras = flowActions
                .flatMap { plugin -> plugin.actionsFor(targetOf(row, bodyProvider)) }
                // Stable across plugins: a plugin orders its own items with
                // `order`, and equal orders keep load order, so installing one
                // cannot reshuffle another's.
                .sortedBy { it.order }
            if (extras.isNotEmpty()) {
                separator()
                extras.forEach { action ->
                    selectableItem(
                        selected = false,
                        enabled = action.enabled,
                        onClick = { action.perform(FlowActionContext { onNotice(it) }) },
                    ) { CellText(action.label, if (action.enabled) P.text else P.faint) }
                }
            }
        },
    )
}

/**
 * The grid's own row, as the plugin API describes it.
 *
 * Headers come off the *complete* message, which arrives after the row does, so
 * a flow still in flight yields empty lists rather than a partial set — an
 * action reading them gets nothing rather than something misleading.
 */
private fun targetOf(row: TrafficRow, bodyProvider: (String, BodySide) -> ByteArray?): FlowTarget =
    FlowTarget(
        id = row.id,
        method = row.request.request.method,
        url = row.request.request.url,
        status = row.response?.response?.status,
        startedDateTime = row.request.startedDateTime,
        requestHeaders = row.completeRequest?.request?.headers.orEmpty().map { FlowHeader(it.name, it.value) },
        responseHeaders = row.completeResponse?.response?.headers.orEmpty().map { FlowHeader(it.name, it.value) },
        body = { side ->
            bodyProvider(
                row.id,
                when (side) {
                    FlowBodySide.REQUEST -> BodySide.REQUEST
                    FlowBodySide.RESPONSE -> BodySide.RESPONSE
                },
            )
        },
    )

/** Copies [text], and reports the one case worth telling anyone about. */
private fun copyOrReport(text: String, what: String, onNotice: (String) -> Unit) {
    if (!copyToClipboard(text)) onNotice("Could not copy the $what to the clipboard.")
}
