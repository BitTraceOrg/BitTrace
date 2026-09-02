package org.bittrace.plugin.format

import org.bittrace.plugin.Plugin

/**
 * A plugin category (like theme plugins) that renders a request/response body
 * into displayable text — e.g. RAW, HEX, pretty JSON. The host shows one chip
 * per available formatter at the top of the BODY tab; picking one runs its
 * [format] over the raw bytes.
 *
 * Bundled formatters ship with the app; external ones drop in as JARs, exactly
 * like theme plugins.
 *
 * **Threading**: [format] and [highlight] are called on a background dispatcher,
 * never on the UI thread, so a slow formatter delays only its own pane. They may
 * run concurrently for the request and response panes, so an implementation must
 * not rely on per-instance mutable state; and they must not touch the UI.
 */
interface BodyFormatter : Plugin {
    /** Formats the raw body [bytes] (with the flow's [mimeType]) into text. */
    fun format(bytes: ByteArray, mimeType: String): String

    /**
     * Formats the body *and* classifies it for syntax highlighting. This is what
     * the host renders; [format] remains the plain-text view of the same output.
     * The default implementation returns unhighlighted text, so formatters that
     * only implement [format] keep working.
     */
    fun highlight(bytes: ByteArray, mimeType: String): FormattedBody =
        FormattedBody(format(bytes, mimeType))

    /**
     * True when this formatter is the natural default for [mimeType] — the host
     * pre-selects the first formatter that claims the flow's content type, and
     * falls back to RAW when none does. General-purpose formatters (RAW, HEX)
     * claim nothing.
     */
    fun handles(mimeType: String): Boolean = false
}
