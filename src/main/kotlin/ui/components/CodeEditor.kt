package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.monkopedia.kodemirror.basicsetup.minimalSetup
import com.monkopedia.kodemirror.commands.defaultKeymap
import com.monkopedia.kodemirror.language.bracketMatching
import com.monkopedia.kodemirror.language.defaultHighlightStyle
import com.monkopedia.kodemirror.language.foldGutter
import com.monkopedia.kodemirror.language.foldKeymap
import com.monkopedia.kodemirror.language.syntaxHighlighting
import com.monkopedia.kodemirror.search.highlightSelectionMatches
import com.monkopedia.kodemirror.search.searchKeymap
import com.monkopedia.kodemirror.state.Compartment
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.allowMultipleSelections
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.state.readOnly
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.KodeMirror
import com.monkopedia.kodemirror.view.crosshairCursor
import com.monkopedia.kodemirror.view.drawSelection
import com.monkopedia.kodemirror.view.editable
import com.monkopedia.kodemirror.view.highlightActiveLine
import com.monkopedia.kodemirror.view.highlightActiveLineGutter
import com.monkopedia.kodemirror.view.highlightSpecialChars
import com.monkopedia.kodemirror.view.keymapOf
import com.monkopedia.kodemirror.view.lineNumbers
import com.monkopedia.kodemirror.view.onChange
import com.monkopedia.kodemirror.view.placeholder
import com.monkopedia.kodemirror.view.rectangularSelection
import com.monkopedia.kodemirror.view.rememberEditorSession
import com.monkopedia.kodemirror.view.scrollPastEnd
import com.monkopedia.kodemirror.view.setDoc
import com.monkopedia.kodemirror.view.tabRendering
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
                    // Past the limit this is a viewer, so it gets the viewer's
                    // bundle. `editableText` is already in the `key` above, so
                    // crossing the limit rebuilds the session either way.
                    if (editableText) basicSetup else readOnlySetup,
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

        ScrolledEditor(session, value, modifier) {
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
 *
 * [plain] drops all of that down to text: no line numbers, no fold gutter, no
 * active-line highlight, no language, no colour. It is for a view whose whole
 * claim is that nothing has been done to what it shows — a gutter counting the
 * lines of a raw message is a reading of it, and a highlighter is an
 * interpretation. Still this editor rather than a `Text`, because the viewport
 * and the selection are what make a multi-megabyte body openable at all.
 */
@Composable
fun CodeView(
    value: String,
    modifier: Modifier = Modifier,
    contentType: String = "",
    plain: Boolean = false,
) {
    // Only the palette rebuilds the session here. The language does not: it
    // lives in a compartment, which is CodeMirror's own answer to a setting
    // that changes over the life of an editor, and it is reconfigured in place
    // below. Paging a flow's body tabs changes the content type on almost every
    // switch — Hex declares none at all — and rebuilding for that threw away
    // the whole editor (state, viewport, search panel) and built another to
    // show text that had not changed.
    // [plain] joins the palette in the key: it picks a different setup bundle,
    // which is fixed for the life of a session. Paging between a rich tab and a
    // plain one therefore does rebuild the editor — they are two different
    // views, and only a switch between them pays for it.
    key(P.palette, plain) {
        // A plain view has no language, so nothing claims a token and nothing is
        // coloured. `languageFor` still has to supply one: the setup bundles
        // throw without a language configured.
        val language = languageFor(if (plain) "" else contentType)
        val appearance = editorAppearance()
        val languageSlot = remember { Compartment() }

        val session = rememberEditorSession(
            doc = value,
            extensions = extensionListOf(
                *listOfNotNull(
                    if (plain) minimalSetup else readOnlySetup,
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
            // A plain view stays on the plain language whatever the content
            // type says, so this never fires for one.
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
        ScrolledEditor(session, value, modifier)
    }
}

/**
 * The editor under a scroll container of ours, so it can carry a scrollbar.
 *
 * KodeMirror scrolls vertically through a `LazyColumn` it keeps to itself: the
 * state never leaves the composable, `HorizontalScrollbar` is private to that
 * file, and neither `EditorTheme` nor `ViewUpdate` offers anywhere to hang a
 * vertical one. The only way to put a scrollbar beside the editor is to be the
 * thing that scrolls it.
 *
 * Handing the editor an unbounded height is what the library calls its height
 * contract, and it is supported — `boundUnconstrainedHeight` swaps the infinite
 * constraint for the document's natural height rather than collapsing or
 * throwing. But it means exactly what it says: **the whole document is laid
 * out**, so the line list no longer virtualizes and a large body costs what a
 * large body costs. That is the trade this makes, deliberately.
 *
 * It also takes the editor's caret reveal with it — under an unbounded height
 * there is nothing for the editor to scroll, so scroll-into-view has nothing to
 * act on and the surrounding container governs what is visible.
 */
@Composable
private fun ScrolledEditor(
    session: EditorSession,
    value: String,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scrollable = remember(value) { withinScrollbarLimit(value) }
    Box(modifier.background(P.input)) {
        if (scrollable) {
            val vertical = rememberScrollState()
            Box(Modifier.fillMaxSize().verticalScroll(vertical)) {
                // `fillMaxWidth`, not `fillMaxSize`: the width is the pane's,
                // but the height has to be the document's. Asking for the
                // parent's height inside a scrolling container is asking for
                // infinity.
                KodeMirror(session = session, modifier = Modifier.fillMaxWidth())
            }
            // Draws nothing while the document fits, like every other scrollbar
            // in the app, so a short body is not given a rail to look at.
            VScrollbar(vertical, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        } else {
            // Past the limit the editor keeps its own scrolling, and there is
            // no scrollbar to show — see [SCROLLBAR_LINE_LIMIT].
            KodeMirror(session = session, modifier = Modifier.fillMaxSize())
        }
        overlay()
    }
}

/**
 * How long a document may be before it keeps the editor's own scrolling.
 *
 * Laying a whole document out is not merely slower than virtualizing it, it
 * stops working, and it stops working twice over. Rendering the editor under an
 * unbounded height at a 700px width:
 *
 * ```
 *   2,000 … 10,000 lines   ok
 *          11,000 lines    OutOfMemoryError
 *          40,000 lines    IllegalArgumentException: Can't represent a width
 *                          of 700 and height of 840029 in Constraints
 * ```
 *
 * The second is a hard ceiling — `Constraints` packs both axes into a `Long`,
 * so a tall enough document cannot be measured at any heap size. The first
 * arrives well before it and is the one that decides this number: a capture
 * tool is holding traffic in the same heap, and spending it on the lines of a
 * body nobody is reading is the wrong trade.
 *
 * 2,000 is a comfortable multiple below where it broke, and covers the bodies
 * this is for — a pretty-printed API response runs to tens or hundreds of
 * lines. Past it the editor virtualizes as before and the scrollbar is the
 * thing given up, which is the right way round: the alternative is a scrollbar
 * on a pane that crashes.
 */
private const val SCROLLBAR_LINE_LIMIT = 2_000

/**
 * Whether [text] is short enough for [SCROLLBAR_LINE_LIMIT].
 *
 * Stops at the first line past the limit rather than counting them all, so
 * asking the question about a ten-megabyte body costs the same as asking it
 * about a small one.
 */
private fun withinScrollbarLimit(text: String): Boolean {
    var lines = 1
    for (c in text) {
        if (c == '\n' && ++lines > SCROLLBAR_LINE_LIMIT) return false
    }
    return true
}

/**
 * [basicSetup] with the parts that only an editable document can use removed.
 *
 * Everything this app shows a *captured* body through is read-only — the
 * inspector's panes, an API response, and any body past [EDITABLE_LIMIT] — and
 * `basicSetup` is the editing bundle. Installing it behind `readOnly` still
 * builds every state field and view plugin in it; they simply never fire.
 *
 * What goes, and why it cannot matter here:
 *
 *  - `history()` — undo/redo over a document that takes no edits. It is also
 *    the one with a footprint: an undo history exists to retain changesets.
 *  - `autocompletion()` and its keymap — a completion popup whose result has
 *    nowhere to be inserted.
 *  - `closeBrackets()` and its keymap, `indentOnInput` — reactions to typing.
 *  - `dropCursor` — where a drag would insert.
 *  - `lintKeymap` — this app registers no linter, so the bindings address a
 *    diagnostic set that is always empty.
 *
 * What stays is everything that makes a body *readable*: the gutter, folding,
 * bracket matching, selection and its match highlighting, the active line, and
 * the search keymap. Search stays lazy — `Mod-F` appends the search extension
 * on first use — so the panel costs nothing until somebody looks for something.
 *
 * Not [minimalSetup] plus additions: that bundle drops the gutter and folding,
 * which is a deliberately different view (see [CodeView]'s `plain`).
 *
 * Internal rather than private because the diff tool builds its own sessions —
 * two of them, scrolling as one — and a viewer there that behaved differently
 * from the viewer in the inspector would be the same mismatch this bundle
 * exists to prevent.
 */
internal val readOnlySetup: Extension = extensionListOf(
    lineNumbers,
    highlightActiveLineGutter,
    highlightSpecialChars,
    tabRendering,
    foldGutter(),
    drawSelection,
    allowMultipleSelections.of(true),
    syntaxHighlighting(defaultHighlightStyle, fallback = true),
    bracketMatching(),
    rectangularSelection,
    crosshairCursor,
    highlightActiveLine,
    highlightSelectionMatches(),
    keymapOf(defaultKeymap + searchKeymap + foldKeymap),
)

/**
 * Beyond this the editor stops accepting input.
 *
 * Kept from the hand-rolled editor, though the reason narrowed: it used to be
 * the point at which typing stopped being smooth, and is now the point at which
 * editing a body by hand stops being something anybody means to do.
 */
const val EDITABLE_LIMIT = 256 * 1024
