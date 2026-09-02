package org.bittrace.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.Segment
import org.bittrace.ui.SegmentedToggle
import org.bittrace.ui.TextInput
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What a body search is looking for, and where. */
class BodySearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var side by mutableStateOf(BodySide.RESPONSE)
    var regex by mutableStateOf(false)

    /** How many rows matched, reported back once a scan finishes. */
    var matches by mutableStateOf(0)

    /** Set when a regex will not compile, so a half-typed one reads as a hint. */
    var problem by mutableStateOf<String?>(null)

    fun show() {
        open = true
    }

    fun hide() {
        open = false
        query = ""
        problem = null
    }
}

/**
 * The body-search strip, between the waterfall and the grid.
 *
 * Filtering by *body* is the one question the column filters cannot answer:
 * every one of them works on metadata the grid already shows, and "which of
 * these responses mentions this order id" is not in any column. It sits above
 * the grid because it narrows the grid, in the same band the headers occupy and
 * in the same colour, so it reads as part of the table's chrome rather than as
 * something floating over it.
 */
@Composable
fun BodySearchBar(state: BodySearchState, scanning: Boolean) {
    Row(
        // Sized by its padding rather than pinned to a height: the row holds a
        // text field and two segmented controls, and a fixed height leaves
        // whichever is tallest sitting against the rules above and below it.
        Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PzText("Body", color = P.dim, style = Typo.label, family = P.Ui)
        Spacer(Modifier.width(8.dp))
        TextInput(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = if (state.regex) "regular expression" else "text to find",
            modifier = Modifier.width(260.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Escape) { state.hide(); true } else false
            },
        )
        Spacer(Modifier.width(10.dp))

        SegmentedToggle(
            segments = listOf(
                Segment(BodySide.REQUEST.name, "Request"),
                Segment(BodySide.RESPONSE.name, "Response"),
            ),
            selected = state.side.name,
        ) { picked -> state.side = BodySide.valueOf(picked) }

        Spacer(Modifier.width(8.dp))
        SegmentedToggle(
            segments = listOf(Segment("text", "Text"), Segment("regex", "Regex")),
            selected = if (state.regex) "regex" else "text",
        ) { picked -> state.regex = picked == "regex" }

        Spacer(Modifier.width(10.dp))
        val (label, colour) = when {
            state.problem != null -> state.problem!! to P.err
            state.query.isBlank() -> "" to P.faint
            scanning -> "searching…" to P.faint
            state.matches == 0 -> "no bodies match" to P.warn
            state.matches == 1 -> "1 flow" to P.ok
            else -> "${state.matches} flows" to P.ok
        }
        PzText(label, color = colour, style = Typo.caption, family = P.Ui, maxLines = 1)

        Spacer(Modifier.weight(1f))
        IconActionButton(
            key = AllIconsKeys.General.Close,
            contentDescription = "Close the body search",
            onClick = { state.hide() },
        )
    }
}

/**
 * The ids of the rows whose body matches.
 *
 * Bodies are held in a cache that evicts, so a row whose body has gone simply
 * does not match — the alternative, treating "no body here any more" as a hit,
 * would put rows in the result that cannot be checked.
 *
 * Decoded as UTF-8 and searched as text even when the body is not text: a
 * binary body will mostly not match, which is the right answer, and refusing to
 * search one would mean deciding what is binary, which the content type lies
 * about often enough to be useless.
 */
fun matchingBodies(
    rows: List<TrafficRow>,
    query: String,
    side: BodySide,
    regex: Boolean,
    body: (String, BodySide) -> ByteArray?,
): Result<Set<String>> = runCatching {
    if (query.isBlank()) return@runCatching emptySet()
    val pattern = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null

    rows.mapNotNullTo(mutableSetOf()) { row ->
        val bytes = body(row.id, side) ?: return@mapNotNullTo null
        val text = bytes.decodeToString()
        val hit = if (pattern != null) pattern.containsMatchIn(text) else text.contains(query, ignoreCase = true)
        row.id.takeIf { hit }
    }
}
