package org.bittrace.plugin.builtin

import androidx.compose.ui.graphics.Color
import org.bittrace.plugin.theme.Palette
import org.bittrace.plugin.theme.Ramp
import org.bittrace.plugin.theme.ThemePlugin
import org.bittrace.plugin.theme.ThemeSpec

/**
 * The bundled "Precision" themes.
 *
 * The ramps are anchored on the colours the app has always used — the blue-grey
 * surfaces, the #3B9DFF accent — and interpolated out to the full Int UI index
 * range, so the identity survives while every shade a control might ask for
 * exists. Anchors are placed so the indices Int UI reaches for land on the
 * intended colour: `gray(2)` is the panel, `gray(12)` is body text, `blue(6)` is
 * the dark accent and `blue(4)` the light one.
 */

// Every dark shade below is a value from DESIGN.MD §2, placed at the index the
// semantic role reads. The comment on each line names that role, so a change to
// the spec has exactly one place to land.
private val darkGray = Ramp.of(
    listOf(
        Color(0xFF131416), // 1  window backdrop
        Color(0xFF1E1F22), // 2  editor — content, field interiors
        Color(0xFF2B2D30), // 3  panel — chrome, headers, popups
        Color(0xFF2F3134), // 4  row hover
        Color(0xFF303235), // 5  border2 — internal dividers
        Color(0xFF35373B), // 6  hover fill, header strips
        Color(0xFF393B40), // 7  border, active stripe button
        Color(0xFF43454A), // 8  pressed / toggled-on
        Color(0xFF4C5052), // 9  outline-button border
        Color(0xFF6F737A), // 10 disabled text, checkbox border
        Color(0xFF8B9098), // 11
        Color(0xFFB4B8BF), // 12 dim
        Color(0xFFDFE1E5), // 13 text
        Color(0xFFFFFFFF), // 14 on-accent content
    ),
)

private val lightGray = Ramp.of(
    listOf(
        Color(0xFF1A1E21), // 1  body text
        Color(0xFF2A2F33), // 2
        Color(0xFF3A4046), // 3
        Color(0xFF4A5158), // 4
        Color(0xFF5C646A), // 5  secondary text
        Color(0xFF737B82), // 6
        Color(0xFF878F96), // 7
        Color(0xFF9AA1A7), // 8  placeholders and hints
        Color(0xFFB4BBC1), // 9
        Color(0xFFC7CDD2), // 10
        Color(0xFFD0D5DA), // 11 borders
        Color(0xFFE3E7EB), // 12 chrome, header strips, row hover
        Color(0xFFEEF0F2), // 13 window surface
        Color(0xFFFFFFFF), // 14 panel surface
    ),
)

// The accent ramp carries five values the spec names outright: the selected-row
// fill, the waterfall's wait segment, and the primary button's three states.
private val darkBlue = Ramp.of(
    listOf(
        Color(0xFF1B2A4A), // 1
        Color(0xFF2E436E), // 2  selected row
        Color(0xFF2F5182), // 3  waterfall wait
        Color(0xFF2E60D0), // 4  primary pressed
        Color(0xFF3574F0), // 5  accent
        Color(0xFF4682FA), // 6  primary hover
        Color(0xFF548AF7), // 7  accent as text
        Color(0xFF6F9FF9), // 8
        Color(0xFF8DB4FB), // 9
        Color(0xFFB0CBFC), // 10
        Color(0xFFD3E2FD), // 11
        Color(0xFFEEF4FE), // 12
    ),
)
private val lightBlue = Ramp.between(12, listOf(Color(0xFF0A2A4D), Color(0xFF2680E0), Color(0xFF8FC4F5), Color(0xFFEAF3FD)))

// Semantic ramps are anchored so the index each role reads lands on the spec's
// value: green(7) = #73BD79, red(7) = #F75464, yellow(6) = #E3AE4D,
// teal(6) = #3592C4, orange(6) = #E08855, purple(8) = #B99BF8.
private val darkGreen = Ramp.between(12, listOf(Color(0xFF0C2A16), Color(0xFF3F7C46), Color(0xFF5FAD65), Color(0xFF73BD79), Color(0xFFE8F6EA)))
private val lightGreen = Ramp.between(12, listOf(Color(0xFF0B2E1F), Color(0xFF2E9E63), Color(0xFF8FD3B0), Color(0xFFE9F7F0)))
private val darkRed = Ramp.between(12, listOf(Color(0xFF2E1414), Color(0xFF9B3B3B), Color(0xFFDB5C5C), Color(0xFFF75464), Color(0xFFFCE9EB)))
private val lightRed = Ramp.between(12, listOf(Color(0xFF3D1212), Color(0xFFD64545), Color(0xFFEC9A9A), Color(0xFFFCEDED)))
private val darkYellow = Ramp.between(12, listOf(Color(0xFF2B2008), Color(0xFF8A6A22), Color(0xFFE3AE4D), Color(0xFFFAF0DA)))
private val lightYellow = Ramp.between(12, listOf(Color(0xFF3A2907), Color(0xFFB9821F), Color(0xFFE0BE7C), Color(0xFFFBF3E3)))
private val darkOrange = Ramp.between(12, listOf(Color(0xFF2E1809), Color(0xFF8C4E27), Color(0xFFE08855), Color(0xFFFBEDE3)))
private val lightOrange = Ramp.between(12, listOf(Color(0xFF3A1B08), Color(0xFFC2601F), Color(0xFFE3A277), Color(0xFFFBEFE6)))
private val darkPurple = Ramp.between(12, listOf(Color(0xFF1D1740), Color(0xFF5A4BA8), Color(0xFF8F7BE0), Color(0xFFB99BF8), Color(0xFFF1EBFE)))
private val lightPurple = Ramp.between(12, listOf(Color(0xFF1D1747), Color(0xFF6152C4), Color(0xFFA79CE8), Color(0xFFEFEDFB)))
private val darkTeal = Ramp.between(12, listOf(Color(0xFF07222E), Color(0xFF1F6484), Color(0xFF3592C4), Color(0xFF7FC0E2), Color(0xFFE6F4FB)))
private val lightTeal = Ramp.between(12, listOf(Color(0xFF0A2E27), Color(0xFF2A8A73), Color(0xFF8FCDBA), Color(0xFFE9F6F2)))

/** The original "Precision" dark palette. */
val precisionDark = Palette(
    isDark = true,
    gray = darkGray,
    blue = darkBlue,
    green = darkGreen,
    red = darkRed,
    yellow = darkYellow,
    orange = darkOrange,
    purple = darkPurple,
    teal = darkTeal,
)

/** Its light counterpart, keeping the same roles: surfaces light, text dark. */
val precisionLight = Palette(
    isDark = false,
    gray = lightGray,
    blue = lightBlue,
    green = lightGreen,
    red = lightRed,
    yellow = lightYellow,
    orange = lightOrange,
    purple = lightPurple,
    teal = lightTeal,
)

/** Bundled theme plugin — the app's built-in Dark/Light themes. */
class PrecisionThemePlugin : ThemePlugin {
    override val id = "bittrace.precision"
    override val name = "Precision"

    override fun themes(): List<ThemeSpec> = listOf(
        ThemeSpec("precision-dark", "Precision Dark", palette = precisionDark),
        ThemeSpec("precision-light", "Precision Light", palette = precisionLight),
    )
}
