package org.bittrace.ui.components

import org.bittrace.ui.P
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/** The resize cursors the two bars adopt on hover. */
private val vResize = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
private val hResize = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * A 5px horizontal bar dragged vertically to resize the region below it.
 * [onDrag] receives the drag delta in Dp (positive = downward).
 */
@Composable
fun HorizontalSplitter(onDrag: (Dp) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(4.dp).background(P.line)
            .pointerHoverIcon(vResize)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount.toDp())
                }
            },
    )
}

/**
 * A 5px vertical bar dragged horizontally to resize the panes beside it.
 * [onDrag] receives the drag delta in Dp (positive = rightward), matching
 * [HorizontalSplitter] — the two used to disagree, and every caller of this one
 * had to convert the raw pixels back itself.
 */
@Composable
fun VerticalSplitter(onDrag: (Dp) -> Unit) {
    Box(
        Modifier.width(4.dp).fillMaxHeight().background(P.line)
            .pointerHoverIcon(hResize)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount.toDp())
                }
            },
    )
}
