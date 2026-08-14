package org.bittrace.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.bittrace.data.NameValuePair
import org.bittrace.data.SettingsStore
import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.VerticalSplitter
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder

/** Request | Response inspector (DESIGN.md §6.10), with a persisted divider. */
@Composable
fun Inspector(row: TrafficRow?, bodyProvider: (String, BodySide) -> ByteArray?, settings: SettingsStore) {
    var rowWidth by remember { mutableStateOf(0f) }
    val split = settings.settings.requestResponseSplit
    Row(
        Modifier.fillMaxSize().background(P.panel)
            .onGloballyPositioned { rowWidth = it.size.width.toFloat() },
    ) {
        Pane(
            Modifier.weight(split),
            "REQUEST", BodySide.REQUEST, row, bodyProvider,
            tabs = listOf("OVERVIEW", "HEADERS", "BODY", "COOKIES", "RAW", "HEX"), default = "OVERVIEW",
        )
        VerticalSplitter { delta ->
            if (rowWidth > 0f) settings.update {
                it.copy(requestResponseSplit = (it.requestResponseSplit + delta / rowWidth).coerceIn(0.15f, 0.85f))
            }
        }
        Pane(
            Modifier.weight(1f - split),
            "RESPONSE", BodySide.RESPONSE, row, bodyProvider,
            tabs = listOf("OVERVIEW", "BODY", "COOKIES", "HEADERS", "TIMING"), default = "BODY",
        )
    }
}

@Composable
private fun Pane(
    modifier: Modifier,
    caption: String,
    side: BodySide,
    row: TrafficRow?,
    bodyProvider: (String, BodySide) -> ByteArray?,
    tabs: List<String>,
    default: String,
) {
    var tab by remember { mutableStateOf(default) }
    Column(modifier.fillMaxHeight()) {
        // Thin tab strip.
        Row(
            Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PzText(caption, color = P.dim, size = 12, family = P.Ui)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                tabs.forEach { t ->
                    val on = t == tab
                    Box(
                        Modifier.background(if (on) P.accent else Color.Transparent)
                            .clickable { tab = t }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { PzText(t, color = if (on) P.bg else P.dim, size = 12, family = P.Ui) }
                }
            }
        }

        if (row == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PzText("Select a flow to inspect", color = P.dim, size = 13)
            }
            return@Column
        }

        // Fixed pane header (URL/status line + MetaGrid), adapting to the side;
        // hidden on the BODY tab to give the body maximum height.
        if (tab != "BODY") PaneHeader(row, side)

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Selectable + Ctrl+C copyable, but with the right-click Copy context
            // menu suppressed (empty representation).
            CompositionLocalProvider(LocalContextMenuRepresentation provides NoContextMenu) {
                SelectionContainer {
                    when (tab) {
                        "OVERVIEW" -> FlowOverview(row, side)
                        "HEADERS" -> Kv(headersOf(row, side))
                        "BODY" -> BodyText(row, side, bodyProvider)
                        "COOKIES" -> Cookies(row, side)
                        "RAW" -> Raw(row, side, bodyProvider)
                        "HEX" -> HexView(row, side, bodyProvider)
                        "TIMING" -> Timing(row)
                    }
                }
            }
        }
    }
}

// --- content ---

// --- pane header (adapts to side + available info) ---

