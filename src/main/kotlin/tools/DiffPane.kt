package org.bittrace.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.LineNumber
import com.monkopedia.kodemirror.state.RangeSetBuilder
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.state.readOnly
import com.monkopedia.kodemirror.view.Decoration
import com.monkopedia.kodemirror.view.DecorationSet
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.KodeMirror
import com.monkopedia.kodemirror.view.LineDecorationSpec
import com.monkopedia.kodemirror.view.PluginValue
import com.monkopedia.kodemirror.view.ViewPlugin
import com.monkopedia.kodemirror.view.ViewUpdate
import com.monkopedia.kodemirror.view.editable
import com.monkopedia.kodemirror.view.rememberEditorSession
import com.monkopedia.kodemirror.view.setDoc
import org.bittrace.ui.P
import org.bittrace.ui.components.editor.editorAppearance
import org.bittrace.ui.components.editor.languageFor
import org.bittrace.ui.components.readOnlySetup

/** Which half of a [DiffRow] a pane is showing. */
internal enum class DiffSide { LEFT, RIGHT }

/**
 * One side of the diff, as an editor.
 *
 * The same KodeMirror surface every other captured body in this app is read
 * through, rather than a column of `Text`s that happened to look like one. What
 * that buys is what the hand-rolled view could not have without building it
 * twice: the body is syntax-highlighted by the same language the inspector
 * would pick for it, `Mod-F` searches it, selection spans lines, and a long
 * response is folded rather than scrolled past.
 *
 * The document is the *aligned* text — one line per [DiffRow], blank where this
 * side has no line — so the two panes have the same number of lines and the
 * same line heights. That is what makes a single outer scroll keep them level;
 * see [DiffView].
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
    modifier: Modifier = Modifier,
) {
    // Keyed on the rows as well as the theme: the washes are baked into the
    // session's extensions at construction, so a new diff needs a new session
    // rather than a new document under the old one's colours.
    key(P.palette, contentType, rows) {
        val text = remember(rows, side) {
            rows.joinToString("\n") { row -> (if (side == DiffSide.LEFT) row.left else row.right).orEmpty() }
        }
        val washes = remember(rows, side) { rows.map { washFor(it.kind, side) } }
        val appearance = editorAppearance()
        val language = languageFor(contentType)

        val session = rememberEditorSession(
            doc = text,
            extensions = extensionListOf(
                readOnlySetup,
                appearance,
                language.extension,
                readOnly.of(true),
                editable.of(false),
                lineWash(washes),
            ),
        )
        LaunchedEffect(text) {
            if (session.state.doc.toString() != text) session.setDoc(text)
        }
        // `fillMaxWidth`, not `fillMaxSize`: the pane's height is the
        // document's, because the diff scrolls both sides from outside.
        KodeMirror(session = session, modifier = modifier.fillMaxWidth())
    }
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

/**
 * The diff's colours, as line decorations.
 *
 * A view plugin because that is where CodeMirror puts decorations, and the same
 * shape the port's own `highlightActiveLine` uses. The set is built once: the
 * document never changes under a session here, since a new diff builds a new
 * one.
 */
private fun lineWash(washes: List<Color>): Extension = ViewPlugin.define(
    create = { session -> WashPlugin(session, washes) },
    decorations = { plugin -> plugin.decorations },
).asExtension()

private class WashPlugin(session: EditorSession, private val washes: List<Color>) : PluginValue {
    var decorations: DecorationSet = build(session)
        private set

    override fun update(update: ViewUpdate) {
        if (update.docChanged) decorations = build(update.session)
    }

    private fun build(session: EditorSession): DecorationSet {
        val doc = session.state.doc
        val builder = RangeSetBuilder<Decoration>()
        // Ascending, and one range per line: a builder takes its ranges in
        // order, and a line decoration is an empty range at the line's start.
        for (number in 1..doc.lines) {
            val wash = washes.getOrNull(number - 1) ?: continue
            if (wash == Color.Transparent) continue
            val line = doc.line(LineNumber(number))
            builder.add(line.from, line.from, Decoration.line(LineDecorationSpec(style = SpanStyle(background = wash))))
        }
        return builder.finish()
    }
}

private const val WASH = 0.14f
