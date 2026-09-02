package org.bittrace.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficRow
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.proxy.BodySide
import org.bittrace.ui.Dropdown
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.Segment
import org.bittrace.ui.SegmentedToggle
import org.bittrace.ui.Typo
import org.bittrace.ui.VScrollbar
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder

/**
 * Compares two captured flows, one against the other.
 *
 * This does not host an [org.bittrace.components.Inspector] — not for want of
 * trying. The Inspector renders *one* flow and owns its own tab, formatter and
 * scroll state; two of them side by side would show two bodies at their own
 * scroll positions with nothing lining up, which is a split view rather than a
 * diff. What is reused is everything underneath it: the same body formatters,
 * the same request/response vocabulary, the same message layout the Raw tab
 * assembles (start line, headers, blank line, body), so what you compare here is
 * what you would have read there.
 */
@Composable
fun DiffTool(
    store: SessionStore,
    bodyProvider: (String, BodySide) -> ByteArray?,
    formatters: List<BodyFormatter>,
    /** The pair the grid marked, when the tool was opened from there. */
    initial: Pair<String, String>?,
    /** What to start on otherwise — the flow the panes are already showing. */
    fallbackId: String?,
) {
    // Capped: a combo box is a list you read, and five thousand entries is not
    // one. The most recent flows are the ones anybody is comparing.
    val candidates = remember(store.rows.size) { store.rows.takeLast(CANDIDATE_LIMIT).asReversed() }

    var side by remember { mutableStateOf(BodySide.RESPONSE) }
    // Re-seeded whenever the grid hands over a new pair, so marking two rows and
    // asking again re-points a window that is already open.
    var leftId by remember(initial, fallbackId) {
        mutableStateOf(initial?.first ?: fallbackId ?: candidates.firstOrNull()?.id)
    }
    var rightId by remember(initial, fallbackId) {
        // Without a marked pair it falls to the flow before the selected one,
        // because the question that opens this window unasked is nearly always
        // "what changed since the last time I called this?".
        mutableStateOf(initial?.second ?: candidates.getOrNull(1)?.id)
    }

    val left = candidates.firstOrNull { it.id == leftId }
    val right = candidates.firstOrNull { it.id == rightId }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(P.chrome).bottomBorder(P.line)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowPicker(candidates, leftId) { leftId = it }
            Spacer(Modifier.width(8.dp))
            FlowPicker(candidates, rightId) { rightId = it }
            Spacer(Modifier.width(12.dp))
            SegmentedToggle(
                segments = listOf(Segment("REQUEST", "Request"), Segment("RESPONSE", "Response")),
                selected = side.name,
            ) { picked -> side = BodySide.valueOf(picked) }
            Spacer(Modifier.weight(1f))
        }

        if (left == null || right == null) {
            Empty("Pick two flows to compare.")
            return@Column
        }
        if (left.id == right.id) {
            Empty("Those are the same flow.")
            return@Column
        }

        val leftText = flowText(left, side, bodyProvider, formatters)
        val rightText = flowText(right, side, bodyProvider, formatters)
        if (leftText == null || rightText == null) {
            Empty("Reading…")
            return@Column
        }

        val diff = remember(leftText, rightText) {
            diffLines(leftText.lines(), rightText.lines())
        }
        DiffView(diff)
    }
}

@Composable
private fun FlowPicker(rows: List<TrafficRow>, selected: String?, onSelect: (String) -> Unit) {
    val labels = remember(rows) { rows.map(::labelOf) }
    val current = rows.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { labels[it] } ?: ""
    Dropdown(value = current, options = labels, width = 300.dp) { picked ->
        val index = labels.indexOf(picked)
        rows.getOrNull(index)?.let { onSelect(it.id) }
    }
}

/** `#42 GET api.example.com/users` — the row number makes every label unique. */
private fun labelOf(row: TrafficRow): String {
    val request = row.request.request
    val short = request.url.substringAfter("://").take(URL_CHARS)
    return "#${row.rowCount} ${request.method} $short"
}

/**
 * One flow rendered the way the Raw tab renders it, off the UI thread.
 *
 * Null while it is being built. Formatting runs a lexer over the whole body, so
 * it never happens in composition — the same rule the Inspector follows, for the
 * same reason.
 */
