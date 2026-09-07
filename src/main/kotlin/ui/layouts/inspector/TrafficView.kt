package org.bittrace.ui.layouts.inspector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bittrace.ui.layouts.inspector.components.FlowTable
import org.bittrace.ui.layouts.inspector.components.OverviewBand
import org.bittrace.ui.components.GridFacetBinding
import org.bittrace.ui.layouts.inspector.components.FACET_COLUMNS
import org.bittrace.ui.layouts.inspector.components.FlowQuery
import org.bittrace.ui.layouts.inspector.components.matches
import org.bittrace.ui.layouts.inspector.components.originOf
import org.bittrace.ui.layouts.inspector.components.Outcome
import org.bittrace.ui.layouts.inspector.components.applyOutcome
import org.bittrace.ui.layouts.inspector.components.columnsFor
import org.bittrace.ui.layouts.inspector.components.sessionBanners
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficRow
import org.bittrace.data.SettingsStore
import org.bittrace.data.horizontalLayout
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.flow.FlowActionPlugin
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.components.ColumnFilter
import org.bittrace.ui.components.SplitPane
import org.bittrace.ui.components.applyGridFilters

/**
 * The traffic screen: the overview band, the flow table, and the inspector on
 * whichever edge the setting puts it.
 *
 * It lived as a private composable inside `Main` while it was the only screen
 * with no file of its own — Home, the Forge and Settings all had one. Every
 * layout has one now, which is the point of the folder it sits in: the entry
 * composable at the top, the parts only this screen uses in `components/`.
 *
 * Everything it needs arrives as a parameter. The query in particular is
 * hoisted rather than held here, because the status bar reports on the same one.
 */
@Composable
fun TrafficView(
    modifier: Modifier,
    store: SessionStore,
    service: ProxyService,
    settings: SettingsStore,
    formatters: List<BodyFormatter>,
    flowActions: List<FlowActionPlugin>,
    selectedId: String?,
    outcome: Outcome,
    /** The overview band's query, hoisted so the status bar reads the same one. */
    query: FlowQuery,
    /** Ids whose body carries the query's text, or null while the scan is pending. */
    bodyHits: Set<String>?,
    searching: Boolean,
    onQuery: (FlowQuery) -> Unit,
    onSearching: (Boolean) -> Unit,
    marked: Set<String>,
    onToggleMark: (TrafficRow) -> Unit,
    onDiffMarked: (() -> Unit)?,
    onNotice: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val rows = store.rows
    // Column order (drag-reorderable) and per-column filters live here so the
    // FLOWS count reflects filtering and the order survives recomposition.
    // Enabled columns are resolved once per view build.
    val cols = remember { mutableStateListOf(*columnsFor(settings.settings.tableColumns).toTypedArray()) }
    val filters = remember { mutableStateMapOf<String, ColumnFilter>() }
    val visible = applyOutcome(applyGridFilters(rows, cols, filters), outcome)
    // Session boundaries are derived from the visible rows, so filtering can
    // never leave a banner stranded.
    val banners = sessionBanners(visible, store::sessionName, store.exportMarks)

    // Inspector size is persisted per dock, so switching docks restores the
    // size that dock last had rather than reusing the other one's.
    val inspectorHeight = settings.settings.inspectorHeightDp.dp
    val inspectorWidth = settings.settings.inspectorWidthDp.dp
    // Read on every composition, not remembered: the View menu toggles this and
    // the change should land immediately.
    val horizontal = settings.settings.horizontalLayout
    val selectedRow = selectedId?.let { store.get(it) }

    val origin = remember(rows.size) { originOf(rows) }

    val shown = visible.filter { query.matches(it, origin, bodyHits) }

    // Hoisted rather than written into each dock branch: the two used to carry
    // the same eight arguments, and only one of the copies would get updated.
    // The seam between the two filter surfaces. The band's query is the state;
    // the three header funnels that offer the same ticks edit it directly, so
    // there is nothing to keep in sync and nothing that can drift.
    val boundFacets = GridFacetBinding(
        keys = FACET_COLUMNS.keys,
        selected = { key -> FACET_COLUMNS[key]?.let { query.facets[it].orEmpty() } ?: emptySet() },
        toggle = { key, value -> FACET_COLUMNS[key]?.let { onQuery(query.toggle(it, value)) } },
        clear = { key -> FACET_COLUMNS[key]?.let { onQuery(query.clearGroup(it)) } },
    )

    val table: @Composable (Modifier) -> Unit = { paneModifier ->
        Box(paneModifier) {
            FlowTable(
                cols, filters, shown, rows, selectedId, banners,
                bodyProvider = service::body, flowActions = flowActions,
                marked = marked, onToggleMark = onToggleMark, onDiffMarked = onDiffMarked,
                onNotice = onNotice, onSelect = onSelect,
                boundFacets = boundFacets,
            )
        }
    }

    Column(
        // `onKeyEvent`, not `onPreviewKeyEvent`. A preview travels root-down and
        // fires before the focused node, so previewing here claimed the key from
        // whatever had focus — and the inspector's body view has its own Ctrl+F,
        // which stopped working the moment this was added. Bubbling is the right
        // semantic for a view-level shortcut: the focused thing gets first
        // refusal, and this only sees what nothing else wanted. It is also what
        // lets `/` be the open key at all — a bare letter can only be a shortcut
        // where no text field would have taken it.
        modifier.fillMaxHeight().onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when {
                (event.key == Key.Slash || (event.isCtrlPressed && event.key == Key.F)) && !searching -> {
                    onSearching(true)
                    true
                }
                event.key == Key.Escape && searching -> {
                    onSearching(false)
                    true
                }
                else -> false
            }
        },
    ) {
        OverviewBand(
            rows = visible,
            shown = shown,
            query = query,
            origin = origin,
            searching = searching,
            selectedId = selectedId,
            onQuery = onQuery,
            onSearching = onSearching,
            onSelect = onSelect,
        )

        SplitPane(
            horizontal = horizontal,
            secondSize = if (horizontal) inspectorWidth else inspectorHeight,
            onResize = { delta ->
                settings.update {
                    if (horizontal) {
                        it.copy(inspectorWidthDp = (it.inspectorWidthDp + delta.value).coerceIn(280f, 1200f))
                    } else {
                        it.copy(inspectorHeightDp = (it.inspectorHeightDp + delta.value).coerceIn(120f, 640f))
                    }
                }
            },
            second = selectedRow?.let { row ->
                { paneModifier: Modifier ->
                    Box(paneModifier) {
                        Inspector(row, service::body, settings, formatters, stacked = horizontal)
                    }
                }
            },
            first = table,
        )
    }
}
