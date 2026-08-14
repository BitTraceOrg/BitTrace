package org.bittrace.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import org.bittrace.plugin.theme.Palette
import org.bittrace.plugin.builtin.precisionDark

/**
 * The active palette, exposed as `P.<token>`. Backed by Compose snapshot state,
 * so [apply] swaps every colour app-wide and every composable reading a token
 * recomposes — no call sites change.
 *
 * The [Palette] type and the concrete palettes now come from theme plugins
 * (see :plugin-api and the bundled PrecisionThemePlugin); the `ThemeManager`
 * calls [apply] with the selected theme's palette. The initial value is the
 * built-in dark palette so the first frame is themed before startup wiring runs.
 */
object P {
    var palette by mutableStateOf(precisionDark)
        private set

    /** Binds the active palette (called by the ThemeManager). */
    fun apply(p: Palette) {
        palette = p
    }

    val bg get() = palette.bg
    val chrome get() = palette.chrome
    val panel get() = palette.panel
    val head get() = palette.head
    val line get() = palette.line
    val line2 get() = palette.line2
    val text get() = palette.text
    val dim get() = palette.dim
    val faint get() = palette.faint
    val accent get() = palette.accent
    val ok get() = palette.ok
    val err get() = palette.err
    val warn get() = palette.warn
    val info get() = palette.info
    val sel get() = palette.sel
    val accentFill get() = palette.accentFill
    val rowHover get() = palette.rowHover
    val waitBar get() = palette.waitBar

    // Fonts are not themed.
    val Mono: FontFamily = FontFamily.Monospace
    val Ui: FontFamily = FontFamily.SansSerif
}
