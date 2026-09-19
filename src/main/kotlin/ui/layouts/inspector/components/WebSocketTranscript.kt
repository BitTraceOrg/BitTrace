package org.bittrace.ui.layouts.inspector.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets
import org.bittrace.data.TrafficRow
import org.bittrace.data.WebSocketRecord
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.bytesStr
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.topBorder

/**
 * The messages that travelled over a WebSocket, oldest first.
 *
 * This is the only view of them there is: they are not a body, so no other tab
 * in the pane can show one — the `101` handshake above has no content, and the
 * Body tab on a WebSocket row is always empty.
 *
 * Lazy, and it has to be: a feed can push for as long as it is left open, and
 * the transcript is capped in the thousands rather than the dozens. It does not
 * auto-scroll. A transcript is read by scrolling back through it, and a list
 * that jumps to the newest message every time one lands cannot be read at all
 * while the connection is busy.
 */
@Composable
fun WebSocketTranscript(row: TrafficRow) {
    val messages = row.webSocketMessages
    val end = row.webSocketEnd

    Column(Modifier.fillMaxSize()) {
        TranscriptSummary(row)

        if (messages.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).topBorder(P.line2).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                PzText(
                    if (end == null) "no messages yet" else "the connection carried no messages",
                    color = P.dim,
                    style = Typo.label,
                )
            }
            return@Column
        }

        val state = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(messages.size) { index -> MessageRow(messages[index]) }
            }
            VScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * The connection itself: how much went each way, and how it ended.
 *
 * The lost counts are shown whenever they are non-zero and hidden otherwise,
 * rather than always sitting at zero — they are the one thing here that changes
 * what the transcript below means.
 */
@Composable
private fun TranscriptSummary(row: TrafficRow) {
    val end = row.webSocketEnd
    val messages = row.webSocketMessages

    Row(
        Modifier.fillMaxWidth().background(P.head).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (end == null) {
            PzText("open", color = P.ok, style = Typo.label, weight = FontWeight.SemiBold)
        } else {
            PzText(
                if (end.aborted) "aborted" else "closed",
                color = if (end.aborted) P.err else P.dim,
                style = Typo.label,
                weight = FontWeight.SemiBold,
            )
            PzText(end.closeSummary, color = P.dim, style = Typo.micro)
        }

        Spacer(Modifier.weight(1f))

        val shown = messages.size
        val total = end?.messages?.takeIf { it > 0 } ?: shown.toLong()
        PzText(
            if (shown.toLong() == total) "$shown msg" else "$shown of $total msg",
            color = P.dim,
            style = Typo.micro,
        )
        if (end != null) {
            PzText(
                "↑ ${bytesStr(end.bytesFromClient)} · ↓ ${bytesStr(end.bytesFromServer)}",
                color = P.dim,
                style = Typo.micro,
            )
        }
    }

    val lost = buildList {
        // The sidecar's losses and this end's are different failures with the
        // same effect on what is on screen, so both are named rather than added
        // together.
        end?.dropped?.takeIf { it > 0 }?.let { add("$it dropped by the proxy") }
        end?.truncated?.takeIf { it > 0 }?.let { add("$it cut short") }
        row.webSocketEvicted.takeIf { it > 0 }?.let { add("$it evicted to stay in memory") }
    }
    if (lost.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().topBorder(P.line2).padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            PzText("incomplete: ${lost.joinToString(", ")}", color = P.warn, style = Typo.micro)
        }
    }
}

/**
 * One message: direction, time, size, then the payload.
 *
 * Direction is carried by the arrow *and* by the colour, because the two
 * directions of a socket are what you scan for — reading a transcript is
 * mostly asking "who said this".
 */
@Composable
private fun MessageRow(record: WebSocketRecord) {
    val message = record.message
    val color = if (record.fromClient) P.accent else P.info

    Column(
        Modifier.fillMaxWidth().topBorder(P.line2).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PzText(
                if (record.fromClient) "↑" else "↓",
                color = color,
                style = Typo.label,
                weight = FontWeight.SemiBold,
                modifier = Modifier.width(16.dp),
            )
            PzText(clockOf(message.timestamp), color = P.faint, style = Typo.micro, modifier = Modifier.width(96.dp))
            PzText(message.type.ifBlank { "—" }, color = P.dim, style = Typo.micro, modifier = Modifier.width(48.dp))
            PzText(bytesStr(message.size), color = P.dim, style = Typo.micro)
            // Everything past here is an exception, so it only appears when
            // there is one.
            Note(record.complete, "partial", P.warn)
            Note(!message.injected, "injected", P.warn)
            Note(!message.dropped, "blocked", P.err)
        }
        PzText(previewOf(record), color = P.text, style = Typo.label)
    }
}

/** A flag beside a message, drawn only when [hidden] is false. */
@Composable
private fun Note(hidden: Boolean, label: String, color: Color) {
    if (hidden) return
    Spacer(Modifier.width(8.dp))
    PzText(label, color = color, style = Typo.micro, weight = FontWeight.SemiBold)
}

/** `12:34:56.789` from an ISO 8601 stamp, or the stamp itself if it is not one. */
private fun clockOf(timestamp: String): String {
    val time = timestamp.substringAfter('T', "").ifBlank { return timestamp }
    return time.substringBefore('+').substringBefore('Z').take(12)
}

/**
 * The payload as one line of text.
 *
 * A text message is shown as itself, clipped to a line's worth — the whole of a
 * long one belongs in a view that can scroll, which a row in a list is not. A
 * binary message has no text to show, so it is described instead: its length,
 * and enough leading bytes to recognise a format by.
 */
private fun previewOf(record: WebSocketRecord): String {
    if (record.payload.isEmpty()) {
        return if (record.message.size > 0) "⟨payload not captured⟩" else "⟨empty⟩"
    }
    val body = if (record.isText) {
        String(record.payload, StandardCharsets.UTF_8).take(PREVIEW_CHARS).replace('\n', ' ')
    } else {
        record.payload.take(PREVIEW_BYTES).joinToString(" ") { "%02x".format(it) }
    }
    // Two different things make a preview shorter than the message, and the
    // reader wants to know it is looking at a part either way.
    val elided = !record.complete ||
        (record.isText && record.payload.size > PREVIEW_CHARS) ||
        (!record.isText && record.payload.size > PREVIEW_BYTES)
    return if (elided) "$body …" else body
}

/** A row's worth of a text message. */
private const val PREVIEW_CHARS = 400

/** Enough leading bytes of a binary message to recognise a format by. */
private const val PREVIEW_BYTES = 32
