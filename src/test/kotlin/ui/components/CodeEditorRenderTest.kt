package org.bittrace.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.bittrace.tools.DiffKind
import org.bittrace.tools.DiffResult
import org.bittrace.tools.DiffRow
import org.bittrace.tools.DiffView
import org.bittrace.ui.BitTraceTheme
import org.bittrace.ui.components.editor.CodeEditorState
import org.bittrace.ui.components.editor.CodeSurface
import org.bittrace.ui.components.editor.EditorScrollState
import org.bittrace.ui.components.editor.languageFor
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor through every phase — composition, measure, layout, draw — at the
 * sizes that used to break it.
 *
 * The editor it replaced was laid out at its document's full height to carry a
 * scrollbar, which ran out of heap past about ten thousand lines and could not
 * be measured at all past forty thousand. This one draws only the rows on
 * screen, so the sizes below are the point: each must render, and must render
 * without a cap.
 *
 * Rendered through [ImageComposeScene]; a failure surfaces as an exception out
 * of [render]. Set `-Dbittrace.ui.dump=<dir>` to keep the frames as PNGs.
 */
class CodeEditorRenderTest {

    private fun json(entries: Int) = buildString {
        append("{\n")
        repeat(entries) { append("  \"key$it\": \"value$it\",\n") }
        append("  \"last\": true\n}")
    }

    /** Renders [content] for a few frames, so highlighting and the scrollbars catch up. */
    private fun render(name: String, frames: Int = 3, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 700, height = 400, density = Density(1f)) {
            BitTraceTheme(dark = true) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
        try {
            var image = scene.render(0)
            for (i in 1 until frames) image = scene.render(i * 16_000_000L)
            val dump = System.getProperty("bittrace.ui.dump")
            if (dump != null) {
                val dir = Path.of(dump).also { Files.createDirectories(it) }
                image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { Files.write(dir.resolve("$name.png"), it) }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a document taller than the pane`() {
        render("codeview-long") { CodeView(value = json(600), contentType = "application/json") }
    }

    @Test
    fun `sixty thousand lines, which the old editor could not lay out`() {
        render("codeview-huge") { CodeView(value = json(60_000), contentType = "application/json") }
    }

    @Test
    fun `a single two-megabyte line`() {
        val line = "[" + (0 until 60_000).joinToString(",") { "{\"id\":$it,\"ok\":true}" } + "]"
        check(line.length > 1_000_000)
        render("codeview-longline") { CodeView(value = line, contentType = "application/json") }
    }

    @Test
    fun `the plain view, short and empty documents`() {
        render("codeview-plain") { CodeView(value = json(600), plain = true) }
        render("codeview-short") { CodeView(value = "{\n  \"ok\": true\n}", contentType = "application/json") }
        render("codeview-empty") { CodeView(value = "", contentType = "application/json") }
    }

    @Test
    fun `the editable editor, with a placeholder`() {
        render("codeeditor-long") { CodeEditor(value = json(600), onValueChange = {}, contentType = "application/json") }
        render("codeeditor-empty") { CodeEditor(value = "", onValueChange = {}, placeholder = "Request body") }
    }

    @Test
    fun `a body past the editable limit is a read-only view`() {
        val tooBig = (0..40_000).joinToString("\n") { "line $it, padded out well past the limit" }
        check(tooBig.length > EDITABLE_LIMIT) { "test fixture no longer exceeds the editable limit" }
        render("codeeditor-toobig") { CodeEditor(value = tooBig, onValueChange = {}, contentType = "text/plain") }
    }

    @Test
    fun `a diff of fifty thousand rows, both panes on one scroll`() {
        val rows = (0 until 50_000).map { i ->
            when (i % 7) {
                0 -> DiffRow(DiffKind.CHANGED, "\"a$i\": 1,", "\"a$i\": 2,", i, i)
                3 -> DiffRow(DiffKind.ADDED, null, "\"new$i\": true,", null, i)
                5 -> DiffRow(DiffKind.REMOVED, "\"old$i\": false,", null, i, null)
                else -> DiffRow(DiffKind.SAME, "\"k$i\": $i,", "\"k$i\": $i,", i, i)
            }
        }
        render("diff-large") { DiffView(DiffResult(rows, truncated = false), "application/json") }
    }

    @Test
    fun `two editors sharing a scroll state stay level`() {
        val scroll = EditorScrollState()
        val left = CodeEditorState(json(5_000), languageFor("application/json"), readOnly = true, scroll = scroll)
        val right = CodeEditorState(json(5_000), languageFor("application/json"), readOnly = true, scroll = scroll)
        val scene = ImageComposeScene(width = 700, height = 400, density = Density(1f)) {
            BitTraceTheme(dark = true) {
                Row(Modifier.fillMaxSize()) {
                    CodeSurface(left, Modifier.weight(1f))
                    CodeSurface(right, Modifier.weight(1f))
                }
            }
        }
        try {
            scene.render(0)
            scene.render(16_000_000L)
            assertTrue(scroll.maxY > 0.0, "the shared state learned the content height")
            scroll.scrollTo(y = 1_000.0)
            scene.render(32_000_000L)
            assertEquals(1_000.0, left.scroll.y)
            assertEquals(1_000.0, right.scroll.y)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a live editor swaps language in place and keeps its history`() {
        val state = CodeEditorState("{\"a\": 1}", languageFor("application/json"))
        render("swap-language", frames = 2) { CodeSurface(state, Modifier.fillMaxSize()) }
        state.moveDocumentEnd()
        state.type("x")
        state.language = languageFor("application/xml")
        render("swap-language-after", frames = 2) { CodeSurface(state, Modifier.fillMaxSize()) }
        assertTrue(state.history.canUndo)
        state.undo()
        assertEquals("{\"a\": 1}", state.text)
    }
}
