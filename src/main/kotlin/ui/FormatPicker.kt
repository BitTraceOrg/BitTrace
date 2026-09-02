package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One option in a [FormatPicker]: a stable [id] and the label shown on it. */
class Format(val id: String, val label: String)

/**
 * The strip above a body that picks how it is rendered.
 *
 * The inspector (which formatter renders a captured body) and the API client's
 * body tab (which content type to send) had each grown their own copy of this;
 * they are the same control over different data, so it lives here once.
 *
 * A segmented control rather than a row of chips: this is one choice out of a
 * few, which is what a segmented control means, whereas Int UI chips are filter
 * tags — 28dp pills with a full corner radius. Seven of those carried far more
 * visual weight than a format picker deserves sitting under a tab strip.
 */
@Composable
fun FormatPicker(
    formats: List<Format>,
    selected: String?,
    modifier: Modifier = Modifier,
    /**
     * The surface the strip sits on. Defaults to the inspector's, which is what
     * it was built for; the request builder passes its own, because a strip in a
     * different grey from the pane it caps reads as a separate control rather
     * than as that pane's own header.
     */
    surface: Color = P.bg,
    onSelect: (Format) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(surface).bottomBorder(P.line)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentedToggle(
            segments = formats.map { Segment(it.id, it.label) },
            selected = selected.orEmpty(),
        ) { id -> formats.firstOrNull { it.id == id }?.let(onSelect) }
        trailing()
    }
}
