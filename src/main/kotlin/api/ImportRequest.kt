package org.bittrace.api

import org.bittrace.plugin.importer.ImportedRequest
import org.bittrace.plugin.importer.RequestImporter

/** What an import attempt produced, including anything the importer had to drop. */
class ImportAttempt(
    val request: ApiRequest? = null,
    val importer: String = "",
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Runs [text] past each importer and converts the first match.
 *
 * Importers are asked in load order, so a bundled one can be superseded by an
 * external plugin that claims the same text — the same precedence the theme and
 * formatter registries use.
 */
fun importRequest(text: String, importers: List<RequestImporter>): ImportAttempt {
    if (text.isBlank()) return ImportAttempt(error = "Nothing to import — the clipboard is empty.")

    val importer = importers.firstOrNull { runCatching { it.canImport(text) }.getOrDefault(false) }
        ?: return ImportAttempt(error = "No importer recognised that text.")

    // A third-party importer must not take the app down with it.
    val imported = runCatching { importer.import(text) }.getOrElse {
        return ImportAttempt(error = "${importer.name} failed: ${it.message ?: it::class.simpleName}")
    } ?: return ImportAttempt(error = "${importer.name} could not read that text.")

    return ImportAttempt(
        request = imported.toApiRequest(),
        importer = importer.name,
        warnings = imported.warnings,
    )
}

/** Maps the plugin-facing shape onto the app's own request model. */
private fun ImportedRequest.toApiRequest(): ApiRequest = ApiRequest(
    name = name.ifBlank { nameFor(method, url) },
    method = method.ifBlank { "GET" },
    url = url,
    // The URL is authoritative for the query, and the table mirrors it.
    params = paramsOf(url),
    headers = headers.filterNot { it.name.equals("cookie", ignoreCase = true) }
        .map { KeyValue(it.name, it.value) },
    cookies = headers.firstOrNull { it.name.equals("cookie", ignoreCase = true) }
        ?.let { cookiesOf(it.value) }
        .orEmpty(),
    body = ApiBody(contentType = contentType, text = body),
)
