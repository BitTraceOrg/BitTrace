package org.bittrace.plugin

import org.bittrace.plugin.Plugin
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.theme.ThemePlugin

/**
 * Holds the plugins discovered at startup (bundled + external) and lets the app
 * pull them out by capability interface.
 */
class PluginRegistry(val plugins: List<Plugin>) {

    /** All loaded plugins implementing category interface [T]. */
    inline fun <reified T> byType(): List<T> = plugins.filterIsInstance<T>()

    /** Theme plugins, in load order (bundled first). */
    val themePlugins: List<ThemePlugin> get() = byType()

    /** Body formatters (RAW/HEX/JSON + any external), in load order. */
    val formatters: List<BodyFormatter> get() = byType()
}
