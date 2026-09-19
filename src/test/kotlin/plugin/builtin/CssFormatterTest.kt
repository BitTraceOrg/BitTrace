package org.bittrace.plugin.builtin

import org.bittrace.plugin.format.TokenKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CSS chip: what it claims, how it re-indents, and how it classifies.
 *
 * Most of these pin the one thing CSS does differently from the other
 * brace-and-semicolon languages this shares a formatter with — it has no `//`
 * comment, and the *same word* means different things on either side of a
 * colon. Both are easy to regress into "looks fine on a stylesheet that was
 * already formatted".
 */
class CssFormatterTest {

    private val css = CssFormatter()

    private fun format(text: String) = css.format(text.toByteArray(), "text/css")

    private fun kindsOf(text: String): Map<String, TokenKind> =
        highlightCss(text).associate { text.substring(it.start, it.end) to it.kind }

    // --- what it claims -----------------------------------------------------

    @Test
    fun `claims css and not the neighbours`() {
        assertTrue(css.handles("text/css"))
        assertTrue(css.handles("text/css; charset=utf-8"))
        assertFalse(css.handles("text/html"))
        assertFalse(css.handles("application/json"))
    }

    // --- formatting ---------------------------------------------------------

    @Test
    fun `a minified rule becomes one declaration per line`() {
        val out = format("body{color:red;margin:0}")

        // No space before the brace: `indentCode` re-indents, it does not
        // restyle, and the JS and GraphQL chips read the same way.
        assertEquals(
            listOf("body{", "  color:red;", "  margin:0", "}"),
            out.trim().lines().map { it.trimEnd() },
        )
    }

    @Test
    fun `nested at-rules indent their inner rules`() {
        val out = format("@media (min-width:600px){.a{color:red}}")

        // The inner rule sits a level deeper than the block that holds it.
        assertContains(out, "\n  .a{")
        assertContains(out, "\n    color:red")
    }

    @Test
    fun `a comment survives formatting intact`() {
        val out = format("/* brand */ a{color:blue}")

        assertContains(out, "/* brand */")
    }

    @Test
    fun `a double slash is left alone rather than eating the line`() {
        // Not a comment in CSS. Treating it as one would hide the declaration
        // after it, which is exactly the error the reader is looking for.
        val out = format("a{color:red} // oops\nb{color:blue}")

        // The text is kept and the rule after it still formats. It shares a
        // line with the stray `//`, which is honest: nothing here is a comment.
        assertContains(out, "// oops")
        assertContains(out, "b{")
        assertContains(out, "color:blue")
    }

    @Test
    fun `an empty body formats to nothing rather than failing`() {
        assertEquals("", format(""))
    }

    @Test
    fun `binary is reported rather than mangled`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01, 0x02)

        assertEquals("Body is not text.", css.format(png, "text/css"))
    }

    // --- highlighting -------------------------------------------------------

    @Test
    fun `the same word is a property before the colon and a value after it`() {
        // `color` and `red` are both plain words; only position tells them
        // apart, which is the whole basis of this highlighter.
        val kinds = kindsOf("a { color: red; }")

        assertEquals(TokenKind.PROPERTY, kinds["color"])
        assertEquals(TokenKind.LITERAL, kinds["red"])
    }

    @Test
    fun `a selector is not read as a declaration`() {
        val kinds = kindsOf("body { color: red; }")

        assertEquals(TokenKind.TAG, kinds["body"])
    }

    @Test
    fun `classes, ids and at-rules keep their sigil`() {
        val kinds = kindsOf("@media screen { .card { color: red } }")

        assertEquals(TokenKind.KEYWORD, kinds["@media"])
        assertEquals(TokenKind.ATTRIBUTE, kinds[".card"])
    }

    @Test
    fun `a hex colour is a number and an id selector is not`() {
        assertEquals(TokenKind.NUMBER, kindsOf("a { color: #fff; }")["#fff"])
        assertEquals(TokenKind.ATTRIBUTE, kindsOf("#main { color: red }")["#main"])
    }

    @Test
    fun `a unit belongs to the number it follows`() {
        // One span, not a number beside a mystery keyword.
        val kinds = kindsOf("a { margin: 16px; }")

        assertEquals(TokenKind.NUMBER, kinds["16px"])
        assertFalse("px" in kinds, "the unit should not be a span of its own: $kinds")
    }

    @Test
    fun `a pseudo-class is marked in the selector`() {
        assertEquals(TokenKind.META, kindsOf("a:hover { color: red }")[":hover"])
    }

    @Test
    fun `a block comment is one span whatever is inside it`() {
        val kinds = kindsOf("/* a { color: red } */ b { color: blue }")

        assertEquals(TokenKind.COMMENT, kinds["/* a { color: red } */"])
        // The rule after it is still classified — the comment closed.
        assertEquals(TokenKind.TAG, kinds["b"])
    }

    @Test
    fun `a string value is a string`() {
        assertEquals(TokenKind.STRING, kindsOf("""a { content: "x" }""")["\"x\""])
    }

    @Test
    fun `spans stay ordered and inside the text`() {
        val text = "@media screen { .a:hover { margin: 16px; color: #fff } }"
        val spans = highlightCss(text)

        var previous = 0
        spans.forEach { span ->
            assertTrue(span.start >= previous, "spans overlap or run backwards at ${span.start}")
            assertTrue(span.end <= text.length, "span past the end of the text")
            previous = span.end
        }
    }
}
