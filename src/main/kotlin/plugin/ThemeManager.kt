package org.bittrace.plugin

import org.bittrace.plugin.theme.ThemeSpec
import org.bittrace.data.SettingsStore
import org.bittrace.ui.P

/**
 * Aggregates the themes contributed by every [org.bittrace.plugin.theme.ThemePlugin]
 * (bundled + external) and applies the selected one to [P]. This is the bridge
 * between the plugin registry and the existing theming surface — the appearance
 * picker just lists [availableThemes].
 */
class ThemeManager(registry: PluginRegistry) {

    /** All themes from all theme plugins, in plugin load order. */
    val availableThemes: List<ThemeSpec> = registry.themePlugins.flatMap { plugin ->
        runCatching { plugin.themes() }.getOrElse {
            System.err.println("[plugin] themes() failed for '${plugin.id}': $it"); emptyList()
        }
    }

    /** Applies the theme with [id] (or the legacy/fallback resolution) to [P]. */
    fun applyId(id: String) {
        resolve(id)?.let { P.apply(it.palette) }
    }

    /** Applies and persists the selection. */
    fun setActiveThemeId(id: String, settings: SettingsStore) {
        applyId(id)
        settings.update { it.copy(theme = id) }
    }

    /** The effective theme id for a stored value (resolves legacy + fallback). */
    fun activeId(stored: String): String = resolve(stored)?.id ?: stored

    /** Maps legacy names, then falls back to the first available theme. */
    private fun resolve(id: String): ThemeSpec? {
        val wanted = LEGACY[id] ?: id
        return availableThemes.firstOrNull { it.id == wanted } ?: availableThemes.firstOrNull()
    }

    private companion object {
        val LEGACY = mapOf("dark" to "precision-dark", "light" to "precision-light")
    }
}
