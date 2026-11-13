package org.bittrace.ui.components.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.bittrace.plugin.format.TokenKind
import org.bittrace.plugin.theme.Palette

/**
 * The editor's colours, from the app's palette.
 *
 * Everything reads off [Palette], so an external theme plugin recolours the
 * editor exactly as it recolours the rest of the app. Built once per palette
 * by [EditorEngine] and shared by every editor, and read at draw time — so a
 * theme switch repaints the editors rather than rebuilding them.
 */
class EditorStyles(palette: Palette) {
    val background: Color = palette.input
    val text: Color = palette.text
    val caret: Color = palette.accent
    val selection: Color = palette.sel

    /** A wash, not a fill: at a 1px-rule density a fill reads as a selected table row. */
    val activeLine: Color = palette.hover.copy(alpha = 0.35f)
    val gutterText: Color = palette.faint
    val gutterActiveText: Color = palette.dim
    val gutterBorder: Color = palette.line2
    val foldMarker: Color = palette.faint
    val foldPlaceholderText: Color = palette.dim
    val foldPlaceholderBackground: Color = palette.hover
    val searchMatch: Color = palette.accentFill
    val searchCurrent: Color = palette.accent.copy(alpha = 0.45f)

    /** "These brackets pair" and "this one does not" — the distinction the status bar draws with the same two colours. */
    val bracketMatch: Color = palette.ok.copy(alpha = 0.28f)
    val bracketMismatch: Color = palette.err.copy(alpha = 0.28f)
    val placeholder: Color = palette.faint
    val panel: Color = palette.panel
    val panelBorder: Color = palette.line
    val scrollThumb: Color = palette.faint.copy(alpha = 0.45f)
    val scrollThumbHover: Color = palette.dim.copy(alpha = 0.7f)

    /**
     * Token colours, by [TokenKind] ordinal.
     *
     * The convention every IDE has settled on, because a developer reading code
     * already has those associations: comments recede, strings are green,
     * numbers are cool, keywords are the one warm accent, and structure —
     * braces, commas — is dimmed, since colouring it as loudly as the tokens
     * between it is what makes a dense body hard to read.
     */
    val tokens: Array<SpanStyle?> = arrayOfNulls<SpanStyle>(TokenKind.entries.size).also { t ->
        t[TokenKind.COMMENT.ordinal] = SpanStyle(color = palette.faint, fontStyle = FontStyle.Italic)
        t[TokenKind.STRING.ordinal] = SpanStyle(color = palette.ok)
        t[TokenKind.NUMBER.ordinal] = SpanStyle(color = palette.info)
        t[TokenKind.LITERAL.ordinal] = SpanStyle(color = palette.info)
        t[TokenKind.KEYWORD.ordinal] = SpanStyle(color = palette.key, fontWeight = FontWeight.Medium)
        // A key in an object, an attribute on a tag — the left-hand side of a pair.
        t[TokenKind.PROPERTY.ordinal] = SpanStyle(color = palette.accent)
        t[TokenKind.ATTRIBUTE.ordinal] = SpanStyle(color = palette.accent)
        t[TokenKind.PUNCTUATION.ordinal] = SpanStyle(color = palette.dim)
        t[TokenKind.TAG.ordinal] = SpanStyle(color = palette.warn, fontWeight = FontWeight.Medium)
        t[TokenKind.TYPE.ordinal] = SpanStyle(color = palette.warn)
        t[TokenKind.VARIABLE.ordinal] = SpanStyle(color = palette.send)
        t[TokenKind.META.ordinal] = SpanStyle(color = palette.faint)
    }
}
