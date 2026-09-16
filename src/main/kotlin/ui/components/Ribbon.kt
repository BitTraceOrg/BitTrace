package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** One slice of a [Ribbon]: its share of the bar, its colour, and its legend text. */
class RibbonSlice(val weight: Float, val color: Color, val label: String)

/**
 * A proportional bar with a legend under it and a total at the right — how the
 * app shows "this quantity, broken into parts": a flow's timing phases, a
 * transfer's headers against its body.
 *
 * Slices are laid out by weight, so the bar always fills its width and the eye
 * reads where the quantity went rather than comparing several numbers. A slice
 * with no weight keeps its legend entry but draws nothing (Compose has no
 * zero-weight child), and a ribbon whose slices are all empty draws one flat
 * rule instead — an empty bar still has to say "nothing here", not vanish.
 */
@Composable
fun Ribbon(slices: List<RibbonSlice>, total: String, modifier: Modifier = Modifier) {
    val drawn = slices.filter { it.weight > 0f }
    val shares = sharesOf(drawn)
    Column(
        modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(9.dp)) {
            if (drawn.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(P.line2))
            } else {
                drawn.forEachIndexed { i, slice ->
                    Box(Modifier.weight(shares[i]).fillMaxHeight().background(slice.color))
                }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                slices.forEach { RibbonLegend(it.color, it.label) }
            }
            PzText(total, color = P.accent, style = Typo.label)
        }
    }
}

/**
 * The slices as fractions of their sum.
 *
 * A caller measures in whatever unit its quantity comes in, and a ribbon over
 * bytes hands over numbers in the millions. Compose treats a weight as a
 * quantity of pixels per unit while measuring intrinsics — it multiplies the
 * weight by a pixel figure and asks a child to measure at the result — so a
 * weight of twenty million becomes a width of twenty million, which is wider
 * than a `Constraints` can represent, and the layout throws rather than
 * clipping. Dividing through here keeps the sum at 1 whatever the unit, and the
 * proportions are all the bar was ever reading from the numbers.
 *
 * Summed as a `Double` so that a long ribbon of large slices cannot lose the
 * small ones to float rounding before they are divided.
 */
private fun sharesOf(slices: List<RibbonSlice>): List<Float> {
    val total = slices.sumOf { it.weight.toDouble() }
    if (total <= 0.0) return slices.map { 1f / slices.size.coerceAtLeast(1) }
    // A slice too thin to round up to a pixel draws nothing, which is what it
    // should do — but a zero weight is not a weight, so it keeps a token share.
    return slices.map { (it.weight / total).toFloat().coerceAtLeast(Float.MIN_VALUE) }
}

/**
 * A swatch and what it means — under a [Ribbon], and over the waterfall band,
 * which is the same key over a bar drawn on a canvas rather than in layout.
 */
@Composable
fun RibbonLegend(color: Color, text: String, style: TextStyle = Typo.label) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 6)
        Spacer(Modifier.width(4.dp))
        PzText(text, color = P.faint, style = style)
    }
}