@Composable
private fun PaneHeader(row: TrafficRow, side: BodySide) {
    if (side == BodySide.REQUEST) {
        val r = row.request.request
        val (host, path) = hostPath(r.url)
        Row(
            Modifier.fillMaxWidth().background(P.bg).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PzText(r.method, color = P.info, size = 13, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            PzText("${schemeOf(r.url)}://", color = P.dim, size = 13)
            PzText("$host$path", color = P.text, size = 13, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
        }
        MetaGrid(
            listOf(
                Triple("VERSION", r.httpVersion, P.text),
                Triple("REMOTE", remoteOf(row).ifBlank { "—" }, P.text),
                Triple("HEADER BYTES", bytesStr(r.headersSize), P.text),
                Triple("TLS", tlsText(row), if (tlsText(row) == "—") P.faint else P.ok),
            ),
        )
    } else {
        val resp = row.response
        val (_, sColor) = statusOf(row)
        Row(
            Modifier.fillMaxWidth().background(P.bg).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (resp == null) {
                PzText("pending", color = P.dim, size = 13)
            } else {
                PzText(
                    if (resp.error) "CONN RESET" else "${resp.response.status} ${resp.response.statusText}".trim(),
                    color = sColor, size = 13, weight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                PzText(mimeOf(row), color = P.dim, size = 12)
                Spacer(Modifier.weight(1f))
                PzText("${resp.time.toLong()} ms · ${bytesStr(resp.response.bodySize)}", color = P.dim, size = 12)
            }
        }
        MetaGrid(
            listOf(
                Triple("VERSION", resp?.response?.httpVersion ?: "—", P.text),
                Triple("STATUS", resp?.let { if (it.error) "ERR" else it.response.status.toString() } ?: "—", sColor),
                Triple("CONTENT-LENGTH", bytesStr(resp?.response?.bodySize), P.text),
                Triple("SERVER", headerValue(row, "server") ?: "—", P.text),
            ),
        )
    }
}

/** A single row of N equal, hairline-separated caption/value cells. */
@Composable
private fun MetaGrid(cells: List<Triple<String, String, Color>>) {
    Row(Modifier.fillMaxWidth().bottomBorder(P.line)) {
        cells.forEachIndexed { i, (label, value, color) ->
            Column(
                Modifier.weight(1f)
                    .then(if (i < cells.lastIndex) Modifier.rightBorder(P.line2) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PzText(label, color = P.faint, size = 9, family = P.Ui)
                PzText(value, color = color, size = 12, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            }
        }
    }
}

// --- overview (side-specific: only data relevant to that half) ---

@Composable
private fun FlowOverview(row: TrafficRow, side: BodySide) {
    if (side == BodySide.REQUEST) RequestOverview(row) else ResponseOverview(row)
}

@Composable
private fun RequestOverview(row: TrafficRow) {
    val r = row.request.request
    val (host, path) = hostPath(r.url)
    val https = tlsText(row) != "—"
    val reqMime = row.completeRequest?.request?.postData?.mimeType?.takeIf { it.isNotBlank() } ?: "—"
    Column(Modifier.fillMaxWidth()) {
        SizeRibbon(r.headersSize, r.bodySize)

        Section("ENDPOINT")
        KvRow("url", r.url, P.info)
        KvRow("method", r.method, P.info)
        KvRow("scheme", schemeOf(r.url), if (https) P.ok else P.warn)
        KvRow("host", host)
        KvRow("path", path.ifBlank { "/" })

        Section("REQUEST")
        KvRow("http version", r.httpVersion)
        KvRow("content type", reqMime)
        KvRow("query params", r.queryString.size.toString())
        KvRow("cookies", row.completeRequest?.request?.cookies?.size?.toString() ?: "—")
    }
}

@Composable
private fun ResponseOverview(row: TrafficRow) {
    val resp = row.response
    val (_, sColor) = statusOf(row)
    val https = tlsText(row) != "—"
    val redirect = resp?.response?.redirectURL?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth()) {
        PhaseRibbon(row)
        SizeRibbon(resp?.response?.headersSize ?: 0, resp?.response?.bodySize ?: 0)

        Section("RESPONSE")
        KvRow("status", if (resp == null) "pending" else "${resp.response.status} ${resp.response.statusText}".trim(), sColor)
        KvRow("remote addr", remoteOf(row).ifBlank { "—" })
        KvRow("remote ver", resp?.response?.httpVersion ?: "—")
        KvRow("content type", mimeOf(row))
        if (redirect != null) KvRow("redirect", redirect, P.info)

        Section("TRANSFER")
        KvRow("encoding", headerValue(row, "content-encoding") ?: "identity")
        KvRow("cache", headerValue(row, "cache-control") ?: "—")

        Section("SECURITY")
        KvRow("tls", if (https) tlsText(row) else "none (cleartext)", if (https) P.ok else P.warn)

        Section("TIMING")
        KvRow("started", startStr(row).ifBlank { "—" })
        KvRow("ttfb", row.response?.timings?.wait?.takeIf { it >= 0 }?.let { "${it.toLong()} ms" } ?: "—")
        KvRow("duration", if (resp != null) "${resp.time.toLong()} ms" else "—", if (resp?.error == true) P.err else P.text)
    }
}

@Composable
private fun PhaseRibbon(row: TrafficRow) {
    val t = row.response?.timings ?: return
    val phases = listOf(
        Triple("blocked", t.blocked, P.faint),
        Triple("dns", t.dns, Color(0xFF9B8CFF)),
        Triple("connect", t.connect, P.info),
        Triple("tls", t.ssl, P.accent),
        Triple("send", t.send, Color(0xFF8FD9B6)),
        Triple("wait", t.wait, P.warn),
        Triple("receive", t.receive, P.ok),
    ).filter { it.second > 0 }
    if (phases.isEmpty()) return
    val total = (row.response?.time ?: phases.sumOf { it.second }).toLong()

    Column(
        Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(9.dp)) {
            phases.forEach { (_, ms, color) ->
                Box(Modifier.weight(ms.toFloat()).fillMaxHeight().background(color))
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                phases.forEach { (name, ms, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(color))
                        Spacer(Modifier.width(4.dp))
                        PzText("$name ${ms.toLong()}ms", color = P.faint, size = 12)
                    }
                }
            }
            PzText("total $total ms", color = P.accent, size = 12)
        }
    }
}

/**
 * A stacked size bar (same visual language as [PhaseRibbon]) splitting a
 * transfer into header vs body bytes, with a legend and total. Reused by both
 * the request and response overviews.
 */
@Composable
private fun SizeRibbon(headerBytes: Long, bodyBytes: Long) {
    val h = headerBytes.coerceAtLeast(0)
    val b = bodyBytes.coerceAtLeast(0)
    val total = h + b
    Column(
        Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(9.dp)) {
            if (total <= 0L) {
                Box(Modifier.weight(1f).fillMaxHeight().background(P.line2))
            } else {
                if (h > 0) Box(Modifier.weight(h.toFloat()).fillMaxHeight().background(P.info))
                if (b > 0) Box(Modifier.weight(b.toFloat()).fillMaxHeight().background(P.ok))
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                RibbonLegend(P.info, "headers ${bytesStr(headerBytes)}")
                RibbonLegend(P.ok, "body ${bytesStr(bodyBytes)}")
            }
            PzText("total ${bytesStr(total)}", color = P.accent, size = 12)
        }
    }
}

@Composable
private fun RibbonLegend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color))
        Spacer(Modifier.width(4.dp))
        PzText(text, color = P.faint, size = 12)
    }
}

