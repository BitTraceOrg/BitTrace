package org.bittrace.ui.components.editor

import org.bittrace.plugin.format.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlighterTest {

    private fun kinds(h: Highlighter, doc: TextDocument, line: Int): List<Pair<String, TokenKind>> {
        val out = mutableListOf<Pair<String, TokenKind>>()
        val text = doc.line(line)
        h.forEachToken(line, 0, text.length) { s, e, k -> out += text.substring(s, e) to k }
        return out
    }

    private fun Highlighter.finish() {
        while (step(Long.MAX_VALUE)) Unit
    }

    @Test
    fun `opening a block comment recolours the lines after it, and closing it converges`() {
        val doc = TextDocument((0 until 50).joinToString("\n") { "let v$it = $it;" })
        val h = Highlighter(doc, languageFor("application/javascript"))
        h.finish()
        assertEquals(TokenKind.KEYWORD, kinds(h, doc, 10).first().second)

        val open = doc.replace(doc.lineStart(5), doc.lineStart(5), "/* ")
        h.onChange(open)
        h.finish()
        assertTrue(kinds(h, doc, 10).all { it.second == TokenKind.COMMENT }, "everything after /* is comment")

        val close = doc.replace(doc.lineEnd(7), doc.lineEnd(7), " */")
        h.onChange(close)
        h.finish()
        assertEquals(TokenKind.KEYWORD, kinds(h, doc, 10).first().second, "after */ the code is code again")
        assertTrue(kinds(h, doc, 6).all { it.second == TokenKind.COMMENT })
    }

    @Test
    fun `an edit that changes no state does not re-lex the rest of the document`() {
        val doc = TextDocument((0 until 10_000).joinToString("\n") { "\"k$it\": $it," })
        val h = Highlighter(doc, languageFor("application/json"))
        h.finish()
        assertTrue(h.isComplete)
        h.onChange(doc.replace(doc.lineStart(10), doc.lineStart(10), "  "))
        assertFalse(h.isComplete)
        // One line to re-lex, then the old states are confirmed and it jumps.
        val deadline = System.nanoTime() + 50_000_000
        h.step(deadline)
        assertTrue(h.isComplete)
    }

    @Test
    fun `lines past the frontier are drawn from a guessed state`() {
        val doc = TextDocument("/*\n" + "x\n".repeat(10) + "*/\nlet a")
        val h = Highlighter(doc, languageFor("application/javascript"))
        assertFalse(h.isExact(5))
        // The guess is the initial state, so before highlighting line 5 is code.
        assertTrue(kinds(h, doc, 5).none { it.second == TokenKind.COMMENT })
        h.finish()
        assertTrue(kinds(h, doc, 5).all { it.second == TokenKind.COMMENT })
    }

    @Test
    fun `switching language resets the states`() {
        val doc = TextDocument("<a>\n/* x */\n</a>")
        val h = Highlighter(doc, languageFor("application/javascript"))
        h.finish()
        assertEquals(TokenKind.COMMENT, kinds(h, doc, 1).single().second)
        h.setLanguage(languageFor("application/xml"))
        assertEquals(0, h.frontier)
        h.finish()
        assertTrue(kinds(h, doc, 1).none { it.second == TokenKind.COMMENT })
        assertEquals(TokenKind.TAG, kinds(h, doc, 0).first { it.first == "a" }.second)
    }

    @Test
    fun `a very long line is lexed a chunk at a time and agrees with a short one`() {
        val unit = "{\"key\": \"value\", \"n\": 12, \"b\": true}, "
        val long = "[" + unit.repeat(20_000) + "1]"
        val doc = TextDocument(long)
        val h = Highlighter(doc, languageFor("application/json"))
        h.finish()
        // A window in the middle of a 760k-char line.
        val from = long.length / 2
        val to = from + 200
        val window = mutableListOf<Triple<Int, Int, TokenKind>>()
        h.forEachToken(0, from, to) { s, e, k -> window += Triple(s, e, k) }
        assertTrue(window.isNotEmpty())
        // The same tokens a one-shot lex of the whole line yields, in that window.
        val reference = mutableListOf<Triple<Int, Int, TokenKind>>()
        JsonTokenizer.tokenize(long, 0, long.length, 0, { s, e, k -> if (e > from && s < to) reference += Triple(s, e, k) }, TokenRun())
        assertEquals(reference, window.filter { it.second > from && it.first < to })
    }

    @Test
    fun `deleting lines moves the states with them`() {
        val doc = TextDocument("/*\na\n*/\nlet x\nlet y")
        val h = Highlighter(doc, languageFor("application/javascript"))
        h.finish()
        h.onChange(doc.replace(doc.lineStart(1), doc.lineStart(2), ""))
        h.finish()
        assertEquals(TokenKind.KEYWORD, kinds(h, doc, 2).first().second)
        assertEquals(TokenKind.KEYWORD, kinds(h, doc, 3).first().second)
    }
}
