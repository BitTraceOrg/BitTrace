package org.bittrace.api

import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide

/**
 * Turns a captured flow into an editable request.
 *
 * This is the move a proxy and an API client can only make together: watch what
 * an app actually sent, then replay it with one header changed. Everything the
 * builder needs is already on the row — method, URL, headers, query — and the
 * body comes from the same cache the Inspector reads.
 */
fun requestFromFlow(
    row: TrafficRow,
    bodyProvider: (String, BodySide) -> ByteArray?,
): ApiRequest {
    val head = row.request.request
    val captured = row.completeRequest?.request

    val all = captured?.headers.orEmpty().filterNot { it.name.lowercase() in DROPPED_HEADERS }
    // A Cookie header becomes rows, so individual cookies can be switched off.
    val headers = all.filterNot { it.name.equals("cookie", ignoreCase = true) }
        .map { KeyValue(it.name, it.value) }
    val cookies = all.firstOrNull { it.name.equals("cookie", ignoreCase = true) }
        ?.let { cookiesOf(it.value) }
        .orEmpty()

    // The URL keeps its query and the table mirrors it — the two are kept in
    // step while editing, and the URL is what gets sent.
    val params = paramsOf(head.url).ifEmpty { head.queryString.map { KeyValue(it.name, it.value) } }

    val bytes = bodyProvider(row.id, BodySide.REQUEST)
    val body = ApiBody(
        contentType = captured?.postData?.mimeType.orEmpty(),
        // A body that is not text cannot be edited here; the row keeps it, and
        // sending an empty body beats pasting mojibake into the editor.
        text = bytes?.let { decodeOrEmpty(it) }.orEmpty(),
    )

    return ApiRequest(
        name = nameFor(head.method, head.url),
        method = head.method,
        url = head.url,
        params = params,
        headers = headers,
        cookies = cookies,
        body = body,
    )
}

/** Splits a `Cookie` header value into editable rows. */
fun cookiesOf(header: String): List<KeyValue> =
    header.split(';').mapNotNull { pair ->
        val name = pair.substringBefore('=').trim()
        if (name.isEmpty()) null else KeyValue(name, pair.substringAfter('=', "").trim())
    }

/**
 * A short, file-name-safe label like `GET users`.
 *
 * Shared with the cURL importer, which is where the `uppercase` comes from: a
 * method off the wire already arrives upper-cased, one out of a pasted command
 * may not.
 */
internal fun nameFor(method: String, url: String): String {
    val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?')
    val leaf = path.trimEnd('/').substringAfterLast('/').ifBlank { "root" }
    val label = method.uppercase()
    return fileNameFor("$label $leaf") ?: label
}

private fun decodeOrEmpty(bytes: ByteArray): String {
    if (bytes.size > EDITABLE_BODY_LIMIT) return ""
    return try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        ""
    }
}

/**
 * Headers the client must not replay.
 *
 * `host`, `content-length`, `connection`, `transfer-encoding` and friends
 * describe *that* connection, not the request — `HttpClient` either computes
 * them itself or rejects them outright. The marker header is ours and would
 * make a replay correlate against the original flow.
 */
private val DROPPED_HEADERS = setOf(
    "host",
    "content-length",
    "connection",
    "transfer-encoding",
    "upgrade",
    "expect",
    "keep-alive",
    "proxy-connection",
    "http2-settings",
    MARKER_HEADER.lowercase(),
)

/** Matches the editor's own ceiling, so a replay never lands read-only. */
private const val EDITABLE_BODY_LIMIT = 256 * 1024
