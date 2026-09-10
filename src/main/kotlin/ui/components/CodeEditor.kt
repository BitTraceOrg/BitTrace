package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monkopedia.kodemirror.basicsetup.basicSetup
import com.monkopedia.kodemirror.state.Compartment
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.state.readOnly
import com.monkopedia.kodemirror.view.KodeMirror
import com.monkopedia.kodemirror.view.editable
import com.monkopedia.kodemirror.view.onChange
import com.monkopedia.kodemirror.view.placeholder
import com.monkopedia.kodemirror.view.rememberEditorSession
import com.monkopedia.kodemirror.view.scrollPastEnd
import com.monkopedia.kodemirror.view.setDoc
import org.bittrace.ui.components.editor.editorAppearance
import org.bittrace.ui.components.editor.languageFor

/**
 * The app's code surface, over KodeMirror.
 *
 * KodeMirror is a native Kotlin port of CodeMirror 6 — no WebView, no JS bridge
 * — and it replaces about six files of hand-rolled editor: a line-number gutter
 * aligned by hand to a `TextLayoutResult`, a find bar, a completion popup, and a
 * viewer that had to be virtualized by splitting the document into per-line
 * `AnnotatedString`s. All of that is machinery the port already had, tested
 * against CodeMirror's own suite, and it brings folding, bracket matching and
 * undo history this app never had at all.
 *
 * What stayed ours is the part that is ours: [editorAppearance] derives the
 * editor's theme from the active palette, so an external theme plugin still
 * recolours it — adopting one of the seventeen bundled themes would quietly have
 * ended that.
 */
@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentType: String = "",
    placeholder: String = "",
) {
    val editableText = value.length <= EDITABLE_LIMIT

    // A session is built once and then owns its document, so the language, the
    // theme and the read-only flag are fixed at construction. `key` is what
    // rebuilds it when one of them actually changes — rare, and worth the lost
    // undo history, which is the alternative to reconfiguring a live editor.
    key(contentType, P.palette, editableText) {
        val latest by rememberUpdatedState(onValueChange)
        val language = languageFor(contentType)
        val appearance = editorAppearance()

        val session = rememberEditorSession(
            doc = value,
            // Built as a list and filtered, because `extensionListOf` takes no
            // nulls and three of these are conditional.
            extensions = extensionListOf(
                *listOfNotNull(
                    basicSetup,
                    appearance,
                    language.extension,
                    // Room below the last line, so a short document still has an
                    // editor's worth of space to click into and the caret can
                    // sit somewhere other than against the bottom of its own
                    // text. Modest: the default is 200dp, which is most of a
                    // body pane.
                    scrollPastEnd(60.dp),
                    placeholder.takeIf { it.isNotEmpty() }?.let { placeholder(it) },
                    // Past the limit the text is shown but not typed into. The
                    // port handles large documents far better than what came
                    // before, but a megabyte of JSON is still not something to
                    // edit by hand.
                    if (editableText) null else readOnly.of(true),
                    if (editableText) null else editable.of(false),
                    onChange { edited -> latest(edited) },
                ).toTypedArray(),
            ),
        )

        // The document belongs to the session once it exists, so a change from
        // outside — loading a saved request into the tab — has to be pushed in.
        // Guarded, or echoing our own edit back would fight the caret.
        LaunchedEffect(value) {
            if (session.state.doc.toString() != value) session.setDoc(value)
        }

        Box(modifier.background(P.input)) {
            KodeMirror(session = session, modifier = Modifier.fillMaxSize())
            if (!editableText) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .background(P.warn)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    PzText("read-only · body too large to edit", color = P.bg, style = Typo.micro, family = P.Ui)
                }
            }
        }
    }
}

/**
 * A read-only view of captured text.
 *
 * The same editor with editing switched off, rather than a second rendering
 * path: it inherits the gutter, the search panel, the selection and the
 * viewport, and a viewer that behaved differently from the editor beside it is
 * exactly the kind of difference people notice.
 */
@Composable
fun CodeView(value: String, modifier: Modifier = Modifier, contentType: String = "") {
    // Only the palette rebuilds the session here. The language does not: it
    // lives in a compartment, which is CodeMirror's own answer to a setting
    // that changes over the life of an editor, and it is reconfigured in place
    // below. Paging a flow's body tabs changes the content type on almost every
    // switch — Hex declares none at all — and rebuilding for that threw away
    // the whole editor (state, viewport, search panel) and built another to
    // show text that had not changed.
    key(P.palette) {
        val language = languageFor(contentType)
        val appearance = editorAppearance()
        val languageSlot = remember { Compartment() }

        val session = rememberEditorSession(
            doc = value,
            extensions = extensionListOf(
                *listOfNotNull(
                    basicSetup,
                    appearance,
                    languageSlot.of(language.extension),
                    readOnly.of(true),
                    editable.of(false),
                ).toTypedArray(),
            ),
        )
        // What the compartment currently holds. Tracked rather than derived
        // from the state, because the session exposes the configuration as
        // extensions rather than as the language that produced them — and
        // comparing content types instead would reconfigure for a change from
        // one JSON media type to another, which is the same language.
        val configured = remember { mutableStateOf(language) }
        LaunchedEffect(language) {
            // Identity, not equality: `languageFor` hands back one shared
            // instance per language, so two calls agree exactly when the
            // language is unchanged.
            if (language !== configured.value) {
                configured.value = language
                session.dispatch(
                    TransactionSpec(effects = listOf(languageSlot.reconfigure(language.extension))),
                )
            }
        }
        LaunchedEffect(value) {
            if (session.state.doc.toString() != value) session.setDoc(value)
        }
        Box(modifier.background(P.input)) {
            KodeMirror(session = session, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Beyond this the editor stops accepting input.
 *
 * Kept from the hand-rolled editor, though the reason narrowed: it used to be
 * the point at which typing stopped being smooth, and is now the point at which
 * editing a body by hand stops being something anybody means to do.
 */
const val EDITABLE_LIMIT = 256 * 1024
