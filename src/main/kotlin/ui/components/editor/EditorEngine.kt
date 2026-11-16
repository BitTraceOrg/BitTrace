package org.bittrace.ui.components.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.math.ceil
import org.bittrace.plugin.theme.Palette

/**
 * What every editor in the app shares.
 *
 * An editor is per component — its document, its undo history, its caret —
 * but nothing else needs to be. The languages are immutable descriptions; the
 * colours are a function of the palette; the font metrics are a function of
 * the face, the size and the density; and highlighting is work that should be
 * paced across all the editors on screen together rather than by each one
 * racing the others for the frame. So those live here, once, and [init] builds
 * what it can at program start — before the first window opens — so the first
 * editor shown costs no more than the tenth.
 */
object EditorEngine {

    /**
     * Builds the shared, immutable parts. Called from `main` before the window
     * opens; idempotent, and everything here also builds lazily on first use,
     * so a test that never ran `main` still works.
     */
    fun init() {
        LanguageRegistry.init()
    }

    /** Text with no language: no tokens, no brackets, no folds. */
    val plain: EditorLanguage get() = LanguageRegistry.plain

    // --- colours ---

    @Volatile
    private var styleCache: Pair<Palette, EditorStyles>? = null

    /** The styles for [palette], built once per palette and shared by every editor. */
    fun styles(palette: Palette): EditorStyles {
        styleCache?.let { (p, s) -> if (p === palette) return s }
        return EditorStyles(palette).also { styleCache = palette to it }
    }

    // --- text layout ---

    private var measurerKey: Pair<FontFamily.Resolver, Density>? = null
    private var sharedMeasurer: TextMeasurer? = null
    private val metricsCache = HashMap<Pair<TextStyle, Float>, FontMetrics>()

    /**
     * The one [TextMeasurer] every editor draws through. Its layout cache is
     * what keeps a line that stays on screen from being shaped again every
     * frame, and sharing it means two editors showing the same body share that
     * too.
     */
    fun measurer(resolver: FontFamily.Resolver, density: Density): TextMeasurer {
        val key = resolver to density
        val existing = sharedMeasurer
        if (existing != null && measurerKey == key) return existing
        return TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = MEASURER_CACHE).also {
            sharedMeasurer = it
            measurerKey = key
        }
    }

    /** The grid for [style]: measured once per face, size and density. */
    fun metrics(measurer: TextMeasurer, style: TextStyle, density: Density): FontMetrics =
        metricsCache.getOrPut(style to density.density) {
            val sample = measurer.measure(AnnotatedString("M".repeat(SAMPLE)), style, softWrap = false, maxLines = 1)
            val width = sample.getHorizontalPosition(SAMPLE, usePrimaryDirection = true) / SAMPLE
            val fontHeight = sample.size.height.toFloat()
            // A little leading over the font's own height: a body is read line
            // by line, and lines set solid run together.
            val lineHeight = ceil(fontHeight * LINE_SPACING)
            FontMetrics(charWidth = width, lineHeight = lineHeight, textTop = ((lineHeight - fontHeight) / 2f))
        }

    // --- platform ---

    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    fun clipboardText(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    }.getOrNull()

    fun setClipboardText(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    private const val SAMPLE = 64
    private const val LINE_SPACING = 1.3f
    private const val MEASURER_CACHE = 1024
}

/**
 * Background highlighting for every editor, paced together.
 *
 * Driven by the frame clock rather than a thread of its own: each editor with
 * work outstanding waits for a frame, and the first to wake in that frame runs
 * one shared time slice across all of them — the ones whose screens are past
 * their frontier first. Everything therefore stays on the UI thread, where the
 * documents are edited, and nothing needs a lock; and however many editors are
 * open, highlighting never takes more than [BUDGET_NANOS] of a frame.
 */
object HighlightScheduler {
    private val active = LinkedHashSet<Highlighter>()
    private var lastFrame = Long.MIN_VALUE
    private var cursor = 0

    private const val BUDGET_NANOS = 4_000_000L

    /**
     * Runs for the life of an editor: whenever [signal] changes (the editor
     * bumps it after an edit or a language switch), works frame by frame until
     * [highlighter] has caught up.
     */
    suspend fun drive(highlighter: Highlighter, signal: State<Int>) {
        active.add(highlighter)
        try {
            snapshotFlow { signal.value }.collect {
                while (!highlighter.isComplete) {
                    withFrameNanos { frame -> tick(frame) }
                }
            }
        } finally {
            active.remove(highlighter)
        }
    }

    private fun tick(frame: Long) {
        if (frame == lastFrame) return
        lastFrame = frame
        val deadline = System.nanoTime() + BUDGET_NANOS
        val pending = active.filter { !it.isComplete }
        if (pending.isEmpty()) return
        // Editors whose screen is past their frontier are showing guesses; they go first.
        val urgent = pending.filter { it.visibleLast > it.frontier }
        val ordered = urgent + pending.filter { it !in urgent }.let { rest ->
            if (rest.isEmpty()) rest else rest.indices.map { rest[(it + cursor) % rest.size] }
        }
        cursor++
        for (h in ordered) {
            h.step(deadline)
            if (System.nanoTime() > deadline) break
        }
    }
}
