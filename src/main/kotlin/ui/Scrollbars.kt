package org.bittrace.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.VerticalScrollbar

/**
 * Themed scrollbars. Compose Desktop draws none by default, so every scrollable
 * surface (the flow table, the inspector panes) pairs its scroll state with one
 * of these. They draw nothing while the content fits, so they cost no space
 * until there is something to scroll.
 *
 * Jewel draws them now; these stay only as the two-argument shorthand the
 * eleven call sites already use. Style — square, hairline-thin, palette-driven —
 * comes from the theme via `ui/JewelBridge.kt`, so there is nothing to pass.
 * Jewel takes the scroll state directly, so callers no longer wrap it in a
 * `ScrollbarAdapter`.
 */

/** Vertical scrollbar; typically aligned to the end edge of a scrolling Box. */
@Composable
fun VScrollbar(state: ScrollableState, modifier: Modifier = Modifier) =
    VerticalScrollbar(state, modifier)

/** Horizontal scrollbar; typically placed under a horizontally scrolling block. */
@Composable
fun HScrollbar(state: ScrollableState, modifier: Modifier = Modifier) =
    HorizontalScrollbar(state, modifier)
