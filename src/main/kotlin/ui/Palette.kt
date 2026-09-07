package org.bittrace.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import org.bittrace.plugin.builtin.precisionDark
import org.bittrace.plugin.theme.Palette
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * The active palette, exposed as `P.<token>`. Backed by Compose snapshot state,
 * so [apply] swaps every colour app-wide and every composable reading a token
 * recomposes — no call sites change.
 *
 * The [Palette] type and the concrete palettes come from theme plugins (see
 * :plugin-api and the bundled PrecisionThemePlugin); the `ThemeManager` calls
 * [apply] with the selected theme's palette. The initial value is the built-in
 * dark palette so the first frame is themed before startup wiring runs.
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
    val input get() = palette.input
    val line get() = palette.line
    val line2 get() = palette.line2
    val text get() = palette.text
    val dim get() = palette.dim
    val faint get() = palette.faint
    val hover get() = palette.hover
    val pressed get() = palette.pressed
    val stripeOn get() = palette.stripeOn
    val outline get() = palette.outline
    val accent get() = palette.accent
    val ok get() = palette.ok
    val err get() = palette.err
    val warn get() = palette.warn
    val info get() = palette.info
    val send get() = palette.send
    val key get() = palette.key
    val sel get() = palette.sel
    val accentFill get() = palette.accentFill
    val rowHover get() = palette.rowHover
    val rowStripe get() = palette.rowStripe
    val waitBar get() = palette.waitBar

    /**
     * The two families, from Jewel.
     *
     * Exactly Inter for chrome and JetBrains Mono for payload, and Jewel ships
     * both — so these read off the theme's own styles
     * rather than falling back to whatever the platform calls sans and mono.
     * They are composable getters for that reason: the theme is the source.
     */
    val Ui: FontFamily
        @Composable get() = JewelTheme.defaultTextStyle.fontFamily ?: FontFamily.SansSerif

    val Mono: FontFamily
        @Composable get() = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Monospace
}
