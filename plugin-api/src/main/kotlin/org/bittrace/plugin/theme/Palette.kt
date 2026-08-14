package org.bittrace.plugin.theme

import androidx.compose.ui.graphics.Color

/**
 * The full set of themeable colours. A [ThemePlugin] produces one of these per
 * theme it contributes; the host binds it to `P.*` so the whole UI recolours.
 *
 * Uses `androidx.compose.ui.graphics.Color` so plugins share the exact type the
 * app renders with — this is the only Compose type in the plugin API.
 */
data class Palette(
    val bg: Color,
    val chrome: Color,
    val panel: Color,
    val head: Color,
    val line: Color,
    val line2: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,
    val ok: Color,
    val err: Color,
    val warn: Color,
    val info: Color,
    val sel: Color,
    val accentFill: Color,
    val rowHover: Color,
    val waitBar: Color,
)
