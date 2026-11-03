package org.bittrace.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.components.editor.CodeEditorState
import org.bittrace.ui.components.editor.CodeSurface
import org.bittrace.ui.components.editor.EditorEngine
import org.bittrace.ui.components.editor.languageFor

/**
 * The app's code editor.
 *
 * Built on the in-house editor in `ui/components/editor`: one
 * [CodeEditorState] per call site, made once and then changed in place. The
 * language follows [contentType] without rebuilding anything, so switching a
 * body from JSON to XML keeps the caret and the undo history; a theme switch
 * only repaints; and a body of any length scrolls, with a scrollbar, because
 * the view draws only the rows on screen.
 *
 * Past [EDITABLE_LIMIT] the text is shown but not typed into — a megabyte of
 * JSON is not something anybody means to edit by hand — and a badge says so.
 */
@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentType: String = "",
    placeholder: String = "",
) {
    val editable = value.length <= EDITABLE_LIMIT
    val state = remember { CodeEditorState(value, languageFor(contentType), readOnly = !editable) }
    val latest by rememberUpdatedState(onValueChange)
    // The last text this editor reported, so its own edit coming back as
    // `value` is recognised as an echo rather than loaded as new text.
    val emitted = remember { arrayOfNulls<String>(1) }

    SideEffect {
        state.language = languageFor(contentType)
        state.readOnly = !editable
    }
    DisposableEffect(state) {
        state.onTextChange = { text ->
            emitted[0] = text
            latest(text)
        }
        onDispose { state.onTextChange = null }
    }
    LaunchedEffect(value) {
        if (value !== emitted[0]) state.setText(value)
    }

    Box(modifier) {
        CodeSurface(state, Modifier.fillMaxSize(), placeholder = placeholder)
        if (!editable) {
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

/**
 * A read-only view of captured text.
 *
 * The same editor in read-only mode, rather than a second rendering path: it
 * has the gutter, find, selection and folding the editor beside it has, and a
 * viewer that behaved differently from the editor next to it is exactly the
 * kind of difference people notice.
 *
 * [plain] drops all of that down to text: no line numbers, no folding, no
 * language, no colour. It is for a view whose whole claim is that nothing has
 * been done to what it shows — a gutter counting the lines of a raw message is
 * a reading of it, and a highlighter is an interpretation. Still this editor
 * rather than a `Text`, because the virtualized view and the selection are what
 * make a multi-megabyte body openable at all.
 *
 * Paging a flow's body tabs changes the content type on nearly every switch;
 * that reconfigures the one state in place instead of building another.
 */
@Composable
fun CodeView(
    value: String,
    modifier: Modifier = Modifier,
    contentType: String = "",
    plain: Boolean = false,
) {
    val language = if (plain) EditorEngine.plain else languageFor(contentType)
    val state = remember { CodeEditorState(value, language, readOnly = true) }
    SideEffect {
        state.language = language
        state.foldingEnabled = !plain
    }
    LaunchedEffect(value) { state.setText(value) }
    CodeSurface(state, modifier, gutter = !plain)
}

/**
 * Beyond this the editor stops accepting input.
 *
 * Kept from the editors this one replaced, though not for their reason: not
 * the point where typing stops being smooth — the editor draws only what is
 * on screen — but the point at which editing a body by hand stops being
 * something anybody means to do, and at which reporting the whole text back on
 * every keystroke starts to cost something.
 */
const val EDITABLE_LIMIT = 256 * 1024