@Composable
private fun flowText(
    row: TrafficRow,
    side: BodySide,
    bodyProvider: (String, BodySide) -> ByteArray?,
    formatters: List<BodyFormatter>,
): String? {
    val text by produceState<String?>(null, row.id, side, formatters) {
        value = null
        val bytes = bodyProvider(row.id, side)
        val mime = mimeOf(row, side)
        val formatter = formatters.firstOrNull { it.handles(mime) }
            ?: formatters.firstOrNull { it.id == "bittrace.raw" }
        value = withContext(Dispatchers.Default) {
            buildString {
                append(headText(row, side))
                append('\n')
                if (bytes != null && bytes.isNotEmpty()) {
                    append(runCatching { formatter?.format(bytes, mime) }.getOrNull() ?: bytes.decodeToString())
                }
            }
        }
    }
    return text
}

private fun headText(row: TrafficRow, side: BodySide): String = buildString {
    if (side == BodySide.REQUEST) {
        val request = row.request.request
        append("${request.method} ${request.url} ${request.httpVersion}\n")
        row.completeRequest?.request?.headers?.forEach { append("${it.name}: ${it.value}\n") }
    } else {
        val response = row.response?.response
        if (response == null) {
            append("(no response)\n")
        } else {
            append("${response.httpVersion} ${response.status} ${response.statusText}\n")
            row.completeResponse?.response?.headers?.forEach { append("${it.name}: ${it.value}\n") }
        }
    }
}

private fun mimeOf(row: TrafficRow, side: BodySide): String {
    val headers = when (side) {
        BodySide.REQUEST -> row.completeRequest?.request?.headers
        BodySide.RESPONSE -> row.completeResponse?.response?.headers
    }
    return headers?.firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value.orEmpty()
}

/**
 * The diff itself: two gutters, two columns, one row per aligned line.
 *
 * Both sides share one horizontal scroll, because reading a diff means reading
 * across it — two independent scrolls would let the halves of one change drift
 * apart, which is the one thing this view exists to prevent.
 */
@Composable
private fun DiffView(diff: DiffResult) {
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val added = diff.rows.count { it.kind == DiffKind.ADDED }
    val removed = diff.rows.count { it.kind == DiffKind.REMOVED }
    val changed = diff.rows.count { it.kind == DiffKind.CHANGED }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).background(P.head).bottomBorder(P.line)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Count("+$added", P.ok)
            Spacer(Modifier.width(10.dp))
            Count("−$removed", P.err)
            Spacer(Modifier.width(10.dp))
            Count("~$changed", P.warn)
            if (diff.truncated) {
                Spacer(Modifier.width(12.dp))
                PzText(
                    "too large to align line by line — showing the changed block",
                    color = P.warn, style = Typo.caption, family = P.Ui,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().background(P.bg), state = listState) {
                items(diff.rows.size) { index ->
                    DiffLine(diff.rows[index], hScroll)
                }
            }
            VScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun Count(text: String, color: Color) =
    PzText(text, color = color, style = Typo.caption, family = P.Ui, weight = FontWeight.SemiBold)

@Composable
private fun DiffLine(row: DiffRow, hScroll: androidx.compose.foundation.ScrollState) {
    Row(Modifier.fillMaxWidth()) {
        Half(row.leftNumber, row.left, washFor(row.kind, left = true), hScroll, Modifier.weight(1f))
        Half(row.rightNumber, row.right, washFor(row.kind, left = false), hScroll, Modifier.weight(1f))
    }
}

@Composable
private fun Half(
    number: Int?,
    text: String?,
    wash: Color,
    hScroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier,
) {
    Row(modifier.background(wash)) {
        Box(
            Modifier.width(48.dp).rightBorder(P.line2).padding(end = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // A side with no line here is blank rather than zero: the gutters
            // count their own files, so a number would claim a line that the
            // other file has and this one does not.
            if (number != null) {
                PzText("$number", color = P.faint, style = Typo.label, softWrap = false)
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(hScroll).padding(horizontal = 6.dp)) {
            PzText(text.orEmpty(), color = P.text, style = Typo.label, softWrap = false, maxLines = 1)
        }
    }
}

/** A wash, not a border: a changed line is a region, and a rule would split it. */
private fun washFor(kind: DiffKind, left: Boolean): Color = when (kind) {
    DiffKind.SAME -> Color.Transparent
    DiffKind.CHANGED -> P.warn.copy(alpha = WASH)
    DiffKind.REMOVED -> if (left) P.err.copy(alpha = WASH) else Color.Transparent
    DiffKind.ADDED -> if (left) Color.Transparent else P.ok.copy(alpha = WASH)
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().background(P.bg), contentAlignment = Alignment.Center) {
        PzText(text, color = P.faint, style = Typo.label, family = P.Ui)
    }
}

private const val WASH = 0.14f

/** How many recent flows the pickers offer. */
private const val CANDIDATE_LIMIT = 200

/** Enough of a URL to tell two flows apart in a dropdown. */
private const val URL_CHARS = 60