private fun schemeOf(url: String): String = url.substringBefore("://", "")

private fun mimeOf(row: TrafficRow): String =
    row.completeResponse?.response?.content?.mimeType?.takeIf { it.isNotBlank() }
        ?: row.completeRequest?.request?.postData?.mimeType?.takeIf { it.isNotBlank() }
        ?: "—"

private fun headerValue(row: TrafficRow, name: String): String? =
    row.completeResponse?.response?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

@Composable
private fun Timing(row: TrafficRow) {
    val t = row.response?.timings
    if (t == null) { Pad("no timings yet"); return }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Section("TIMING")
        listOf(
            "blocked" to t.blocked, "dns" to t.dns, "connect" to t.connect,
            "ssl" to t.ssl, "send" to t.send, "wait" to t.wait, "receive" to t.receive,
        ).forEach { (k, v) -> KvRow(k, if (v < 0) "—" else "${v.toLong()} ms", capitalize = false) }
        KvRow("total", "${(row.response?.time ?: 0.0).toLong()} ms", P.accent, capitalize = false)
    }
}

@Composable
private fun Cookies(row: TrafficRow, side: BodySide) {
    val pairs = when (side) {
        BodySide.REQUEST -> row.completeRequest?.request?.cookies?.map { it.name to it.value }
        BodySide.RESPONSE -> row.completeResponse?.response?.cookies?.map { it.name to it.value }
    }
    if (pairs == null) { Pad("cookies arrive with the complete message"); return }
    if (pairs.isEmpty()) { Pad("no cookies"); return }
    Kv(pairs)
}

