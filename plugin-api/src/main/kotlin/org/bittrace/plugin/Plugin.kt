package org.bittrace.plugin

/**
 * Base contract every BitTrace plugin implements — bundled or dropped in as an
 * external JAR. Category interfaces (e.g. [org.bittrace.plugin.theme.ThemePlugin])
 * extend this with their own capability-specific methods.
 *
 * Implementations are discovered via [java.util.ServiceLoader]; a plugin JAR
 * must declare its class in
 * `META-INF/services/org.bittrace.plugin.Plugin`.
 */
interface Plugin {
    /** Stable unique id (e.g. "acme.solarized"). Used for de-duplication. */
    val id: String

    /** Human-readable name shown in the host UI. */
    val name: String

    /** Called once after discovery, before the plugin's capabilities are used. */
    fun init(host: PluginHost) {}
}
