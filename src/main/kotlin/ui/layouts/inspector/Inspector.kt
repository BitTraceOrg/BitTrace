package org.bittrace.ui.layouts.inspector

import org.bittrace.ui.bytesStr
import org.bittrace.ui.components.isFormBody
import org.bittrace.ui.components.parseForm
import org.bittrace.ui.hostPath
import org.bittrace.ui.layouts.inspector.components.Phase
import org.bittrace.ui.layouts.inspector.components.WebSocketTranscript
import org.bittrace.ui.layouts.inspector.components.phasesOf
import org.bittrace.ui.startStr
import org.bittrace.ui.statusOf
import org.bittrace.ui.tlsText
import androidx.compose.foundation.layout.RowScope
import org.bittrace.ui.Typo
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.Format
import org.bittrace.ui.components.FormatPicker
import org.bittrace.ui.components.HorizontalSplitter
import org.bittrace.ui.P
import org.bittrace.ui.components.TabContentSwitcher
import org.bittrace.ui.components.TabLabel
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.Ribbon
import org.bittrace.ui.components.RibbonSlice
import org.bittrace.ui.components.CodeView
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.components.VerticalSplitter
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder

/**
 * Request | Response inspector, with a persisted divider.
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
            tabs = listOf("Overview", "Raw", "Headers", "Cookies", "Body", "Form"), default = "Overview",
        )
    }
    val response: @Composable (Modifier) -> Unit = { modifier ->
        // On a WebSocket, Messages *replaces* Body rather than joining it. A
        // `101` cannot carry a body — RFC 9112 ends a 1xx response at the blank
        // line after its headers, and everything after that belongs to the
        // protocol that was switched to — so Body on one of these rows is
        // permanently empty, and the messages are the content that took its
        // place on the wire. They take its place in the strip too, and its
        // position, so the tab under the pointer is the payload either way.
        val socket = row?.isWebSocket == true
        val payloadTab = if (socket) "Messages" else "Body"
        Pane(
            modifier,
            "Response", responseTrailing, BodySide.RESPONSE, row, bodyProvider, formatters,
            tabs = listOf("Overview", "Raw", "Headers", "Cookies", payloadTab, "Timing"),
            default = payloadTab,
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
    var selected by remember { mutableStateOf(default) }
    // The choice is remembered across flows, but the tabs on offer are not the
    // same for every flow — a WebSocket has Messages and nothing else does. A
    // remembered tab this flow does not have falls back rather than showing an
    // empty pane.
    val tab = if (selected in tabs) selected else default
    // Sticky formatter choice; cleared when a different flow is selected so the
    // default follows each flow's content type.
    var formatterId by remember(row?.id) { mutableStateOf<String?>(null) }
    // The tabs are views over one flow, not independent pages, so this is the
    // shared-body form: one code view below, fed by whichever tab is showing,
    // rather than one per tab. The pane title names the pane, so it is a
    // heading rather than one more label in the strip beside it.
    TabContentSwitcher(
        tabs = tabs.map { TabLabel(it) },
        selected = tab,
        modifier = modifier.fillMaxHeight(),
        title = caption,
        trailing = trailing?.let { content -> { content() } },
        onSelect = { selected = it },
    ) {
        if (row == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PzText("Select a flow to inspect", color = P.dim, style = Typo.body)
            }
            return@TabContentSwitcher
        }

        // Fixed pane header (URL/status line + MetaGrid), adapting to the side;
        // hidden on the payload tab to give the payload maximum height. That is
        // Messages on a WebSocket, which stands in Body's place there and gets
        // the height for the same reason — it is a long list, and it carries a
        // summary line of its own.
        if (tab != "Body" && tab != "Messages") SideHeader(row, side)

        // One chip per available formatter, defaulting to the one that claims
        // this flow's content type (falling back to RAW).
        val mime = mimeOf(row, side)
        val active = formatters.firstOrNull { it.id == formatterId }
            ?: formatters.firstOrNull { it.handles(mime) }
            ?: formatters.firstOrNull { it.id == "bittrace.raw" }
            ?: formatters.firstOrNull()
        // An image body earns a chip of its own beside the formatters, and is
        // what the tab opens on: the picture is what you came to see. It is a
        // chip rather than a formatter because a formatter returns text, and
        // the whole point of this one is that it does not.
        //
        // `formatterId` resets per flow, so "nothing picked yet" reliably means
        // "just opened this flow" — which is when the image should win.
        val decodable = isPicture(mime)
        val showsPicture = decodable && (formatterId == null || formatterId == IMAGE_FORMAT_ID)
        if (tab == "Body" && (formatters.isNotEmpty() || decodable)) {
            FormatterChips(
                formats = buildList {
                    if (decodable) add(Format(IMAGE_FORMAT_ID, "Image"))
                    formatters.forEach { add(Format(it.id, it.name)) }
                },
                selected = if (showsPicture) IMAGE_FORMAT_ID else active?.id,
                onSelect = { formatterId = it },
            )
        }

        // The monospace tabs are a code view now, which brings its own gutter,
        // search panel, selection and viewport — so none of the scroll state,
        // find state or scrollbars the pane used to hoist for them survive here.
        // The other tabs are still one scrolling column.
        val vertical = rememberScrollState()
        val monoTab = tab == "Body" || tab == "Raw"

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == "Messages") {
                    // Outside the scrolling column below on purpose: the
                    // transcript is a lazy list, and a lazy list inside a
                    // parent that scrolls the same axis has no height to
                    // measure against.
                    WebSocketTranscript(row)
                } else if (monoTab) {
                    // No SelectionContainer here. A lazy list disposes the rows
                    // you scroll past, so a selection spanning them would copy
                    // only the part still on screen — a partial copy that looks
                    // like a whole one. The copy button beside the pane takes
                    // the body from the source instead, which is always all of
                    // it. Selection within a row still works.
                    //
                    // Both tabs decide *what* to show and share the one view
                    // that shows it, rather than composing a code view each —
                    // which is what keeps a change of formatter, or of the body
                    // underneath, from rebuilding the editor. Body → Raw does
                    // rebuild it, because a plain view is a different editor
                    // and not a setting on this one.
                    val content = if (tab == "Body") {
                        bodyContent(row, side, bodyProvider, active, mime, showsPicture)
                    } else {
                        rawContent(row, side, bodyProvider)
                    }
                    when (content) {
                        is MonoContent.Note -> Pad(content.text)
                        // RAW is shown as text and nothing else: no language, no
                        // gutter. BODY keeps both — that tab is the one that
                        // exists to make a body readable.
                        is MonoContent.Code ->
                            Mono(content.text, content.contentType, plain = tab == "Raw")
                        // Only BODY ever produces one: RAW is the tab for
                        // seeing the bytes as they came, and a picture there
                        // would be the one place in the app that refused to
                        // show you what was actually on the wire.
                        is MonoContent.Picture ->
                            Picture(content.bytes, content.contentType)
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
                if (!monoTab && tab != "Messages") {
                    VScrollbar(vertical, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

// --- content ---

// --- pane header (adapts to side + available info) ---

/**
 * The line at the top of a half, whichever half it is.
 *
 * The two branches below had the same modifier chain written out twice, twenty
 * lines apart inside one function — close enough to look deliberate and far
 * enough apart to drift. Only what goes *in* the row differs by side.
 */
