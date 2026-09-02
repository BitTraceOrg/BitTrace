package org.bittrace.components

import org.bittrace.ui.Typo
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bittrace.data.NameValuePair
import org.bittrace.data.SettingsStore
import org.bittrace.data.TrafficRow
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.format.FormattedBody
import org.bittrace.proxy.BodySide
import org.bittrace.ui.CellText
import org.bittrace.ui.Format
import org.bittrace.ui.FormatPicker
import org.bittrace.ui.HorizontalSplitter
import org.bittrace.ui.P
import org.bittrace.ui.PaneHeader
import org.bittrace.ui.PillTabs
import org.bittrace.ui.PzText
import org.bittrace.ui.Ribbon
import org.bittrace.ui.RibbonSlice
import org.bittrace.ui.CodeView
import org.bittrace.ui.VScrollbar
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.bittrace.ui.copyToClipboard
import org.bittrace.ui.VerticalSplitter
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder
import org.jetbrains.jewel.ui.component.ToggleableChip

/**
 * Request | Response inspector (DESIGN.md §6.10), with a persisted divider.
 *
 * [stacked] flips the two panes from side-by-side to request-above-response —
 * what the inspector wants when it is docked to the right edge, where height is
 * plentiful and width is not. The divider fraction is shared by both layouts,
 * since it means the same thing along whichever axis is the long one.
 *
 * [showRequest] drops the request pane entirely, for the API client: there the
 * request is the thing you just authored a few inches to the left, so showing
 * it back costs half the width and says nothing new.
 */
@Composable
fun Inspector(
    row: TrafficRow?,
    bodyProvider: (String, BodySide) -> ByteArray?,
    settings: SettingsStore,
    formatters: List<BodyFormatter> = emptyList(),
    stacked: Boolean = false,
    showRequest: Boolean = true,
    /** Right-aligned metadata in the Response header. */
    responseTrailing: (@Composable () -> Unit)? = null,
) {
    var extent by remember { mutableStateOf(0f) }
    val split = settings.settings.requestResponseSplit
    // The vertical splitter reports pixels and the horizontal one Dp, while the
    // measured extent is pixels — convert so both axes move by the same amount.
    val density = LocalDensity.current

    val request: @Composable (Modifier) -> Unit = { modifier ->
        Pane(
            modifier,
            "Request", null, BodySide.REQUEST, row, bodyProvider, formatters,
            tabs = listOf("Overview", "Headers", "Body", "Form", "Cookies", "Raw", "Hex"), default = "Overview",
        )
    }
    val response: @Composable (Modifier) -> Unit = { modifier ->
        Pane(
            modifier,
            "Response", responseTrailing, BodySide.RESPONSE, row, bodyProvider, formatters,
            tabs = listOf("Overview", "Body", "Cookies", "Headers", "Raw", "Hex", "Timing"), default = "Body",
        )
    }

    /** Moves the divider by [delta] px along the inspector's long axis. */
    fun drag(delta: Float) {
        if (extent > 0f) settings.update {
            it.copy(requestResponseSplit = (it.requestResponseSplit + delta / extent).coerceIn(0.15f, 0.85f))
        }
    }

    if (!showRequest) {
        // One pane, no divider — there is nothing to divide it from.
        Box(Modifier.fillMaxSize().background(P.panel)) { response(Modifier.fillMaxSize()) }
        return
    }

    if (stacked) {
        Column(
            Modifier.fillMaxSize().background(P.panel)
                .onGloballyPositioned { extent = it.size.height.toFloat() },
        ) {
            request(Modifier.weight(split))
            HorizontalSplitter { delta -> drag(with(density) { delta.toPx() }) }
            response(Modifier.weight(1f - split))
        }
    } else {
        Row(
            Modifier.fillMaxSize().background(P.panel)
                .onGloballyPositioned { extent = it.size.width.toFloat() },
        ) {
            request(Modifier.weight(split))
            VerticalSplitter { delta -> drag(with(density) { delta.toPx() }) }
            response(Modifier.weight(1f - split))
        }
    }
}

