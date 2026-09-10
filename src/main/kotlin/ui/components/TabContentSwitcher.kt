package org.bittrace.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.TabContentScope
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabState
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * One tab's label, plus optional muted metadata beside it (a step count, a problem count).
 */
data class TabLabel(
    val label: String,
    val meta: String = "",
)

/**
 * A [TabLabel] and the page it shows — one tab of the paged form.
 */
data class TabContent(
    val label: String,
    val meta: String = "",
    val page: @Composable () -> Unit,
)

/**
 * The single tabbed-panel component for the whole app — a Jewel [TabStrip] in a
 * [PaneHeader] over the tab's content, so every tab strip shares one look, one
 * selection model, and Jewel's arrow-key navigation rather than each screen rolling
 * its own row of clickable labels.
 *
 * This is the paged form: each tab owns a page, and the pager switches between them.
 * Use it when the pages are independent. When the tabs are views over one piece of
 * content that must not be rebuilt on every switch — the inspector's body view — use
 * the [TabLabel] overload, which draws the same strip over a body the caller keeps.
 *
 * The strip and its seam come from [PaneHeader], so the tabs sit on the same header
 * surface as every other pane title in the app, with [title] and [trailing] for the
 * pane's name and its right-aligned metadata. Any seam with whatever sits above or
 * below the component is the parent's business, so callers add their own.
 *
 * [modifier] must carry a height — `Modifier.weight(1f)` in a column, or
 * `fillMaxHeight()` — because the content takes whatever the strip leaves.
 */
@Composable
fun TabContentSwitcher(
    tabs: List<TabContent>,
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Column(modifier) {
        TabHeader(
            tabs = tabs.map { TabLabel(it.label, it.meta) },
            selectedIndex = pagerState.currentPage,
            title = title,
            trailing = trailing,
            onSelect = { index -> coroutineScope.launch { pagerState.animateScrollToPage(index) } },
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            tabs[page].page()
        }
    }
}

/**
 * The same strip, over a [body] the caller draws itself.
 *
 * For tabs that are views over one thing rather than independent pages: the body stays
 * one composition across the whole strip, so switching tabs changes what it shows
 * without tearing down and rebuilding it. The inspector's Body/Raw/Hex tabs share a
 * code view this way — a page each would give each tab an editor of its own.
 *
 * [modifier] must carry a height, as in the paged form.
 */
@Composable
fun TabContentSwitcher(
    tabs: List<TabLabel>,
    selected: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onSelect: (String) -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        TabHeader(
            tabs = tabs,
            selectedIndex = tabs.indexOfFirst { it.label == selected },
            title = title,
            trailing = trailing,
            onSelect = { index -> onSelect(tabs[index].label) },
        )
        body()
    }
}

//region Private components ────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The header both forms share: the pane's name, the strip, and the pane's metadata.
 *
 * The strip takes the weight so it scrolls rather than clipping when the pane is
 * dragged narrow, which keeps [trailing] pinned to the right edge.
 */
@Composable
private fun TabHeader(
    tabs: List<TabLabel>,
    selectedIndex: Int,
    title: String?,
    trailing: @Composable (RowScope.() -> Unit)?,
    onSelect: (Int) -> Unit,
) {
    PaneHeader(title = title) {
        TabStrip(
            tabs = tabs.mapIndexed { index, tab ->
                TabData.Default(
                    selected = index == selectedIndex,
                    // These are views of one thing, not documents — nothing to close.
                    closable = false,
                    onClick = { onSelect(index) },
                    content = tabContent(tab.label, tab.meta),
                )
            },
            // The theme's own tab style, so the strip follows the active palette and the
            // app's compact tab metrics instead of pinning itself to sizes and a dark
            // scheme of its own.
            style = JewelTheme.defaultTabStyle,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

/**
 * Standard tab label: the name, plus optional muted monospace metadata after it (a step
 * count, a problem count). Dims with the tab's own state via [TabContentScope.tabContentAlpha].
 */
private fun tabContent(
    label: String,
    meta: String,
): @Composable TabContentScope.(TabState) -> Unit = { state ->
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.tabContentAlpha(state),
    ) {
        PzText(label, color = P.text, style = Typo.label, family = P.Ui, softWrap = false, maxLines = 1)
        if (meta.isNotEmpty()) {
            PzText(meta, color = P.dim, style = Typo.caption, family = P.Mono, softWrap = false, maxLines = 1)
        }
    }
}

//endregion
