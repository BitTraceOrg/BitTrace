package org.bittrace.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.awt.Desktop
import java.net.URI
import org.bittrace.ui.P
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.NoOpCodeHighlighter
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.standalone.styling.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.github.tables.dark as darkTables
import org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.github.tables.light as lightTables
import org.jetbrains.jewel.intui.markdown.standalone.styling.light
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableStyling
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableRendererExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling

/**
 * Markdown, rendered by Jewel.
 *
 * This was a hand-rolled renderer — a line-driven parser over the subset a page
 * of notes tends to use. Jewel ships the real thing, built on CommonMark, at the
 * same version this app already pins, so the subset is now the whole language:
 * nested lists, reference links, setext headings and tables render as what they
 * are rather than as the characters they were typed with.
 *
 * Tables are their own artifact and need both halves — the processor extension
 * to parse the pipes, the renderer extension to draw the grid. One without the
 * other parses into blocks that nothing knows how to show.
 *
 * The styling is Jewel's stock Int UI one, dark or light to follow the app's
 * palette, and nothing more. Restyling it colour by colour meant reaching for a
 * dozen more experimental factories — one per block kind — to state what the
 * defaults already state for themselves.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val dark = P.palette.isDark
    val styling = remember(dark) { if (dark) MarkdownStyling.dark() else MarkdownStyling.light() }
    val tables = remember(dark, styling) {
        GitHubTableRendererExtension(
            if (dark) GfmTableStyling.darkTables() else GfmTableStyling.lightTables(),
            styling,
        )
    }
    // The renderer built directly rather than through the Int UI `dark()` and
    // `light()` factories: those differ only in the styling they default to,
    // which is passed in here anyway, and importing a third pair of `dark` and
    // `light` extensions would mean aliasing all of them.
    val renderer = remember(styling, tables) { DefaultMarkdownBlockRenderer(styling, listOf(tables)) }
    // The processor is independent of the theme — it parses, it does not draw —
    // so it survives a theme switch rather than being rebuilt by one.
    val processor = remember { MarkdownProcessor(listOf(GitHubTableProcessorExtension)) }

    ProvideMarkdownStyling(
        markdownStyling = styling,
        markdownBlockRenderer = renderer,
        // No syntax highlighting inside fenced blocks. The app has an editor
        // that does that, and wiring it in here would mean teaching Jewel's
        // highlighter interface about KodeMirror's languages for the sake of a
        // six-line JSON sample.
        codeHighlighter = NoOpCodeHighlighter,
        markdownProcessor = processor,
    ) {
        Markdown(
            text,
            modifier = modifier,
            // Not selectable, and that is a trade rather than an oversight:
            // selection puts the page in a `SelectionContainer`, which takes the
            // double-click for a word — and double-click is how the panel above
            // this opens the source. Copying is what the editor is for.
            selectable = false,
            onUrlClick = ::openInBrowser,
        )
    }
}

/**
 * Opens a link from the page in the system browser.
 *
 * Silent on failure, deliberately: a headless or locked-down desktop is not
 * something a documentation panel should report on, and the link is still on
 * the page to be copied.
 */
private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }
}
