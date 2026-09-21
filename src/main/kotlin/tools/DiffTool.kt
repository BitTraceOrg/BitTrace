package org.bittrace.tools

import androidx.compose.foundation.background
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
import org.bittrace.ui.components.Dropdown
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.Segment
import org.bittrace.ui.components.SegmentedToggle
import org.bittrace.ui.Typo
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder

/**
 * Compares two captured flows, one against the other.
 *
 * This does not host an [org.bittrace.ui.layouts.inspector.Inspector] — not for want of
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
        // The left flow's own media type picks the language for both panes:
        // they are two captures of one endpoint, and highlighting the halves of
        // a comparison by two different languages would be a difference the
        // diff did not find.
        DiffView(diff, mimeOf(left, side))
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
 * The diff itself: two editors, side by side, one line per aligned row.
 *
 * Both sides are [DiffPane]s — the app's own KodeMirror surface — rather than
 * two columns of text, so what you compare here is highlighted, searchable and
 * foldable the way the same body is in the inspector. The view's own job is
 * what an editor cannot do for itself: keeping the two level.
 *
 * One scroll, outside both, is what does that. The panes are laid out at their
 * documents' full height inside it, so the rows cannot drift apart the way two
 * independently scrolling editors would — and drifting apart is the one thing
 * this view exists to prevent. It is the same trade `CodeEditor` makes for its
 * scrollbar, with the same ceiling, which is why [MAX_ROWS] exists.
 */
@Composable
internal fun DiffView(diff: DiffResult, contentType: String = "") {
    val vertical = rememberScrollState()
    val shown = remember(diff) { diff.rows.take(MAX_ROWS) }
    val clipped = diff.rows.size > shown.size
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
            if (clipped) {
                Spacer(Modifier.width(12.dp))
                PzText(
                    "showing the first ${shown.size} rows of ${diff.rows.size}",
                    color = P.warn, style = Typo.caption, family = P.Ui,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().background(P.bg).verticalScroll(vertical)) {
                Row(Modifier.fillMaxWidth()) {
                    DiffPane(
                        shown, DiffSide.LEFT, contentType,
                        Modifier.weight(1f).rightBorder(P.line),
                    )
                    DiffPane(shown, DiffSide.RIGHT, contentType, Modifier.weight(1f))
                }
            }
            VScrollbar(vertical, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun Count(text: String, color: Color) =
    PzText(text, color = color, style = Typo.caption, family = P.Ui, weight = FontWeight.SemiBold)

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().background(P.bg), contentAlignment = Alignment.Center) {
        PzText(text, color = P.faint, style = Typo.label, family = P.Ui)
    }
}

/**
 * How many aligned rows the view will lay out.
 *
 * Both panes are measured at their documents' full height so that one scroll
 * governs both, and `CodeEditor` records where that stops working: past roughly
 * ten thousand laid-out lines the editor runs out of heap, and past forty
 * thousand `Constraints` cannot represent the height at all. Two documents
 * share that budget here, so the cap is the same 2,000 its scrollbar uses, and
 * the counts bar says when a diff has been clipped to it.
 */
private const val MAX_ROWS = 2_000

/** How many recent flows the pickers offer. */
private const val CANDIDATE_LIMIT = 200

/** Enough of a URL to tell two flows apart in a dropdown. */
private const val URL_CHARS = 60
