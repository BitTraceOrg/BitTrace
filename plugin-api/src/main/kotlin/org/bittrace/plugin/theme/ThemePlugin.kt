package org.bittrace.plugin.theme

import org.bittrace.plugin.Plugin

/** One selectable theme contributed by a [ThemePlugin]. */
data class ThemeSpec(
    /** Stable unique id (e.g. "acme.solarized-dark"). */
    val id: String,
    /** Human-readable name shown in the appearance picker. */
    val name: String,
    /** Whether this is a dark theme (for grouping/UX hints). */
    val dark: Boolean,
    /** The colours applied when this theme is active. */
    val palette: Palette,
)

/**
 * The first plugin category: contributes one or more selectable themes. The host
 * flattens `themes()` across all theme plugins (bundled + external) into the
 * appearance picker — implementing this interface and shipping the JAR is all it
 * takes to add a theme.
 */
interface ThemePlugin : Plugin {
    fun themes(): List<ThemeSpec>
}
