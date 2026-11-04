package org.bittrace.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.bittrace.proxy.BreakpointResume
import org.bittrace.rules.BreakpointQueue
import org.bittrace.rules.HeaderText
import org.bittrace.rules.PausedFlow
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.CodeEditor
import org.bittrace.ui.components.EmptyState
import org.bittrace.ui.components.FormField
import org.bittrace.ui.components.FormNote
import org.bittrace.ui.components.GhostButton
import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.TextArea
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.leftBorder
import org.bittrace.ui.rightBorder

/**
 * Flows held at a breakpoint, and the editor that lets each one go.
 *
 * The message is edited as text — method and URL (or status), headers one per
 * line, body in the same editor the Forge uses — because that is what is about
 * to cross the wire, and pasting headers in from somewhere else should work.
 *
 * Each pause shows its countdown: the sidecar lets a flow go on unchanged when
 * nobody answers, and an editor that silently stopped mattering half-way
 * through an edit would be worse than one that says it is about to.
 */
@Composable
fun BreakpointTool(queue: BreakpointQueue) {
    val paused = queue.paused
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    val current = paused.firstOrNull { it.key == selected } ?: paused.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(P.chrome).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PzText(
                if (paused.isEmpty()) "Nothing paused" else "${paused.size} paused",
                color = P.dim, style = Typo.label, family = P.Ui,
            )
            Spacer(Modifier.weight(1f))
            GhostButton("Continue all", enabled = paused.isNotEmpty()) { queue.continueAll() }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(P.panel).rightBorder(P.line)) {
                paused.forEach { flow ->
                    val on = flow.key == current?.key
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (on) P.sel else P.panel)
                            .then(if (on) Modifier.leftBorder(P.accent, 2.dp) else Modifier)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { selected = flow.key }
                            .bottomBorder(P.line)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        CellText(
                            "${if (flow.isResponse) "Response" else "Request"} · ${flow.hit.ruleName}",
                            color = if (flow.isResponse) P.info else P.send, style = Typo.caption, family = P.Ui,
                        )
                        CellText("${flow.hit.request.method} ${flow.hit.request.url}", color = P.text, style = Typo.label)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (current == null) {
                    EmptyState(
                        "Flows matching a breakpoint rule stop here until you send them on.",
                        centred = true,
                    )
                } else {
                    PausedEditor(queue, current)
                }
            }
        }
    }
}

/** Bodies past this are shown but not editable: the editor is not a hex tool. */
private const val EDITABLE_BODY = 2 * 1024 * 1024

@Composable
private fun PausedEditor(queue: BreakpointQueue, flow: PausedFlow) {
    val hit = flow.hit
    val response = hit.response
    val originalHeaders = HeaderText.format(if (response != null) response.headers else hit.request.headers)
    val contentType = (if (response != null) response.headers else hit.request.headers)
        .firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value.orEmpty()
    val bodyText = remember(flow) { decodeText(flow.body) }

    var method by remember(flow) { mutableStateOf(hit.request.method) }
    var url by remember(flow) { mutableStateOf(hit.request.url) }
    var status by remember(flow) { mutableStateOf(response?.status?.toString().orEmpty()) }
    var reason by remember(flow) { mutableStateOf(response?.statusText.orEmpty()) }
    var headers by remember(flow) { mutableStateOf(originalHeaders) }
    var body by remember(flow) { mutableStateOf(bodyText.orEmpty()) }
    var problem by remember(flow) { mutableStateOf<String?>(null) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(flow) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((flow.deadlineMillis - now) / 1000).coerceAtLeast(0)

    fun send() {
        val bodyChanged = bodyText != null && body != bodyText
        val headersChanged = headers != originalHeaders
        val answer = if (response == null) {
            val changed = method != hit.request.method || url != hit.request.url || headersChanged || bodyChanged
            BreakpointResume(
                hit.id, hit.side, if (changed) "edit" else "continue",
                request = BreakpointResume.RequestEdit(
                    method = method.trim().takeIf { it != hit.request.method },
                    url = url.trim().takeIf { it != hit.request.url },
                    headers = if (headersChanged) HeaderText.parse(headers) else null,
                ).takeIf { changed },
                replaceBody = bodyChanged,
            )
        } else {
            val code = status.trim().toIntOrNull()
            if (code == null || code !in 100..999) {
                problem = "Status must be a code like 200"
                return
            }
            val changed = code != response.status || reason != response.statusText || headersChanged || bodyChanged
            BreakpointResume(
                hit.id, hit.side, if (changed) "edit" else "continue",
                response = BreakpointResume.ResponseEdit(
                    status = code.takeIf { it != response.status || reason != response.statusText },
                    statusText = reason.takeIf { it != response.statusText || code != response.status },
                    headers = if (headersChanged) HeaderText.parse(headers) else null,
                ).takeIf { changed },
                replaceBody = bodyChanged,
            )
        }
        queue.resume(flow, answer, if (answer.replaceBody) body.toByteArray(Charsets.UTF_8) else ByteArray(0))
    }

    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        PaneHeader(title = if (response == null) "Request, before it is sent" else "Response, before it is delivered") {
            Spacer(Modifier.weight(1f))
            PzText("continues unchanged in ${left}s", color = if (left < 30) P.warn else P.faint,
                style = Typo.caption, family = P.Ui, softWrap = false)
            Spacer(Modifier.width(12.dp))
            GhostButton("Abort") { queue.resume(flow, BreakpointResume(hit.id, hit.side, "abort")) }
            Spacer(Modifier.width(8.dp))
            GhostButton("Continue unchanged") { queue.resume(flow, BreakpointResume(hit.id, hit.side, "continue")) }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Send", slim = true) { send() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                problem?.let { PzText(it, color = P.err, style = Typo.label, family = P.Ui) }
                if (response == null) {
                    FormField("Method") { TextInput(method, { method = it }, Modifier.width(120.dp)) }
                    FormField("URL") { TextInput(url, { url = it }, Modifier.weight(1f)) }
                } else {
                    PzText("${hit.request.method} ${hit.request.url}", color = P.dim, style = Typo.label)
                    FormField("Status") {
                        TextInput(status, { status = it }, Modifier.width(90.dp))
                        Spacer(Modifier.width(8.dp))
                        TextInput(reason, { reason = it }, Modifier.weight(1f))
                    }
                }
                FormField("Headers") {
                    TextArea(headers, { headers = it }, Modifier.weight(1f).height(160.dp))
                }
                PzText("Body", color = P.dim, style = Typo.label, family = P.Ui)
                if (bodyText == null) {
                    FormNote(
                        "${flow.body.size} bytes that are not text, or too large to edit here — " +
                            "they go on as they are.",
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(320.dp).background(P.input)) {
                        CodeEditor(body, { body = it }, Modifier.fillMaxSize(), contentType = contentType)
                    }
                    FormNote("Edited as decoded text; the proxy reapplies any Content-Encoding and fixes Content-Length.")
                }
            }
            VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** The body as UTF-8 text, or null when it is binary or too large to edit. */
private fun decodeText(bytes: ByteArray): String? {
    if (bytes.size > EDITABLE_BODY) return null
    val decoder = Charsets.UTF_8.newDecoder()
    return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
}
