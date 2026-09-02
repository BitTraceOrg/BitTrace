package org.bittrace.plugin.importer

import org.bittrace.plugin.Plugin

/** One header on an imported request. */
class ImportedHeader(val name: String, val value: String)

/**
 * A request recovered from text — a cURL command, a fetch snippet, whatever an
 * importer understands.
 *
 * Deliberately plain: the app's own request model carries serialization and
 * file-body concerns that a plugin has no business knowing about, so importers
 * speak in this and the host maps it across.
 */
class ImportedRequest(
    val method: String = "GET",
    val url: String = "",
    val headers: List<ImportedHeader> = emptyList(),
    val body: String = "",
    val contentType: String = "",
    /** A suggested name; the host falls back to one derived from the URL. */
    val name: String = "",
    /** What the importer understood but could not carry across, for the user. */
    val warnings: List<String> = emptyList(),
)

/**
 * A plugin category (like themes and body formatters) that turns pasted text
 * into a request the API client can send.
 *
 * The host tries [canImport] on each importer in load order and uses the first
 * that claims the text, so a sniff should be cheap and specific — matching a
 * command name or a syntax marker, not attempting the parse.
 *
 * Bundled importers ship with the app; external ones drop in as JARs, exactly
 * like theme plugins.
 */
interface RequestImporter : Plugin {

    /** Whether this importer recognises [text]. Cheap; no parsing. */
    fun canImport(text: String): Boolean

    /** Parses [text], or returns null if it turns out not to be usable after all. */
    fun import(text: String): ImportedRequest?
}
