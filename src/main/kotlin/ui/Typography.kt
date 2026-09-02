package org.bittrace.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * The type scale (DESIGN.MD §3.1).
 *
 * Seven steps, and the sizes are the spec's rather than a ratio: a design
 * language that names 11.5 for a segmented item and 12.5 for a table cell is
 * making a decision, and deriving those from a base would only approximate it.
 * What the scale buys is that there are seven of them and not the nine ad-hoc
 * point sizes the app used to carry.
 *
 * The family is chosen separately, at the call site — Inter for chrome, mono for
 * anything the proxy captured, and the two never swap roles.
 */
object Typo {
    /** Home KPI values. The only step allowed to shout. */
    val display: TextStyle
        @Composable get() = at(24.sp, FontWeight.SemiBold)

    /** Buttons, toolbar labels, search fields, quick-action titles. */
    val h2: TextStyle
        @Composable get() = at(13.sp)

    /** The payload default: table cells, bodies, URLs, method chips. */
    val body: TextStyle
        @Composable get() = at(12.5.sp)

    /** Column headers, status-bar widgets, detail keys, phase names. */
    val label: TextStyle
        @Composable get() = at(12.sp)

    /** Segmented items, filter chips, pane meta, response summaries. */
    val caption: TextStyle
        @Composable get() = at(11.5.sp)

    /** Waterfall ticks, item counts, micro annotations. */
    val micro: TextStyle
        @Composable get() = at(10.sp)

    /**
     * The base style at [size].
     *
     * Line height is cleared rather than carried over: the base style's is sized
     * for the base, and keeping it on a smaller step pads rows out with space
     * they did not ask for. Unspecified lets Compose take it from the font.
     */
    @Composable
    private fun at(size: TextUnit, weight: FontWeight? = null): TextStyle {
        val base = JewelTheme.defaultTextStyle
        return base.copy(
            fontSize = size,
            lineHeight = TextUnit.Unspecified,
            fontWeight = weight ?: base.fontWeight,
        )
    }
}
