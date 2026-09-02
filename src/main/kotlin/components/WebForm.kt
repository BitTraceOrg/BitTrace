package org.bittrace.components

import java.net.URLDecoder

/**
 * Decoding for the request inspector's FORM tab: turns a posted body into the
 * fields the browser actually submitted, for the two encodings HTML forms use.
 *
 * Like the body formatters, this is total — a truncated or malformed body
 * yields the fields that could be read, never an exception.
 */

/** One submitted field. File parts carry [fileName]/[contentType] instead of text. */
data class FormField(
    val name: String,
    val value: String,
    val fileName: String? = null,
    val contentType: String? = null,
    val size: Long = value.length.toLong(),
)

/** True when [contentType] is an encoding this decoder understands. */
fun isFormBody(contentType: String): Boolean =
    contentType.contains("x-www-form-urlencoded", ignoreCase = true) ||
        contentType.contains("multipart/form-data", ignoreCase = true)

/**
 * Decodes [bytes] according to [contentType], or returns null when the body is
 * not a form encoding (the caller shows a hint instead of an empty table).
 */
fun parseForm(bytes: ByteArray?, contentType: String): List<FormField>? {
    if (bytes == null) return null
    return when {
        contentType.contains("x-www-form-urlencoded", ignoreCase = true) ->
            parseUrlEncoded(String(bytes, Charsets.UTF_8))

        contentType.contains("multipart/form-data", ignoreCase = true) ->
            boundaryOf(contentType)?.let { parseMultipart(bytes, it) } ?: emptyList()

        else -> null
    }
}

/** `a=1&b=two+words&c` → three fields (a valueless key keeps an empty value). */
private fun parseUrlEncoded(text: String): List<FormField> =
    text.trim().split('&')
        .filter { it.isNotBlank() }
        .map { pair ->
            val name = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            FormField(formDecode(name), formDecode(value))
        }

/** The `boundary=` parameter of a multipart content type, unquoted. */
private fun boundaryOf(contentType: String): String? {
    val at = contentType.indexOf("boundary=", ignoreCase = true)
    if (at < 0) return null
    return contentType.substring(at + "boundary=".length)
        .substringBefore(';')
        .trim()
        .trim('"')
        .takeIf { it.isNotEmpty() }
}

/**
 * Splits a `multipart/form-data` body on its [boundary] and reads each part's
 * `Content-Disposition`. Parts are handled as bytes so a file upload's size is
 * accurate and its binary content is never decoded as text.
 */
private fun parseMultipart(bytes: ByteArray, boundary: String): List<FormField> {
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val separator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    val fields = mutableListOf<FormField>()

    var start = indexOf(bytes, delimiter, 0)
    while (start >= 0) {
        val partStart = start + delimiter.size
        // `--` right after the delimiter marks the closing boundary.
        if (partStart + 2 <= bytes.size && bytes[partStart] == '-'.code.toByte() &&
            bytes[partStart + 1] == '-'.code.toByte()
        ) break

        val next = indexOf(bytes, delimiter, partStart)
        val partEnd = if (next < 0) bytes.size else next
        val headerEnd = indexOf(bytes, separator, partStart)
        if (headerEnd < 0 || headerEnd > partEnd) break

        val headers = String(bytes, partStart, headerEnd - partStart, Charsets.ISO_8859_1)
        val bodyStart = headerEnd + separator.size
        // Trim the CRLF that precedes the next boundary.
        val bodyEnd = (partEnd - 2).coerceAtLeast(bodyStart)

        val name = headerParam(headers, "name") ?: ""
        val fileName = headerParam(headers, "filename")
        val partType = headerValue(headers, "content-type")
        val size = (bodyEnd - bodyStart).toLong()

        fields += if (fileName != null) {
            FormField(name, "", fileName, partType, size)
        } else {
            FormField(name, String(bytes, bodyStart, (bodyEnd - bodyStart), Charsets.UTF_8), size = size)
        }

        start = next
    }
    return fields
}

/** `%20`/`+` decoding that falls back to the raw text on malformed escapes. */
private fun formDecode(text: String): String =
    runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)

/** `name="value"` (or bare `name=value`) out of a part's header block. */
private fun headerParam(headers: String, param: String): String? {
    val at = headers.indexOf("$param=", ignoreCase = true)
    if (at < 0) return null
    val rest = headers.substring(at + param.length + 1)
    return if (rest.startsWith("\"")) {
        rest.drop(1).substringBefore('"')
    } else {
        rest.takeWhile { it != ';' && it != '\r' && it != '\n' }.trim()
    }
}

/** The value of a `Header: value` line in a part's header block. */
private fun headerValue(headers: String, name: String): String? =
    headers.lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** First index of [needle] in [haystack] at or after [from], or -1. */
private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty() || needle.size > haystack.size) return -1
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