@Composable
private fun Pane(
    modifier: Modifier,
    caption: String,
    /** Right-aligned metadata: the status line, timings, size. */
    trailing: (@Composable () -> Unit)? = null,
    side: BodySide,
    row: TrafficRow?,
    bodyProvider: (String, BodySide) -> ByteArray?,
    formatters: List<BodyFormatter>,
    tabs: List<String>,
    default: String,
) {
    var tab by remember { mutableStateOf(default) }
    // Sticky formatter choice; cleared when a different flow is selected so the
    // default follows each flow's content type.
    var formatterId by remember(row?.id) { mutableStateOf<String?>(null) }
    // Sticky across flows: having asked for the pretty view once, you want it
    // for the next flow too.
    var smartRaw by remember { mutableStateOf(false) }
    Column(modifier.fillMaxHeight()) {
        // The pane title names the pane, so it is a heading rather than one more
        // label in the strip beside it. The header carries no vertical margin of
        // its own, so the strip is exactly as tall as the tabs it holds.
        PaneHeader(title = caption) {
            // Scrolls rather than clipping when the pane is dragged narrow.
            PillTabs(tabs, tab, Modifier.weight(1f)) { tab = it }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }

        if (row == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PzText("Select a flow to inspect", color = P.dim, style = Typo.body)
            }
            return@Column
        }

        // Fixed pane header (URL/status line + MetaGrid), adapting to the side;
        // hidden on the BODY tab to give the body maximum height.
        if (tab != "Body") PaneHeader(row, side)

        // One chip per available formatter, defaulting to the one that claims
        // this flow's content type (falling back to RAW).
        val mime = mimeOf(row, side)
        val active = formatters.firstOrNull { it.id == formatterId }
            ?: formatters.firstOrNull { it.handles(mime) }
            ?: formatters.firstOrNull { it.id == "bittrace.raw" }
            ?: formatters.firstOrNull()
        if (tab == "Body" && formatters.isNotEmpty()) {
            FormatterChips(formatters, active) { formatterId = it.id }
        }

        // The monospace tabs are a code view now, which brings its own gutter,
        // search panel, selection and viewport — so none of the scroll state,
        // find state or scrollbars the pane used to hoist for them survive here.
        // The other tabs are still one scrolling column.
        val vertical = rememberScrollState()
        val monoTab = tab == "Body" || tab == "Raw" || tab == "Hex"

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (monoTab) {
                    // No SelectionContainer here. A lazy list disposes the rows
                    // you scroll past, so a selection spanning them would copy
                    // only the part still on screen — a partial copy that looks
                    // like a whole one. The copy button beside the pane takes
                    // the body from the source instead, which is always all of
                    // it. Selection within a row still works.
                    when (tab) {
                        "Body" -> BodyText(row, side, bodyProvider, active, mime)
                        "Raw" -> Raw(row, side, bodyProvider, smartRaw, active, mime)
                        else -> HexView(row, side, bodyProvider, formatters)
                    }
                } else {
                    Box(Modifier.fillMaxSize().verticalScroll(vertical)) {
                        // Selectable + Ctrl+C copyable, but with the right-click Copy context
                        // menu suppressed (empty representation).
                        CompositionLocalProvider(LocalContextMenuRepresentation provides NoContextMenu) {
                            SelectionContainer {
                                when (tab) {
                                    "Overview" -> FlowOverview(row, side)
                                    "Headers" -> Kv(headersOf(row, side))
                                    "Form" -> WebForm(row, bodyProvider)
                                    "Cookies" -> Cookies(row, side)
                                    "Timing" -> Timing(row)
                                }
                            }
                        }
                    }
                }
                // Only the scrolling column needs one; the code view draws its
                // own.
                if (!monoTab) {
                    VScrollbar(vertical, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                // Floats over the raw text rather than scrolling with it, and
                // sits clear of the scrollbar's 8dp lane.
                if (tab == "Raw") {
                    SmartViewButton(
                        on = smartRaw,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 16.dp),
                    ) { smartRaw = !smartRaw }
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
            PzText(r.method, color = P.info, style = Typo.body, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            PzText("${schemeOf(r.url)}://", color = P.dim, style = Typo.body)
            CellText("$host$path", color = P.text, style = Typo.body)
        }
        MetaGrid(
            listOf(
                Triple("Version", r.httpVersion, P.text),
                Triple("Remote", remoteOf(row).ifBlank { "—" }, P.text),
                Triple("Header bytes", bytesStr(r.headersSize), P.text),
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
                PzText("pending", color = P.dim, style = Typo.body)
            } else {
                PzText(
                    if (resp.error) "Conn reset" else "${resp.response.status} ${resp.response.statusText}".trim(),
                    color = sColor, style = Typo.body, weight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                PzText(mimeOf(row), color = P.dim, style = Typo.label)
                Spacer(Modifier.weight(1f))
                PzText("${resp.time.toLong()} ms · ${bytesStr(resp.response.bodySize)}", color = P.dim, style = Typo.label)
            }
        }
        MetaGrid(
            listOf(
                Triple("Version", resp?.response?.httpVersion ?: "—", P.text),
                Triple("Status", resp?.let { if (it.error) "ERR" else it.response.status.toString() } ?: "—", sColor),
                Triple("CONTENT-LENGTH", bytesStr(resp?.response?.bodySize), P.text),
                Triple("Server", headerValue(row, BodySide.RESPONSE, "server") ?: "—", P.text),
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
                PzText(label, color = P.faint, style = Typo.micro, family = P.Ui)
                CellText(value, color = color, style = Typo.label)
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

        Section("Endpoint", first = true)
        KvRow("url", r.url, P.info)
        KvRow("method", r.method, P.info)
        KvRow("scheme", schemeOf(r.url), if (https) P.ok else P.warn)
        KvRow("host", host)
        KvRow("path", path.ifBlank { "/" })

        Section("Request")
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

        Section("Response", first = true)
        KvRow("status", if (resp == null) "pending" else "${resp.response.status} ${resp.response.statusText}".trim(), sColor)
        KvRow("remote addr", remoteOf(row).ifBlank { "—" })
        KvRow("remote ver", resp?.response?.httpVersion ?: "—")
        KvRow("content type", mimeOf(row))
        if (redirect != null) KvRow("redirect", redirect, P.info)

        Section("Transfer")
        KvRow("encoding", headerValue(row, BodySide.RESPONSE, "content-encoding") ?: "identity")
        KvRow("cache", headerValue(row, BodySide.RESPONSE, "cache-control") ?: "—")

        Section("Security")
        KvRow("tls", if (https) tlsText(row) else "none (cleartext)", if (https) P.ok else P.warn)

        Section("Timing")
        KvRow("started", startStr(row).ifBlank { "—" })
        KvRow("ttfb", row.response?.timings?.wait?.takeIf { it >= 0 }?.let { "${it.toLong()} ms" } ?: "—")
        KvRow("duration", if (resp != null) "${resp.time.toLong()} ms" else "—", if (resp?.error == true) P.err else P.text)
    }
}

@Composable
private fun PhaseRibbon(row: TrafficRow) {
    // Same phase set and colours the table's compact ribbon uses.
    val phases = phasesOf(row)
    if (phases.isEmpty()) return
    val total = (row.response?.time ?: phases.sumOf { it.ms }).toLong()

    Ribbon(
        phases.map { RibbonSlice(it.ms.toFloat(), it.color, "${it.name} ${it.ms.toLong()}ms") },
        total = "total $total ms",
    )
}

/**
 * The same bar over a different quantity: the transfer split into header versus
 * body bytes. Shown by both the request and the response overview.
 */
@Composable
private fun SizeRibbon(headerBytes: Long, bodyBytes: Long) {
    val h = headerBytes.coerceAtLeast(0)
    val b = bodyBytes.coerceAtLeast(0)
    Ribbon(
        listOf(
            RibbonSlice(h.toFloat(), P.info, "headers ${bytesStr(headerBytes)}"),
            RibbonSlice(b.toFloat(), P.ok, "body ${bytesStr(bodyBytes)}"),
        ),
        total = "total ${bytesStr(h + b)}",
    )
}

private fun schemeOf(url: String): String = url.substringBefore("://", "")

private fun mimeOf(row: TrafficRow, side: BodySide = BodySide.RESPONSE): String =
    if (side == BodySide.REQUEST) {
        row.completeRequest?.request?.postData?.mimeType?.takeIf { it.isNotBlank() } ?: "—"
    } else {
        row.completeResponse?.response?.content?.mimeType?.takeIf { it.isNotBlank() }
            ?: row.completeRequest?.request?.postData?.mimeType?.takeIf { it.isNotBlank() }
            ?: "—"
    }

/** One header off either half of the message, or null if it is not there. */
private fun headerValue(row: TrafficRow, side: BodySide, name: String): String? {
    val headers = when (side) {
        BodySide.REQUEST -> row.completeRequest?.request?.headers
        BodySide.RESPONSE -> row.completeResponse?.response?.headers
    }
    return headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
}

@Composable
private fun Timing(row: TrafficRow) {
    val t = row.response?.timings
    if (t == null) { Pad("no timings yet"); return }
    val phases = phasesOf(row)

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Section("Waterfall", first = true)
        if (phases.isEmpty()) {
            Pad("no phase timings for this flow")
        } else {
            // Each phase gets its own lane, offset by everything before it, so
            // the eye reads where the time actually went rather than comparing
            // seven numbers. Phases stack sequentially — the same simplification
            // the table's ribbon makes.
            val total = phases.sumOf { it.ms }
            var offset = 0.0
            phases.forEach { phase ->
                WaterfallLane(phase, offset, total)
                offset += phase.ms
            }
        }

        Section("Timing")
        listOf(
            "blocked" to t.blocked, "dns" to t.dns, "connect" to t.connect,
            "ssl" to t.ssl, "send" to t.send, "wait" to t.wait, "receive" to t.receive,
        ).forEach { (k, v) -> KvRow(k, if (v < 0) "—" else "${v.toLong()} ms", capitalize = false) }
        KvRow("total", "${(row.response?.time ?: 0.0).toLong()} ms", P.accent, capitalize = false)
    }
}

/**
 * One phase of the waterfall: its name, a bar starting where the phase starts,
 * and its duration.
 *
 * The bar is laid out as three weights — lead-in, bar, tail — because a weight
 * must be positive, so each part is only added when it has width. A very short
 * phase still gets a visible sliver rather than collapsing to nothing.
 */
@Composable
private fun WaterfallLane(phase: Phase, offset: Double, total: Double) {
    val lead = (offset / total).toFloat()
    val width = (phase.ms / total).toFloat().coerceAtLeast(0.01f)
    val tail = (1f - lead - width).coerceAtLeast(0f)

    Row(
        Modifier.fillMaxWidth().topBorder(P.line2).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PzText(phase.name, color = P.faint, style = Typo.label, modifier = Modifier.width(64.dp), softWrap = false)
        Row(Modifier.weight(1f).height(10.dp).background(P.bg)) {
            if (lead > 0f) Spacer(Modifier.weight(lead))
            Box(Modifier.weight(width).fillMaxHeight().background(phase.color))
            if (tail > 0f) Spacer(Modifier.weight(tail))
        }
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            PzText("${phase.ms.toLong()} ms", color = P.text, style = Typo.label, softWrap = false)
        }
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

/**
 * The submitted web form: query-string parameters plus the decoded fields of a
 * `x-www-form-urlencoded` or `multipart/form-data` body. Read-only, like the
 * rest of the inspector.
 */
@Composable
private fun WebForm(row: TrafficRow, bodyProvider: (String, BodySide) -> ByteArray?) {
    val query = row.request.request.queryString
    val contentType = mimeOf(row, BodySide.REQUEST).takeIf { it != "—" }
        ?: headerValue(row, BodySide.REQUEST, "content-type")
        ?: ""
    val hasBody = row.request.request.bodySize != 0L
    val bytes = if (hasBody && isFormBody(contentType)) bodyProvider(row.id, BodySide.REQUEST) else null
    val fields = remember(row.id, contentType, bytes?.size) { parseForm(bytes, contentType) }

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Section("Query string", first = true)
        if (query.isEmpty()) {
            Pad("no query parameters")
        } else {
            query.forEach { KvRow(it.name, it.value, P.text, keyColor = P.accent, capitalize = false) }
        }

        Section("Form fields")
        when {
            !hasBody -> Pad("request has no body")
            !isFormBody(contentType) ->
                Pad("body is not form-encoded (${contentType.ifBlank { "unknown type" }})")
            bytes == null -> Pad("body not cached for this flow")
            fields.isNullOrEmpty() -> Pad("no fields")
            else -> fields.forEach { f ->
                val value = if (f.fileName == null) f.value else "⟨file⟩ ${f.fileName} · ${bytesStr(f.size)}" +
                    (f.contentType?.let { " · $it" } ?: "")
                KvRow(
                    f.name.ifBlank { "—" },
                    value,
                    if (f.fileName == null) P.text else P.info,
                    keyColor = P.accent,
                    capitalize = false,
                )
            }
        }
    }
}

@Composable
private fun BodyText(
    row: TrafficRow,
    side: BodySide,
    bodyProvider: (String, BodySide) -> ByteArray?,
    formatter: BodyFormatter?,
    mime: String,
) {
    val declared = if (side == BodySide.REQUEST) row.request.request.bodySize else row.response?.response?.bodySize
    if (declared == 0L) { Pad("body: none"); return }
    val bytes = bodyProvider(row.id, side)
    if (bytes == null) { Pad("body not cached for this flow"); return }
    if (formatter == null) { Mono(String(bytes, Charsets.UTF_8), mime); return }

    val body = formattedBody(row.id, side, bytes, formatter, mime)
    if (body == null) { Pad("formatting ${bytesStr(bytes.size.toLong())}…"); return }
    Mono(body.text, mime)
}

/**
 * Runs [formatter] over [bytes] off the UI thread, returning null while it
 * works.
 *
 * Formatting a large body runs a full lexer over it, so it never happens in
 * composition, which would drop frames. Switching flow or formatter discards
 * the previous job's result. Keyed on the byte *count* rather than the array:
 * arrays compare by identity, and the provider can hand back a new reference
 * per composition, which would restart the job every frame.
 */
@Composable
private fun formattedBody(
    id: String,
    side: BodySide,
    bytes: ByteArray,
    formatter: BodyFormatter,
    mime: String,
): FormattedBody? {
    val formatted by produceState<FormattedBody?>(null, id, side, formatter.id, mime, bytes.size) {
        value = null
        value = withContext(Dispatchers.Default) {
            // A third-party formatter must never take the inspector down with it.
            runCatching { formatter.highlight(bytes, mime) }
                .getOrElse {
                    FormattedBody("Formatter '${formatter.name}' failed: ${it.message ?: it::class.simpleName}")
                }
        }
    }
    return formatted
}

/** The formatter picker shown above the BODY tab — one chip per formatter. */
@Composable
private fun FormatterChips(
    formatters: List<BodyFormatter>,
    active: BodyFormatter?,
    onPick: (BodyFormatter) -> Unit,
) {
    FormatPicker(
        formats = formatters.map { Format(it.id, it.name) },
        selected = active?.id,
        onSelect = { picked -> formatters.firstOrNull { it.id == picked.id }?.let(onPick) },
    )
}

/**
 * The message exactly as it went over the wire: start line, headers, blank
 * line, body.
 *
 * With [smart] on, the same message is shown pretty-printed and coloured — the
 * head is highlighted here (header names against their values) and the body is
 * run through the active formatter, exactly as the BODY tab does it. Off, it is
 * the untouched bytes, which is the point of a raw view.
 */
@Composable
private fun Raw(
    row: TrafficRow,
    side: BodySide,
    bodyProvider: (String, BodySide) -> ByteArray?,
    smart: Boolean,
    formatter: BodyFormatter?,
    mime: String,
) {
    val head = StringBuilder()
    if (side == BodySide.REQUEST) {
        val r = row.request.request
        head.append("${r.method} ${r.url} ${r.httpVersion}\n")
        row.completeRequest?.request?.headers?.forEach { head.append("${it.name}: ${it.value}\n") }
    } else {
        val h = row.response?.response
        if (h == null) { Pad("no response yet"); return }
        head.append("${h.httpVersion} ${h.status} ${h.statusText}\n")
        row.completeResponse?.response?.headers?.forEach { head.append("${it.name}: ${it.value}\n") }
    }

    val bytes = bodyProvider(row.id, side)

    if (!smart) {
        val sb = StringBuilder(head)
        sb.append('\n')
        bytes?.let { sb.append(String(it, Charsets.UTF_8)) }
        Mono(sb.toString(), mime)
        return
    }

    val body = if (bytes == null || formatter == null) null else formattedBody(row.id, side, bytes, formatter, mime)
    if (bytes != null && formatter != null && body == null) {
        Pad("formatting ${bytesStr(bytes.size.toLong())}…")
        return
    }

    // Head and body concatenated as plain text. The colouring that used to
    // happen here — header names against their values, then the formatter's own
    // spans — is the code view's job now, and doing it twice would mean styling
    // a string only to throw the styling away.
    val text = remember(head.toString(), body) {
        buildString {
            append(head)
            append('\n')
            body?.let { append(it.text) }
        }
    }
    Mono(text, mime)
}

/**
 * The RAW tab's pretty-view toggle: floats over the text, translucent so the
 * line it covers stays readable.
 */
@Composable
private fun SmartViewButton(on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    // A real toggle rather than a clickable box: it reports its checked state
    // and carries hover, press and focus. It loses the translucency the old one
    // used to keep the covered line readable — an opaque chip over a fixed
    // corner is the trade, and it reads more clearly for it.
    ToggleableChip(checked = on, onClick = { onToggle() }, modifier = modifier) {
        PzText("Smart view", style = Typo.micro, family = P.Ui, softWrap = false)
    }
}

@Composable
private fun HexView(
    row: TrafficRow,
    side: BodySide,
    bodyProvider: (String, BodySide) -> ByteArray?,
    formatters: List<BodyFormatter>,
) {
    val bytes = bodyProvider(row.id, side)
    if (bytes == null || bytes.isEmpty()) { Pad("body: none"); return }

    val hex = formatters.firstOrNull { it.id == HEX_FORMATTER }
    if (hex == null) { Pad("the hex formatter ($HEX_FORMATTER) is not loaded"); return }

    // Off the UI thread and cached, like every other body. Shown as plain text:
    // a hex dump is columns of digits, and no language describes it — the
    // formatter's own offset-column highlighting went with the move to a code
    // view, and colouring a dump by guessing at a language would be worse than
    // leaving it alone.
    val body = formattedBody(row.id, side, bytes, hex, mimeOf(row, side))
    if (body == null) { Pad("formatting ${bytesStr(bytes.size.toLong())}…"); return }
    Mono(body.text, contentType = "")
}

/**
 * The bundled hex formatter, asked for by id.
 *
 * The HEX tab used to build its own dump, byte-for-byte identical to what this
 * formatter produces — so the app shipped an extension point, then went around
 * it, and the tab quietly lost the offset-column highlighting the plugin was
 * already generating. It is a plugin, so it can be absent; the tab says so
 * rather than silently falling back to something that is not a hex dump.
 */
private const val HEX_FORMATTER = "bittrace.hex"

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
private fun Section(title: String, first: Boolean = false) {
    // A group heading, so it outranks the rows under it: same size, more weight
    // (DESIGN.MD §9.8 puts detail-group titles at Inter 12 600). It used to be a
    // step *smaller* than the rows it headed, which read as a caption rather
    // than a heading.
    //
    // Rules are drawn top-edge-only, here and in [KvRow] and [Pad] — see the
    // note on KvRow. So a heading owns the rule above it and inherits the one
    // below from whatever follows, which is how it ends up ruled on both sides
    // without any boundary being drawn twice. [first] drops the top rule where
    // the heading opens a column and the pane header above already drew one.
    Row(
        Modifier.fillMaxWidth().background(P.head)
            .then(if (first) Modifier else Modifier.topBorder(P.line))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        PzText(title, color = P.dim, style = Typo.label, family = P.Ui, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun KvRow(
    key: String,
    value: String,
    valueColor: Color = P.text,
    keyColor: Color = P.faint,
    capitalize: Boolean = true,
) {
    // Every row in a detail list draws the rule at its *own top edge*, never at
    // its bottom. A boundary between two rows belongs to exactly one of them,
    // and picking the top consistently is what stops a heading's bottom rule
    // and the next row's top rule landing as a 2px line.
    Row(
        Modifier.fillMaxWidth().topBorder(P.line2).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val label = if (capitalize) key.replaceFirstChar { it.uppercase() } else key
        PzText(label, color = keyColor, style = Typo.label, modifier = Modifier.width(120.dp))
        Spacer(Modifier.width(10.dp))
        PzText(value, color = valueColor, style = Typo.label)
    }
}

/**
 * A captured body, in the app's read-only code view.
 *
 * [contentType] picks the language, so a JSON response is coloured by the same
 * parser that colours a JSON body being written in the API client — one
 * highlighter for the app rather than one for reading and one for writing.
 *
 * The formatter still runs: pretty-printing a minified body is the part of it
 * that mattered, and it happens before this. What is no longer used is
 * `BodyFormatter.highlight` — a formatter that returned spans now has them
 * ignored on these tabs, since the language does that job. The method stays on
 * the plugin interface, because removing it would break every external
 * formatter for no gain.
 */
@Composable
private fun Mono(text: String, contentType: String) {
    CodeView(text, Modifier.fillMaxSize(), contentType)
}



@Composable
private fun Pad(text: String) {
    // Carries the same top rule as a row, so a heading with nothing under it
    // still reads as ruled on both sides.
    Box(Modifier.fillMaxWidth().topBorder(P.line2).padding(16.dp), contentAlignment = Alignment.Center) {
        PzText(text, color = P.dim, style = Typo.label)
    }
}

private fun remoteOf(row: TrafficRow): String {
    val resp = row.response ?: return ""
    val ip = resp.serverIPAddress
    val port = resp.connection
    return if (ip.isBlank()) "" else if (port.isBlank()) ip else "$ip:$port"
}
