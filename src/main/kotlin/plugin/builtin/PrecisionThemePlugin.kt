package org.bittrace.plugin.builtin

import androidx.compose.ui.graphics.Color
import org.bittrace.plugin.theme.Palette
import org.bittrace.plugin.theme.ThemePlugin
import org.bittrace.plugin.theme.ThemeSpec

/** The original "Precision" dark palette (design/pzCore.jsx `P`). */
val precisionDark = Palette(
    bg = Color(0xFF0A0B0C),
    chrome = Color(0xFF101214),
    panel = Color(0xFF0D0F11),
    head = Color(0xFF15181B),
    line = Color(0xFF22262A),
    line2 = Color(0xFF1A1E21),
    text = Color(0xFFDFE3E6),
    dim = Color(0xFF8D959B),
    faint = Color(0xFF5C646A),
    accent = Color(0xFF3B9DFF),
    ok = Color(0xFF57C98B),
    err = Color(0xFFEF5F5F),
    warn = Color(0xFFE0A83C),
    info = Color(0xFF5AAEF0),
    sel = Color(0xFF152230),
    accentFill = Color(0x1F3B9DFF),
    rowHover = Color(0xFF14181C),
    waitBar = Color(0xFF2B6D9E),
)

/** A light counterpart, keeping the same roles (surfaces light, text dark). */
val precisionLight = Palette(
    bg = Color(0xFFEEF0F2),
    chrome = Color(0xFFE8EAED),
    panel = Color(0xFFFFFFFF),
    head = Color(0xFFF1F3F5),
    line = Color(0xFFD0D5DA),
    line2 = Color(0xFFE3E7EB),
    text = Color(0xFF1A1E21),
    dim = Color(0xFF5C646A),
    faint = Color(0xFF9AA1A7),
    accent = Color(0xFF2680E0),
    ok = Color(0xFF2E9E63),
    err = Color(0xFFD64545),
    warn = Color(0xFFB9821F),
    info = Color(0xFF2F87D8),
    sel = Color(0xFFDCEBFB),
    accentFill = Color(0x1F2680E0),
    rowHover = Color(0xFFF0F3F6),
    waitBar = Color(0xFF7FB0D8),
)

/** Bundled theme plugin — the app's built-in Dark/Light themes. */
class PrecisionThemePlugin : ThemePlugin {
    override val id = "bittrace.precision"
    override val name = "Precision"

    override fun themes(): List<ThemeSpec> = listOf(
        ThemeSpec("precision-dark", "Precision Dark", dark = true, palette = precisionDark),
        ThemeSpec("precision-light", "Precision Light", dark = false, palette = precisionLight),
    )
}
