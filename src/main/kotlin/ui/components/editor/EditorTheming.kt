package org.bittrace.ui.components.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.monkopedia.kodemirror.lang.html.html
import com.monkopedia.kodemirror.lang.javascript.javascript
import com.monkopedia.kodemirror.lang.json.json
import com.monkopedia.kodemirror.lang.xml.xml
import com.monkopedia.kodemirror.autocomplete.completionBackground
import com.monkopedia.kodemirror.autocomplete.completionDetailColor
import com.monkopedia.kodemirror.autocomplete.completionIconColor
import com.monkopedia.kodemirror.autocomplete.completionSelectedBackground
import com.monkopedia.kodemirror.autocomplete.completionTextColor
import com.monkopedia.kodemirror.autocomplete.snippetFieldActiveBackground
import com.monkopedia.kodemirror.autocomplete.snippetFieldBackground
import com.monkopedia.kodemirror.language.LanguageSupport
import com.monkopedia.kodemirror.language.syntaxHighlighting
import com.monkopedia.kodemirror.language.StreamLanguage
import com.monkopedia.kodemirror.language.StreamParser
import com.monkopedia.kodemirror.language.StringStream
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.view.EditorLayout
import com.monkopedia.kodemirror.view.EditorTheme
import com.monkopedia.kodemirror.view.editorContentStyle
import com.monkopedia.kodemirror.view.editorTheme
import com.monkopedia.kodemirror.view.specialCharBackground
import com.monkopedia.kodemirror.view.specialCharForeground
import com.monkopedia.kodemirror.view.themeExtras
import com.monkopedia.kodemirror.view.trailingWhitespaceBackground
import com.monkopedia.kodemirror.view.whitespaceColor
import org.bittrace.plugin.theme.Palette
import org.bittrace.ui.P
import org.bittrace.ui.Typo

/**
 * The app's palette, as a KodeMirror theme.
 *
 * The same job `ui/JewelBridge.kt` does for Jewel, and for the same reason: the
 * editor ships seventeen themes of its own, none of which is the one the user
 * picked. Everything here reads off [Palette], so an external theme plugin
 * recolours the editor exactly as it recolours the rest of the app — which is
 * the property that would have been lost by adopting a bundled theme.
 *
 * Almost every field maps onto a token the app already had a name for. The two
 * that did not — the bracket-match washes — are built from `ok` and `err`,
 * because "these brackets pair" and "this one does not" is the same distinction
 * the status bar draws with those two colours.
 */
fun editorThemeFor(palette: Palette): EditorTheme = EditorTheme(
    background = palette.input,
    foreground = palette.text,
    cursor = palette.accent,
    selection = palette.sel,
    // The active line is a wash, not a fill: at a 1px-rule density it would
    // otherwise read as a selected row in a table.
    activeLineBackground = palette.hover.copy(alpha = 0.35f),
    activeLineGutterBackground = palette.hover.copy(alpha = 0.35f),
    // The same tone as the content, deliberately. The editor paints its
    // backgrounds per line, so a gutter in its own colour stops at the last
    // line and leaves a seam across the pane — which reads as the editor not
    // filling its space, because that is exactly what it looks like.
    gutterBackground = palette.input,
    gutterForeground = palette.faint,
    gutterActiveForeground = palette.dim,
    gutterBorderColor = palette.line2,
    searchMatchBackground = palette.accentFill,
    searchMatchSelectedBackground = palette.accent.copy(alpha = 0.45f),
    selectionMatchBackground = palette.hover,
    matchingBracketBackground = palette.ok.copy(alpha = 0.28f),
    nonMatchingBracketBackground = palette.err.copy(alpha = 0.28f),
    panelBackground = palette.panel,
    panelBorderColor = palette.line,
    buttonBackground = palette.chrome,
    buttonBorderColor = palette.outline,
    inputBackground = palette.input,
    inputBorderColor = palette.line,
    tooltipBackground = palette.panel,
    foldPlaceholderColor = palette.dim,
    foldPlaceholderBackground = palette.hover,
    dark = palette.isDark,
    // The keys the editor looks up rather than reads off the theme: the
    // completion popup and the whitespace marks. Left unset they
    // keep One Dark's own values, which is why the completion list came up dark
    // on a light theme — a theme that covers the editor but not its popups is
    // not a theme.
    extras = themeExtras(
        completionBackground to palette.panel,
        completionSelectedBackground to palette.sel,
        completionTextColor to palette.text,
        completionDetailColor to palette.faint,
        completionIconColor to palette.accent,
        snippetFieldBackground to palette.accentFill,
        snippetFieldActiveBackground to palette.sel,
        whitespaceColor to palette.faint.copy(alpha = 0.4f),
        trailingWhitespaceBackground to palette.err.copy(alpha = 0.25f),
        specialCharForeground to palette.bg,
        specialCharBackground to palette.err,
    ),
    // Tighter than the editor's own defaults, which are sized for a full IDE
    // pane rather than for a body viewer inside a split.
    layout = EditorLayout(
        gutterStartPadding = 6.dp,
        gutterEndPadding = 6.dp,
        contentTopPadding = 2.dp,
        contentBottomPadding = 6.dp,
    ),
)

/** The theme and the text style, as the one extension every editor here uses. */
@Composable
fun editorAppearance(): Extension = extensionListOf(
    editorTheme.of(editorThemeFor(P.palette)),
    // Ours wins over the one `basicSetup` installs: that one registers at
    // `Prec.lowest` because it passes `fallback = true`, and this at `Prec.high`
    // because it does not. Without it the editor would keep the bundled style,
    // whose own doc says it suits light themes — and which is unreadable on a
    // dark one.
    syntaxHighlighting(appHighlightStyle(P.palette)),
    // The app's own code style, so an editor and the grid beside it are set in
    // the same face at the same size.
    editorContentStyle.of(TextStyle(fontFamily = P.Mono, fontSize = Typo.label.fontSize)),
)

/**
 * The language for a content type, falling back to plain text.
 *
 * Never null, and that is not tidiness: `basicSetup` throws outright when no
 * language facet is configured, so "no language" has to be a language rather
 * than an absence. The matching is the loose kind the body formatters use — a
 * content type in the wild is `application/vnd.api+json; charset=utf-8` far
 * more often than it is `application/json`.
 */
fun languageFor(contentType: String): LanguageSupport {
    val type = contentType.lowercase()
    return when {
        type.contains("graphql") -> graphql()
        type.contains("json") -> json()
        type.contains("html") -> html()
        type.contains("xml") -> xml()
        type.contains("typescript") -> javascript(typescript = true)
        type.contains("javascript") || type.contains("ecmascript") -> javascript()
        else -> plainText()
    }
}

/**
 * A language that says nothing about its text.
 *
 * Wanted for the Hex tab and for any body no formatter claims — a hex dump is
 * columns of digits, and colouring it by guessing at a language would be worse
 * than leaving it alone. It exists because the editor requires *a* language, and
 * because going without one would also mean going without the gutter, the search
 * panel and the rest of `basicSetup`, which plain text wants as much as anything
 * else does.
 */
fun plainText(): LanguageSupport = LanguageSupport(StreamLanguage.define(plainTextParser))

private val plainTextParser: StreamParser<Unit> = object : StreamParser<Unit> {
    override val name: String get() = "text"

    override fun startState(indentUnit: Int) = Unit

    override fun copyState(state: Unit) = Unit

    /** One token a line, carrying no name — the whole line is content. */
    override fun token(stream: StringStream, state: Unit): String? {
        stream.skipToEnd()
        return null
    }
}
