package org.bittrace.plugin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.bittrace.data.SettingsStore
import org.bittrace.plugin.theme.ThemeSpec
import org.bittrace.ui.P

/**
 * Aggregates the themes contributed by every [org.bittrace.plugin.theme.ThemePlugin]
 * (bundled + external) and applies the selected one to [P]. This is the bridge
 * between the plugin registry and the existing theming surface — the appearance
 * picker just lists [availableThemes].
 */
class ThemeManager(
    registry: PluginRegistry,
    /**
     * Where a misbehaving theme plugin is reported. The same `(level, message)`
     * sink the loader takes, so a theme that throws lands in the log panel
     * beside the load failures rather than in a console nobody has open.
     */
    onLog: (String, String) -> Unit = { level, message -> System.err.println("[plugin/$level] $message") },
) {

    /** All themes from all theme plugins, in plugin load order. */
    val availableThemes: List<ThemeSpec> = registry.themePlugins.flatMap { plugin ->
        runCatching { plugin.themes() }.getOrElse {
            onLog("error", "themes() failed for '${plugin.id}': $it"); emptyList()
        }
    }

    /**
     * The theme currently applied. Snapshot-backed, and read by the Jewel bridge
     * (`ui/JewelBridge.kt`): [ThemeSpec.dark] decides which Int UI base the
     * component styling is derived from, so it is load-bearing, not a hint.
     */
    var activeTheme: ThemeSpec? by mutableStateOf(null)
        private set

    /** Applies the theme with [id] (or the legacy/fallback resolution) to [P]. */
    fun applyId(id: String) {
        resolve(id)?.let { activeTheme = it; P.apply(it.palette) }
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
