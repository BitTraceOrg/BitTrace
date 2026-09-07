package org.bittrace.plugin

import org.bittrace.plugin.collection.CollectionActionPlugin
import org.bittrace.plugin.flow.FlowActionPlugin
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.importer.RequestImporter
import org.bittrace.plugin.theme.ThemePlugin

/**
 * Holds the plugins discovered at startup (bundled + external) and lets the app
 * pull them out by capability interface.
 */
class PluginRegistry(val plugins: List<Plugin>) {

    /** All loaded plugins implementing category interface [T]. */
    inline fun <reified T> byType(): List<T> = plugins.filterIsInstance<T>()

    // Resolved once, at construction. These were getters, which re-ran
    // filterIsInstance over every plugin on each read; the plugin list never
    // changes after discovery, so there was nothing for the repeat to catch.

    /** Theme plugins, in load order (bundled first). */
    val themePlugins: List<ThemePlugin> = byType()

    /** Body formatters (RAW/HEX/JSON + any external), in load order. */
    val formatters: List<BodyFormatter> = byType()

    /** Request importers (cURL + any external), in load order. */
    val importers: List<RequestImporter> = byType()

    /** Contributors to the collections tree's context menu, in load order. */
    val collectionActions: List<CollectionActionPlugin> = byType()

    /** Contributors to a captured flow's context menu, in load order. */
    val flowActions: List<FlowActionPlugin> = byType()
}