@Composable
private fun TitleRow(content: @Composable RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().background(P.bg).bottomBorder(P.line)
        .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
)

@Composable
private fun SideHeader(row: TrafficRow, side: BodySide) {
    if (side == BodySide.REQUEST) {
        val r = row.request.request
        val (host, path) = hostPath(r.url)
        val scheme = schemeOf(r.url)
        TitleRow {
            PzText(r.method, color = P.info, style = Typo.body, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            // A CONNECT target is an authority, not a URL, so there is no
            // scheme to show — and inventing one would name a protocol the
            // tunnel has not carried yet.
            if (scheme.isNotEmpty()) PzText("$scheme://", color = P.dim, style = Typo.body)
            CellText("$host$path", color = P.text, style = Typo.body)
        }
        MetaGrid(
            buildList {
                add(Triple("Version", r.httpVersion, P.text))
                add(Triple("Remote", remoteOf(row).ifBlank { "—" }, P.text))
                if (row.isConnect) add(Triple("Client", row.clientAddress.ifBlank { "—" }, P.text))
                add(Triple("Header bytes", bytesStr(r.headersSize), P.text))
                add(Triple("TLS", tlsText(row), if (tlsText(row) == "—") P.faint else P.ok))
            },
        )
    } else {
        val resp = row.response
        val (_, sColor) = statusOf(row)
        TitleRow {
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
                PzText("${resp.time.toLong()} ms · ${bytesStr(row.responseBodySize)}", color = P.dim, style = Typo.label)
            }
        }
        MetaGrid(
            listOf(
                Triple("Version", resp?.response?.httpVersion ?: "—", P.text),
                Triple("Status", resp?.let { if (it.error) "ERR" else it.response.status.toString() } ?: "—", sColor),
                Triple("Body bytes", bytesStr(row.responseBodySize), P.text),
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
        SizeRibbon(r.headersSize, row.requestBodySize)

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
        SizeRibbon(resp?.response?.headersSize ?: 0, row.responseBodySize ?: 0)

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
    val hasBody = row.requestBodySize != 0L
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

/**
 * What one of the monospace tabs wants on screen.
 *
 * The tabs used to compose their own view, which is why they hand back a value
 * now: the pane renders whichever one it gets from a single call site, so
 * switching tabs reaches the code view that is already there rather than a new
 * one.
 */
private sealed interface MonoContent {
    /** Text for the code view, with the content type that picks its language. */
    data class Code(val text: String, val contentType: String) : MonoContent

    /**
     * An image body, to be looked at rather than read.
     *
     * Not a data class: it carries the raw bytes, and an array's `equals` is
     * identity anyway — a generated one would promise a comparison it does not
     * make.
     */
    class Picture(val bytes: ByteArray, val contentType: String) : MonoContent

    /** A line in place of the text — no body, not cached, still formatting. */
    data class Note(val text: String) : MonoContent
}

/**
 * Whether a body is a picture this can draw.
 *
 * SVG is deliberately not one. Skia decodes raster formats from bytes and has
 * no SVG decoder behind `makeFromEncoded`, and an SVG is XML that the code view
 * shows perfectly well — so it stays text rather than becoming a broken image.
 */
private fun isPicture(mime: String): Boolean =
    mime.startsWith("image/", ignoreCase = true) && !mime.contains("svg", ignoreCase = true)

@Composable
private fun bodyContent(
    row: TrafficRow,
    side: BodySide,
    bodyProvider: (String, BodySide) -> ByteArray?,
    formatter: BodyFormatter?,
    mime: String,
    /** The Image chip is the one selected, so hand back the bytes to draw. */
    asPicture: Boolean,
): MonoContent {
    // Read before anything returns, so this composable is subscribed to it
    // whichever branch it takes: a body arriving now is one whose every other
    // input is still unchanged, and nothing else here would bring the new bytes
    // on screen.
    val streaming = row.streamedBytes(side == BodySide.REQUEST)

    val declared = if (side == BodySide.REQUEST) row.requestBodySize else row.responseBodySize
    if (declared == 0L && streaming == 0L) return MonoContent.Note("body: none")
    val bytes = bodyProvider(row.id, side)
        ?: return MonoContent.Note(
            if (streaming > 0L) "receiving…" else "body not cached for this flow",
        )
    // Before the formatter, and before any attempt to read the bytes as text:
    // a PNG decoded as UTF-8 is a screenful of replacement characters, which is
    // what the other chips are for if that is genuinely what you want to see.
    if (asPicture) {
        // Half an image decodes to nothing, so a body still arriving says so
        // rather than flickering between failures as the chunks land.
        return if (streaming > 0L) {
            MonoContent.Note("receiving image…")
        } else {
            MonoContent.Picture(bytes, mime)
        }
    }

    // A body still arriving is shown as it is. Running a formatter over a
    // fragment would reformat the whole of it on every chunk, and fail on the
    // half-written last record anyway — the formatter takes over once the
    // stream closes and the finished body replaces this.
    if (formatter == null || streaming > 0L) return MonoContent.Code(String(bytes, Charsets.UTF_8), mime)

    val body = formattedBody(row.id, side, bytes, formatter, mime)
        ?: return MonoContent.Note("formatting ${bytesStr(bytes.size.toLong())}…")
    return MonoContent.Code(body.text, mime)
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

/**
 * The picker above the BODY tab — one chip per formatter, plus Image when the
 * body is one.
 *
 * Takes prepared [Format]s rather than formatters, because not every chip is a
 * formatter any more: the caller decides what is on offer and reports back the
 * id that was chosen.
 */
@Composable
private fun FormatterChips(formats: List<Format>, selected: String?, onSelect: (String) -> Unit) {
    FormatPicker(formats = formats, selected = selected, onSelect = { onSelect(it.id) })
}

/**
 * The Image chip's id.
 *
 * Shaped like a formatter id and deliberately not one — nothing registers it,
 * so every lookup into `formatters` misses and falls through to the normal
 * default, which is exactly what should happen on a flow that is not an image.
 */
private const val IMAGE_FORMAT_ID = "bittrace.image"

/**
 * The message exactly as it went over the wire: start line, headers, blank
 * line, body.
 *
 * Nothing is formatted, coloured or counted. This tab used to carry a "smart
 * view" toggle that pretty-printed the body and coloured the head, which made
 * it a second BODY tab with the headers stapled on — and left the one view
 * that promises to have done nothing to the bytes as the one view with a
 * setting for how much had been done to them.
 */
@Composable
private fun rawContent(
    row: TrafficRow,
    side: BodySide,
    bodyProvider: (String, BodySide) -> ByteArray?,
): MonoContent {
    // Subscribes this view to a body still arriving; see [bodyContent].
    row.streamedBytes(side == BodySide.REQUEST)

    val head = StringBuilder()
    if (side == BodySide.REQUEST) {
        val r = row.request.request
        head.append("${r.method} ${r.url} ${r.httpVersion}\n")
        row.completeRequest?.request?.headers?.forEach { head.append("${it.name}: ${it.value}\n") }
    } else {
        val h = row.response?.response ?: return MonoContent.Note("no response yet")
        head.append("${h.httpVersion} ${h.status} ${h.statusText}\n")
        row.completeResponse?.response?.headers?.forEach { head.append("${it.name}: ${it.value}\n") }
    }

    // The blank line that separates a message's head from its body.
    head.append('\n')
    bodyProvider(row.id, side)?.let { head.append(String(it, Charsets.UTF_8)) }
    // No content type: the view this feeds has no language to pick with one.
    return MonoContent.Code(head.toString(), "")
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
private fun Section(title: String, first: Boolean = false) {
    // A group heading, so it outranks the rows under it: same size, more weight
    // (detail-group titles are Inter 12 600). It used to be a
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
private fun Mono(text: String, contentType: String, plain: Boolean = false) {
    CodeView(text, Modifier.fillMaxSize(), contentType, plain = plain)
}

/**
 * An image body, drawn.
 *
 * Decoded off the UI thread for the reason formatting is: a few megapixels of
 * PNG is real work, and doing it in composition drops the frame that was
 * supposed to show the flow you just clicked.
 *
 * Scaled with [ContentScale.Inside], so a large screenshot shrinks to fit and a
 * 16px favicon stays 16px. Blowing a favicon up to fill the pane would say it
 * was something it is not, and the caption below gives the real size either
 * way.
 */
@Composable
private fun Picture(bytes: ByteArray, mime: String) {
    val decoded by produceState<Result<ImageBitmap>?>(null, bytes) {
        value = withContext(Dispatchers.Default) {
            runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
        }
    }

    val result = decoded
    val image = result?.getOrNull()
    when {
        result == null -> Pad("decoding ${bytesStr(bytes.size.toLong())}…")
        image == null -> Pad("this image could not be decoded (${mime.ifBlank { "unknown type" }})")
        else -> Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "Response body, as an image",
                    contentScale = ContentScale.Inside,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The numbers the picture cannot show: what it really measures, and
            // what it cost to send.
            Pad("${image.width} × ${image.height} · $mime · ${bytesStr(bytes.size.toLong())}")
        }
    }
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
