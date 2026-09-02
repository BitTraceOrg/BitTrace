package org.bittrace.plugin

/**
 * Services the host application exposes to plugins via [Plugin.init]. Kept
 * deliberately minimal so plugins depend on a small, stable surface rather than
 * app internals. Extend as new capabilities need host access.
 *
 * A plugin that wants any of this keeps the host it is handed:
 *
 * ```
 * private var host: PluginHost? = null
 * override fun init(host: PluginHost) { this.host = host }
 * ```
 */
interface PluginHost {

    /**
     * Writes a line to the host's log at [level] — `"info"`, `"warn"` or
     * `"error"`, matching the levels the host's own log panel filters by.
     * Anything else is treated as `"info"`.
     *
     * The level is what decides whether a line is merely recorded or actually
     * surfaced: the host counts warnings and errors where the user can see
     * them, so a plugin reporting a real failure should say so rather than
     * letting it settle in with the startup chatter.
     */
    fun log(level: String, message: String)

    /** An informational line — the common case. */
    fun log(message: String): Unit = log("info", message)

    /**
     * Puts [text] on the system clipboard, reporting whether it landed.
     *
     * Here because "give me this somewhere else" is what most actions do, and
     * without it every action plugin reaches for `java.awt.Toolkit` and
     * hand-rolls the same try/catch — which is exactly what the two bundled
     * action plugins were doing. Copying can fail when another process owns the
     * clipboard mid-write, so the result is worth telling the user about.
     */
    fun copyToClipboard(text: String): Boolean
}
