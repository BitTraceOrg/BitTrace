package org.bittrace.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shows a control only while [visible], without taking it out of the layout.
 *
 * Adding a row's action button on hover makes the row change size as the
 * pointer crosses it, so every row under it jumps; keeping the same node laid
 * out and merely hiding it means the two heights cannot disagree. Pair it with
 * the control's own `enabled`, because alpha alone leaves an invisible button
 * that still answers a click.
 */
fun Modifier.revealed(visible: Boolean): Modifier = alpha(if (visible) 1f else 0f)

/**
 * The app's one corner radius, for anything that reads as a chip.
 *
 * Three files had each declared a private `RoundedCornerShape(4.dp)` of their
 * own — the address field, the grid's comparison chips, the band's tokens — and
 * the third one's comment already admitted it was copying the first ("the
 * address field's radius, not a new one"). A radius written down three times is
 * a radius that becomes three radii. Two more chips, the importer pill and the
 * dashboard's port box, had no radius at all; they do now.
 *
 * Enough to read as a removable token rather than a table cell, and short of the
 * pill an 18dp-tall chip would become at half its height.
 */
val ChipShape = RoundedCornerShape(4.dp)

/** A full 1px hairline border. */
fun Modifier.border1(color: Color, width: Dp = 1.dp, shape: Shape = RectangleShape): Modifier =
    border(width, color, shape)

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
