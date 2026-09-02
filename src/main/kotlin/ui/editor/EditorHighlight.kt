package org.bittrace.ui.editor

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.monkopedia.kodemirror.language.HighlightStyle
import com.monkopedia.kodemirror.lezer.highlight.Tags
import org.bittrace.plugin.theme.Palette

/**
 * Syntax colours, from the app's palette.
 *
 * The editor's bundled `defaultHighlightStyle` says in its own doc that it
 * "works well with light themes" — and `basicSetup` applies it whatever theme is
 * loaded. On a near-black background its keyword purple is `#770088`, which is
 * barely separable from the background and unreadable at 12sp.
 *
 * So this is the last piece of the editor to come off [Palette] rather than off
 * a constant, joining the theme in `EditorTheming.kt`. Every colour below is a
 * role the app already had a name for, which is what keeps a body of JSON in the
 * inspector the same colours as the grid row above it — and what lets an
 * external theme plugin recolour code, not just chrome.
 *
 * The assignments follow the convention every IDE has settled on, because a
 * developer reading code has those associations already: comments recede,
 * strings are green, numbers are cool, keywords are the one warm accent.
 */
fun appHighlightStyle(palette: Palette): HighlightStyle = HighlightStyle.define {
    // Recede. A comment is there when you look for it and not before, which is
    // the one case where low contrast is the point rather than a fault.
    listOf(Tags.comment, Tags.lineComment, Tags.blockComment, Tags.docComment) styles
        SpanStyle(color = palette.faint, fontStyle = FontStyle.Italic)

    // The language's own words.
    listOf(
        Tags.keyword,
        Tags.controlKeyword,
        Tags.definitionKeyword,
        Tags.moduleKeyword,
        Tags.operatorKeyword,
        Tags.modifier,
        Tags.self,
    ) styles SpanStyle(color = palette.key, fontWeight = FontWeight.Medium)

    listOf(Tags.string, Tags.docString, Tags.character, Tags.attributeValue) styles
        SpanStyle(color = palette.ok)

    // Values that are not text: cool, and distinct from the strings beside them.
    listOf(
        Tags.number,
        Tags.integer,
        Tags.float,
        Tags.bool,
        Tags.atom,
        Tags.unit,
        // `constant` is a modifier over a tag rather than a tag of its own.
        Tags.constant(Tags.variableName),
    ) styles SpanStyle(color = palette.info)

    Tags.`null` styles SpanStyle(color = palette.info, fontStyle = FontStyle.Italic)

    // A key in an object, an attribute on a tag — the left-hand side of a pair,
    // which is what you scan a JSON body for.
    listOf(Tags.propertyName, Tags.attributeName, Tags.labelName) styles
        SpanStyle(color = palette.accent)

    listOf(Tags.typeName, Tags.className, Tags.namespace, Tags.macroName) styles
        SpanStyle(color = palette.warn)

    Tags.tagName styles SpanStyle(color = palette.warn, fontWeight = FontWeight.Medium)

    // A name being *defined* stands out from the same name used later.
    Tags.definition(Tags.variableName) styles SpanStyle(color = palette.accent)
    listOf(Tags.variableName, Tags.name) styles SpanStyle(color = palette.text)

    listOf(Tags.escape, Tags.regexp) styles SpanStyle(color = palette.send)

    // Structure, dimmed. Braces and commas are how code is shaped, not what it
    // says, and colouring them as loudly as the tokens between them is what
    // makes a dense body hard to read.
    listOf(
        Tags.operator,
        Tags.derefOperator,
        Tags.arithmeticOperator,
        Tags.logicOperator,
        Tags.bitwiseOperator,
        Tags.compareOperator,
        Tags.updateOperator,
        Tags.definitionOperator,
        Tags.typeOperator,
        Tags.controlOperator,
        Tags.punctuation,
        Tags.separator,
        Tags.bracket,
        Tags.angleBracket,
        Tags.squareBracket,
        Tags.paren,
        Tags.brace,
    ) styles SpanStyle(color = palette.dim)

    listOf(Tags.meta, Tags.documentMeta, Tags.annotation, Tags.processingInstruction) styles
        SpanStyle(color = palette.faint)

    // Anything the parser could not place. Loud on purpose: in a captured body
    // it usually means the response is not the format it claimed to be.
    Tags.invalid styles SpanStyle(color = palette.err)

    // Markup, for the formatters that emit it.
    Tags.link styles SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline)
    Tags.url styles SpanStyle(color = palette.accent)
    Tags.heading styles SpanStyle(color = palette.text, fontWeight = FontWeight.Bold)
    Tags.emphasis styles SpanStyle(fontStyle = FontStyle.Italic)
    Tags.strong styles SpanStyle(fontWeight = FontWeight.Bold)
    Tags.strikethrough styles SpanStyle(textDecoration = TextDecoration.LineThrough)
    Tags.inserted styles SpanStyle(color = palette.ok)
    Tags.deleted styles SpanStyle(color = palette.err)
    Tags.changed styles SpanStyle(color = palette.warn)
}
