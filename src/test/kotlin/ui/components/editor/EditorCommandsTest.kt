package org.bittrace.ui.components.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The editing commands, driven without any UI. */
class EditorCommandsTest {

    private var now = 0L

    private fun editor(text: String = "", contentType: String = "") =
        CodeEditorState(text, languageFor(contentType)).also { it.clock = { now } }

    private fun CodeEditorState.typeAll(text: String) = text.forEach { type(it.toString()) }

    @Test
    fun `typing, then undo and redo`() {
        val e = editor()
        e.typeAll("hello")
        assertEquals("hello", e.text)
        assertTrue(e.undo())
        assertEquals("", e.text, "a run of typing undoes as one")
        assertTrue(e.redo())
        assertEquals("hello", e.text)
        assertEquals(5, e.selection.head)
    }

    @Test
    fun `a pause ends a typing run`() {
        val e = editor()
        e.typeAll("ab")
        now += 5_000
        e.typeAll("cd")
        e.undo()
        assertEquals("ab", e.text)
        e.undo()
        assertEquals("", e.text)
    }

    @Test
    fun `moving the caret ends a typing run`() {
        val e = editor()
        e.typeAll("ab")
        e.moveTo(0)
        e.typeAll("x")
        e.undo()
        assertEquals("ab", e.text)
        assertEquals(0, e.selection.head, "undo restores the caret from before the edit")
    }

    @Test
    fun `a new edit clears redo`() {
        val e = editor("abc")
        e.moveDocumentEnd()
        e.backspace()
        e.undo()
        assertTrue(e.history.canRedo)
        e.typeAll("z")
        assertFalse(e.history.canRedo)
    }

    @Test
    fun `backspace runs merge, and undo brings the text back`() {
        val e = editor("hello")
        e.moveDocumentEnd()
        repeat(3) { e.backspace() }
        assertEquals("he", e.text)
        e.undo()
        assertEquals("hello", e.text)
    }

    @Test
    fun `word deletion and movement`() {
        val e = editor("foo.bar  baz")
        e.moveDocumentEnd()
        e.backspace(word = true)
        assertEquals("foo.bar  ", e.text)
        e.moveDocumentStart()
        e.moveHorizontal(forward = true, word = true)
        assertEquals(3, e.selection.head)
        e.moveHorizontal(forward = true, word = true)
        assertEquals(4, e.selection.head)
        e.moveHorizontal(forward = true, word = true)
        assertEquals(7, e.selection.head)
    }

    @Test
    fun `enter keeps the indent, and indents once more after an opening bracket`() {
        val e = editor("  {}", "application/json")
        e.moveTo(3)
        e.newline()
        assertEquals("  {\n    \n  }", e.text)
        assertEquals(e.document.offsetOf(1, 4), e.selection.head)
        e.moveLineEnd()
        e.newline()
        assertEquals("  {\n    \n    \n  }", e.text)
    }

    @Test
    fun `tab indents to the next stop, shift-tab outdents selected lines`() {
        val e = editor("a\nb")
        e.moveTo(1)
        e.indent()
        assertEquals("a \nb", e.text)
        e.selectAll()
        e.indent()
        assertEquals("  a \n  b", e.text)
        e.outdent()
        assertEquals("a \nb", e.text)
        e.undo()
        assertEquals("  a \n  b", e.text)
    }

    @Test
    fun `read-only refuses every edit`() {
        val e = editor("keep")
        e.readOnly = true
        e.moveDocumentEnd()
        e.type("x")
        e.backspace()
        e.deleteForward()
        e.newline()
        e.indent()
        e.paste("y")
        assertFalse(e.cut())
        assertFalse(e.undo())
        assertEquals("keep", e.text)
        // Navigation and selection still work.
        e.selectAll()
        assertEquals("keep", e.selectedText())
    }

    @Test
    fun `vertical movement keeps its column across short lines`() {
        val e = editor("abcdef\nab\nabcdef")
        e.moveTo(5)
        e.moveVertical(1)
        assertEquals(e.document.offsetOf(1, 2), e.selection.head)
        e.moveVertical(1)
        assertEquals(e.document.offsetOf(2, 5), e.selection.head)
    }

    @Test
    fun `home goes to the first non-blank, then to the line start`() {
        val e = editor("    text")
        e.moveDocumentEnd()
        e.moveLineStart()
        assertEquals(4, e.selection.head)
        e.moveLineStart()
        assertEquals(0, e.selection.head)
    }

