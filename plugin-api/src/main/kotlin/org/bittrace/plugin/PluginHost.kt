package org.bittrace.plugin

/**
 * Services the host application exposes to plugins via [Plugin.init]. Kept
 * deliberately minimal so plugins depend on a small, stable surface rather than
 * app internals. Extend as new capabilities need host access.
 */
interface PluginHost {
    /** Writes a line to the host's log/stderr, prefixed with the plugin id. */
    fun log(message: String)
}
