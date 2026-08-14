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
 */
interface BodyFormatter : Plugin {
    /** Formats the raw body [bytes] (with the flow's [mimeType]) into text. */
    fun format(bytes: ByteArray, mimeType: String): String
}
