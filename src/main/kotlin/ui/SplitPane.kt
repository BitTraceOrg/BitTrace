package org.bittrace.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Two panes and the divider between them, laid out along whichever axis the
 * dock setting picked: [horizontal] sits them side by side, otherwise they
 * stack. The traffic view and the API client make the same choice about the
 * same setting, and had each written this block out.
 *
 * [first] takes whatever is left over; [second] is the pane whose size is
 * remembered, and is given [secondSize] along the split axis. A null [second]
 * draws neither the pane nor the divider — a grip that resizes nothing is a
 * control that lies — and [first] takes the whole area.
 *
 * [onResize] receives the delta already signed for [second]: dragging the
 * divider away from [second] makes it larger, whichever axis is in play. The
 * caller still owns the clamp and which setting the new size is written to,
 * since those differ per axis.
 *
 * A `ColumnScope` extension because in the stacked case there is no wrapper to
 * add — the panes are children of the column the caller is already building.
 */
@Composable
fun ColumnScope.SplitPane(
    horizontal: Boolean,
    secondSize: Dp,
    onResize: (Dp) -> Unit,
    second: (@Composable (Modifier) -> Unit)?,
    first: @Composable (Modifier) -> Unit,
) {
    if (horizontal) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            first(Modifier.weight(1f).fillMaxHeight())
            if (second != null) {
                VerticalSplitter { delta -> onResize(-delta) }
                second(Modifier.width(secondSize).fillMaxHeight())
            }
        }
    } else {
        first(Modifier.weight(1f).fillMaxWidth())
        if (second != null) {
            HorizontalSplitter { delta -> onResize(-delta) }
            second(Modifier.fillMaxWidth().height(secondSize))
        }
    }
}
