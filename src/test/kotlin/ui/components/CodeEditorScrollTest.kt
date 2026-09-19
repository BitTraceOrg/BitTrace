package org.bittrace.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.bittrace.ui.BitTraceTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The editor is scrolled by a container of ours rather than by its own line
 * list, which is what lets a scrollbar sit beside it — see `ScrolledEditor`.
 *
 * That arrangement hands KodeMirror an unbounded height, and an unbounded
 * height is the one thing its inner `LazyColumn` cannot be measured under: the
 * library guards it (`boundUnconstrainedHeight`, its issue #33) by substituting
 * the document's natural height. These tests exist because that guard is
 * load-bearing for us and lives in a dependency — if a future version drops it,
 * every body pane in the app throws on its first frame, and that should be a
 * red test rather than a blank inspector.
 *
 * Rendered through [ImageComposeScene], so composition, measure, layout and
 * draw all actually run. A failure surfaces as an exception out of [render].
 * Set `-Dbittrace.ui.dump=<dir>` to keep the frames as PNGs and look at them.
 */
class CodeEditorScrollTest {

    /** Comfortably taller than the pane, comfortably under the scrollbar limit. */
    private val longJson = json(600)

    /** Past the scrollbar limit, so it must fall back to the editor's own scrolling. */
    private val hugeJson = json(60_000)

    private fun json(entries: Int) = buildString {
        append("{\n")
        repeat(entries) { append("  \"key$it\": \"value$it\",\n") }
        append("  \"last\": true\n}")
    }

    /**
     * Renders [content] at a fixed size, failing the test if any phase throws.
     *
     * Two frames, not one: the first composition lays the document out, and the
     * scroll container only learns it has more content than room once that has
     * happened — so a scrollbar, which draws on the strength of exactly that,
     * cannot appear until the second.
     */
    private fun render(name: String, content: @Composable () -> Unit) {
        ImageComposeScene(width = 700, height = 400, density = Density(1f)) {
            BitTraceTheme(dark = true) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }.let { scene ->
            try {
                scene.render()
                val image = scene.render()
                val dump = System.getProperty("bittrace.ui.dump")
                if (dump != null) {
                    val dir = Path.of(dump).also { Files.createDirectories(it) }
                    image.encodeToData(EncodedImageFormat.PNG)?.bytes
                        ?.let { Files.write(dir.resolve("$name.png"), it) }
                }
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `a document taller than the pane lays out under our scroll container`() {
        render("codeview-long") { CodeView(value = longJson, contentType = "application/json") }
    }

    @Test
    fun `the plain view lays out too`() {
        render("codeview-plain") { CodeView(value = longJson, plain = true) }
    }

    @Test
    fun `a short document lays out, and an empty one`() {
        render("codeview-short") { CodeView(value = "{\n  \"ok\": true\n}", contentType = "application/json") }
        render("codeview-empty") { CodeView(value = "", contentType = "application/json") }
    }

    @Test
    fun `the editable editor lays out`() {
        render("codeeditor-long") {
            CodeEditor(value = longJson, onValueChange = {}, contentType = "application/json")
        }
    }

    @Test
    fun `a document past the scrollbar limit falls back instead of dying`() {
        // Unguarded this is an OutOfMemoryError, and past ~40k lines a
        // Constraints overflow — the whole reason the limit exists.
        render("codeview-huge") { CodeView(value = hugeJson, contentType = "application/json") }
    }

    @Test
    fun `the plain view past the limit falls back too`() {
        render("codeview-huge-plain") { CodeView(value = hugeJson, plain = true) }
    }

    @Test
    fun `a body past the editable limit lays out as a read-only view`() {
        val tooBig = (0..40_000).joinToString("\n") { "line $it, padded out well past the limit" }
        check(tooBig.length > EDITABLE_LIMIT) { "test fixture no longer exceeds the editable limit" }
        render("codeeditor-toobig") {
            CodeEditor(value = tooBig, onValueChange = {}, contentType = "text/plain")
        }
    }
}
