package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The strip that opens a pane: the raised surface, the hairline under it, and a
 * title, a tab strip or a row of controls laid along it.
 *
 * Seven screens had each inlined this Row, and the copies had drifted to three
 * horizontal insets, three vertical ones and two type steps — so two strips
 * meeting at a pane edge no longer lined up. One component settles it: 10dp in
 * from the edges, at least [PANE_HEADER_HEIGHT] tall, and taller only when what
 * it holds is taller, which is what keeps a strip of tabs exactly as tall as
 * the tabs rather than padded out around them.
 *
 * The title is a heading, so it carries weight; [content] is laid out after it
 * in the same row and can take `Modifier.weight(1f)` to push a trailing control
 * to the far edge.
 */
@Composable
fun PaneHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = PANE_HEADER_HEIGHT)
            .background(P.head).bottomBorder(P.line)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            PzText(
                title,
                color = P.dim, style = Typo.label, family = P.Ui,
                weight = FontWeight.SemiBold, softWrap = false,
            )
            Spacer(Modifier.width(8.dp))
        }
        content()
    }
}

/**
 * How tall a header strip is at rest — the flow table's column header, the
 * inspector's pane titles and the KV editor's column names are all this, so a
 * grid docked under a pane header reads as one continuous chrome.
 */
val PANE_HEADER_HEIGHT = 26.dp
