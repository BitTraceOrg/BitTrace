package org.bittrace.ui

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A full 1px hairline border. */
fun Modifier.border1(color: Color, width: Dp = 1.dp): Modifier = border(width, color)

/** Single-side hairline borders — the Precision UI is built from 1px lines. */

fun Modifier.bottomBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

fun Modifier.topBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
}

fun Modifier.rightBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawLine(color, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
}

fun Modifier.leftBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawLine(color, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
}
