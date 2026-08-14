package org.bittrace.components

import androidx.compose.ui.graphics.Color
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Row-to-cell formatting shared by the flow table and the waterfall. */

private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

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
    return when {
        path.endsWith(".css") -> "css"
        path.endsWith(".js") -> "js"
        path.endsWith(".ico") || path.endsWith(".png") || path.endsWith(".jpg") ||
            path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".webp") -> "img"
        path.endsWith(".txt") -> "text"
        else -> "html"
    }
}

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