@Composable
private fun BodyText(row: TrafficRow, side: BodySide, bodyProvider: (String, BodySide) -> ByteArray?) {
    val declared = if (side == BodySide.REQUEST) row.request.request.bodySize else row.response?.response?.bodySize
    if (declared == 0L) { Pad("body: none"); return }
    val bytes = bodyProvider(row.id, side)
    if (bytes == null) { Pad("body not cached for this flow"); return }
    Mono(String(bytes, Charsets.UTF_8))
}

@Composable
private fun Raw(row: TrafficRow, side: BodySide, bodyProvider: (String, BodySide) -> ByteArray?) {
    val sb = StringBuilder()
    if (side == BodySide.REQUEST) {
        val r = row.request.request
        sb.append("${r.method} ${r.url} ${r.httpVersion}\n")
        row.completeRequest?.request?.headers?.forEach { sb.append("${it.name}: ${it.value}\n") }
    } else {
        val h = row.response?.response
        if (h == null) { Pad("no response yet"); return }
        sb.append("${h.httpVersion} ${h.status} ${h.statusText}\n")
        row.completeResponse?.response?.headers?.forEach { sb.append("${it.name}: ${it.value}\n") }
    }
    sb.append('\n')
    bodyProvider(row.id, side)?.let { sb.append(String(it, Charsets.UTF_8)) }
    Mono(sb.toString())
}

@Composable
private fun HexView(row: TrafficRow, side: BodySide, bodyProvider: (String, BodySide) -> ByteArray?) {
    val bytes = bodyProvider(row.id, side)
    if (bytes == null || bytes.isEmpty()) { Pad("body: none"); return }
    val text = buildString {
        bytes.asIterable().chunked(16).forEachIndexed { i, chunk ->
            append("%08x  ".format(i * 16))
            chunk.forEach { append("%02x ".format(it)) }
            repeat(16 - chunk.size) { append("   ") }
            append(" |")
            chunk.forEach { b -> val c = b.toInt() and 0xFF; append(if (c in 0x20..0x7E) c.toChar() else '.') }
            append("|\n")
        }
    }
    Mono(text)
}

// --- atoms ---

/** A context-menu representation that renders nothing — suppresses the
 *  right-click Copy menu while keeping text selection and Ctrl+C. */
private object NoContextMenu : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
    }
}

@Composable
private fun headersOf(row: TrafficRow, side: BodySide): List<Pair<String, String>> {
    val list: List<NameValuePair>? = when (side) {
        BodySide.REQUEST -> row.completeRequest?.request?.headers
        BodySide.RESPONSE -> row.completeResponse?.response?.headers
    }
    return list?.map { it.name to it.value } ?: emptyList()
}

@Composable
private fun Kv(pairs: List<Pair<String, String>>) {
    if (pairs.isEmpty()) { Pad("none"); return }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        pairs.forEach { (k, v) -> KvRow(k, v, P.text, keyColor = P.accent, capitalize = false) }
    }
}

@Composable
private fun Section(title: String) {
    Row(
        Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 3.dp),
    ) { PzText(title, color = P.dim, size = 11, family = P.Ui) }
}

@Composable
private fun KvRow(
    key: String,
    value: String,
    valueColor: Color = P.text,
    keyColor: Color = P.faint,
    capitalize: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().bottomBorder(P.line2).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val label = if (capitalize) key.replaceFirstChar { it.uppercase() } else key
        PzText(label, color = keyColor, size = 12, modifier = Modifier.width(120.dp))
        Spacer(Modifier.width(10.dp))
        PzText(value, color = valueColor, size = 12)
    }
}

@Composable
private fun Mono(text: String) {
    Box(Modifier.fillMaxWidth().padding(8.dp).horizontalScroll(rememberScrollState())) {
        PzText(text, color = P.text, size = 12, softWrap = false)
    }
}

@Composable
private fun Pad(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        PzText(text, color = P.dim, size = 12)
    }
}

private fun remoteOf(row: TrafficRow): String {
    val resp = row.response ?: return ""
    val ip = resp.serverIPAddress
    val port = resp.connection
    return if (ip.isBlank()) "" else if (port.isBlank()) ip else "$ip:$port"
}