    @Test
    fun `setText from outside resets the history`() {
        val e = editor("a")
        e.moveDocumentEnd()
        e.typeAll("b")
        e.setText("other document")
        assertFalse(e.history.canUndo)
        assertEquals("other document", e.text)
    }

    @Test
    fun `switching language keeps the text and the history`() {
        val e = editor("{\"a\": 1}", "application/json")
        e.moveDocumentEnd()
        e.typeAll(" ")
        e.language = languageFor("application/xml")
        assertSame(languageFor("text/xml"), e.language)
        assertTrue(e.history.canUndo)
        e.undo()
        assertEquals("{\"a\": 1}", e.text)
    }

    @Test
    fun `edits are reported, setText is not`() {
        val e = editor("x")
        val seen = mutableListOf<String>()
        e.onTextChange = { seen += it }
        e.moveDocumentEnd()
        e.type("y")
        e.setText("z")
        assertEquals(listOf("xy"), seen)
    }

    @Test
    fun `brackets match through tokens, skipping strings`() {
        val e = editor("{\"a\": \"}\", \"b\": [1, 2]}", "application/json")
        // Lex everything so the token kinds are exact.
        e.highlighter.step(Long.MAX_VALUE)
        e.moveTo(0)
        val m = assertNotNull(e.bracketMatch())
        assertTrue(m.matched)
        assertEquals(e.text.length - 1, m.other, "the } inside the string is not a bracket")
        e.moveTo(e.text.indexOf(']') + 1)
        val inner = assertNotNull(e.bracketMatch())
        assertEquals(e.text.indexOf('['), inner.other)
    }

    @Test
    fun `an unclosed bracket is reported as unmatched`() {
        val e = editor("[1, 2", "application/json")
        e.moveTo(0)
        val m = assertNotNull(e.bracketMatch())
        assertFalse(m.matched)
        e.moveTo(2)
        assertNull(e.bracketMatch())
    }

    @Test
    fun `folding a JSON object hides its body and keeps the closer`() {
        val e = editor("{\n  \"a\": {\n    \"b\": 1\n  },\n  \"c\": 2\n}", "application/json")
        assertTrue(e.canFold(0))
        assertTrue(e.canFold(1))
        assertFalse(e.canFold(2))
        assertTrue(e.toggleFold(1))
        assertEquals(listOf(2..2), e.folds.foldRanges())
        assertTrue(e.toggleFold(0))
        assertEquals(listOf(1..4), e.folds.foldRanges(), "the outer fold absorbs the inner one")
        assertEquals(2, e.folds.rowCount(e.document.lineCount))
        assertEquals(5, e.folds.lineOfRow(1))
    }

    @Test
    fun `moving into a fold reveals it`() {
        val e = editor("{\n  \"a\": 1,\n  \"b\": 2\n}", "application/json")
        e.toggleFold(0)
        assertTrue(e.folds.isHidden(1))
        e.moveTo(e.document.offsetOf(2, 1))
        assertFalse(e.folds.isHidden(1))
    }

    @Test
    fun `folding is off when the surface says so`() {
        val e = editor("{\n  \"a\": 1,\n  \"b\": 2\n}", "application/json")
        e.foldingEnabled = false
        assertFalse(e.canFold(0))
        assertFalse(e.toggleFold(0))
    }

    @Test
    fun `xml folds by tag`() {
        val e = editor("<a>\n  <b>\n    <c/>\n  </b>\n  <d x=\"1\"\n     y=\"2\">\n  </d>\n</a>", "application/xml")
        assertTrue(e.canFold(0))
        assertEquals(1..6, e.structure.foldRange(0))
        assertEquals(2..2, e.structure.foldRange(1))
        assertFalse(e.canFold(2), "a self-closing tag folds nothing")
        assertEquals(5..5, e.structure.foldRange(4), "a tag whose attributes run over lines still folds")
    }

    @Test
    fun `find selects matches in turn`() {
        val e = editor("one two one two one")
        val found = findAll(e.text, "one", matchCase = true)
        e.find.setResults(found, 3, 0)
        e.find.current = -1
        e.findNext()
        assertEquals(Selection(0, 3), e.selection)
        e.findNext()
        assertEquals(Selection(8, 11), e.selection)
        e.findNext(forward = false)
        assertEquals(Selection(0, 3), e.selection)
        e.findNext(forward = false)
        assertEquals(Selection(16, 19), e.selection, "wraps around")
    }
}
