package org.bittrace.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.bittrace.ui.P
import org.bittrace.ui.components.editor.CodeEditorState
import org.bittrace.ui.components.editor.CodeSurface
import org.bittrace.ui.components.editor.EditorScrollState
import org.bittrace.ui.components.editor.languageFor

/** Which half of a [DiffRow] a pane is showing. */
internal enum class DiffSide { LEFT, RIGHT }

/**
 * One side of the diff, as an editor.
 *
 * The same code surface every other captured body in this app is read
 * through, rather than a column of `Text`s that happened to look like one: the
 * body is syntax-highlighted by the same language the inspector would pick for
 * it, `Mod-F` searches it, and selection spans lines.
 *
 * The document is the *aligned* text — one line per [DiffRow], blank where this
 * side has no line — so the two panes have the same number of lines. Both take
 * the same [scroll], which is all it takes for them to scroll as one; and since
 * each draws only the rows on screen, a diff of any length is as cheap to show
 * as a short one. Folding is off, because a fold on one side would pull the
 * rows out of line with the other.
 *
 * A consequence worth naming: the gutter numbers the aligned rows, not the
 * file. The two sides therefore agree on every number, which is what reading
 * across a diff wants, but a line's number here is not its number in the
 * response it came from.
 */
@Composable
internal fun DiffPane(
    rows: List<DiffRow>,
    side: DiffSide,
    contentType: String,
    scroll: EditorScrollState,
    modifier: Modifier = Modifier,
    verticalScrollbar: Boolean = true,
) {
    val text = remember(rows, side) {
        rows.joinToString("\n") { row -> (if (side == DiffSide.LEFT) row.left else row.right).orEmpty() }
    }
    val language = languageFor(contentType)
    val state = remember(scroll) {
        CodeEditorState(text, language, readOnly = true, scroll = scroll).apply { foldingEnabled = false }
    }
    val currentRows by rememberUpdatedState(rows)
    SideEffect { state.language = language }
    LaunchedEffect(text) { state.setText(text) }

    // Looked up while drawing, so the washes follow a theme switch.
    CodeSurface(
        state,
        modifier,
        lineBackground = { line -> currentRows.getOrNull(line)?.let { washFor(it.kind, side) } ?: Color.Transparent },
        verticalScrollbar = verticalScrollbar,
    )
}

/**
 * A wash, not a border: a changed line is a region, and a rule would split it.
 *
 * Which side a row lands on is the whole of the rule. A deletion colours the
 * left and leaves the right blank; an insertion does the reverse; a change
 * colours both, because both halves are the one edit.
 */
internal fun washFor(kind: DiffKind, side: DiffSide): Color = when (kind) {
    DiffKind.SAME -> Color.Transparent
    DiffKind.CHANGED -> P.warn.copy(alpha = WASH)
    DiffKind.REMOVED -> if (side == DiffSide.LEFT) P.err.copy(alpha = WASH) else Color.Transparent
    DiffKind.ADDED -> if (side == DiffSide.RIGHT) P.ok.copy(alpha = WASH) else Color.Transparent
}

private const val WASH = 0.14f
