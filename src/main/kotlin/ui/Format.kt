package org.bittrace.ui

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.bittrace.data.TrafficRow

/** Row-to-cell formatting shared by the flow table and the waterfall. */

private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

/** Coarser, and dated: a history entry can be days old, a flow never is. */
private val DAY_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())

/** Wall-clock time of an epoch-millis instant, to the millisecond. */
fun clockOf(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis))

/** Date and time of an epoch-millis instant; blank for a missing timestamp. */
fun dayClockOf(millis: Long): String =
    if (millis <= 0) "" else DAY_CLOCK.format(Instant.ofEpochMilli(millis))

fun instantOf(value: String): Instant? =
    runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant() }
        .getOrNull()

/** Splits a URL into (host, path); host without scheme, path incl. leading slash. */
fun hostPath(url: String): Pair<String, String> {
    val noScheme = url.substringAfter("://", url)
    val slash = noScheme.indexOf('/')
    return if (slash < 0) noScheme to "" else noScheme.substring(0, slash) to noScheme.substring(slash)
}

fun kindOf(url: String): String {
    val path = hostPath(url).second.substringBefore('?').lowercase()
    val extension = path.substringAfterLast('.', "")
    return EXTENSION_KINDS[extension] ?: "html"
}

/**
 * A row's kind, preferring what the response said it was.
 *
 * An extension is a guess and a `Content-Type` is an answer: `/api/v2/users`
 * has no extension at all and is nearly always JSON, while `/download?f=x.png`
 * may be anything. The URL stays the fallback, for a request still in flight or
 * a response that never said.
 */
fun kindOfRow(row: TrafficRow): String {
    val declared = row.completeResponse?.response?.headers
        ?.firstOrNull { it.name.equals("content-type", ignoreCase = true) }
        ?.value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return kindOfType(declared) ?: kindOf(row.request.request.url)
}

/** The bucket a MIME type falls in, or null when it names none of them. */
fun kindOfType(mime: String): String? = when {
    mime.isBlank() -> null
    mime.contains("html") -> "html"
    mime.contains("css") -> "css"
    mime.contains("javascript") || mime.contains("ecmascript") -> "js"
    mime.contains("json") -> "json"
    mime.contains("xml") -> "xml"
    mime.startsWith("image/") -> "img"
    mime.startsWith("font/") || mime.contains("woff") || mime.contains("ttf") -> "font"
    mime.startsWith("audio/") || mime.startsWith("video/") -> "media"
    mime.startsWith("text/") -> "text"
    mime.startsWith("application/") -> "bin"
    else -> null
}

/** Extensions worth recognising, for when nothing declared a type. */
private val EXTENSION_KINDS = mapOf(
    "css" to "css",
    "js" to "js", "mjs" to "js", "cjs" to "js",
    "json" to "json", "map" to "json",
    "xml" to "xml", "xsl" to "xml", "rss" to "xml", "atom" to "xml",
    "ico" to "img", "png" to "img", "jpg" to "img", "jpeg" to "img",
    "gif" to "img", "svg" to "img", "webp" to "img", "avif" to "img", "bmp" to "img",
    "woff" to "font", "woff2" to "font", "ttf" to "font", "otf" to "font", "eot" to "font",
    "mp3" to "media", "mp4" to "media", "webm" to "media", "ogg" to "media",
    "wav" to "media", "mov" to "media", "m3u8" to "media", "ts" to "media",
    "txt" to "text", "csv" to "text", "md" to "text",
    "wasm" to "bin", "zip" to "bin", "gz" to "bin", "pdf" to "bin", "bin" to "bin",
    "html" to "html", "htm" to "html",
)

fun tlsText(row: TrafficRow): String {
    val tls = row.request.tls
    return if (tls.isBlank() || tls == "—") "—" else tls.removePrefix("TLS ").removePrefix("TLSv").trim()
}

fun bytesStr(size: Long?): String = when {
    size == null || size < 0 -> "—"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

/** Wall-clock time the request was first seen. */
fun startStr(row: TrafficRow): String =
    instantOf(row.request.startedDateTime)?.let { CLOCK.format(it) } ?: ""

/** Start time plus the flow's total elapsed time, once the response reports it. */
fun endStr(row: TrafficRow): String {
    val start = instantOf(row.request.startedDateTime) ?: return ""
    val ms = row.response?.time ?: return ""
    return CLOCK.format(start.plusMillis(ms.toLong()))
}

fun durStr(row: TrafficRow): String = row.response?.time?.let { "${it.toLong()} ms" } ?: ""

/** Status label + colour for the ST column and waterfall bars. */
fun statusOf(row: TrafficRow): Pair<String, Color> {
    val resp = row.response ?: return "" to P.dim
    if (resp.error) return "ERR" to P.err
    val st = resp.response.status
    val color = when {
        st in 200..299 -> P.ok
        st in 300..399 -> P.info
        st >= 400 -> P.warn
        else -> P.dim
    }
    return st.toString() to color
}
