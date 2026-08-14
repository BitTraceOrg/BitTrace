package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

private val gripColor = Color(0xFF3A4046)
private val vResize = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
private val hResize = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * A 5px horizontal bar dragged vertically to resize the region below it.
 * [onDrag] receives the drag delta in Dp (positive = downward).
 */
@Composable
fun HorizontalSplitter(onDrag: (Dp) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(5.dp).background(P.chrome).topBorder(P.line).bottomBorder(P.line)
            .pointerHoverIcon(vResize)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount.toDp())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(30.dp).height(1.dp).background(gripColor))
    }
}

/**
 * A 5px vertical bar dragged horizontally to resize the panes beside it.
 * [onDrag] receives the raw pixel delta (positive = rightward); the caller
 * converts it against the container width.
 */
@Composable
fun VerticalSplitter(onDrag: (Float) -> Unit) {
    Box(
        Modifier.width(5.dp).fillMaxHeight().background(P.chrome).leftBorder(P.line).rightBorder(P.line)
            .pointerHoverIcon(hResize)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).height(28.dp).background(gripColor))
    }
}
