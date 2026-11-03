package org.bittrace.ui.components.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TextDocumentTest {

    @Test
    fun `lines and offsets agree`() {
        val doc = TextDocument("ab\ncde\n\nf")
        assertEquals(4, doc.lineCount)
        assertEquals(listOf("ab", "cde", "", "f"), (0 until doc.lineCount).map(doc::line))
        assertEquals(9, doc.length)
        assertEquals(listOf(0, 3, 7, 8), (0 until 4).map(doc::lineStart))
        assertEquals(0, doc.lineOf(0))
        assertEquals(0, doc.lineOf(2), "the offset of a line break belongs to the line it ends")
        assertEquals(1, doc.lineOf(3))
        assertEquals(2, doc.lineOf(7))
        assertEquals(3, doc.lineOf(9))
        assertEquals(5, doc.offsetOf(1, 2))
        assertEquals('\n', doc.charAt(2))
        assertEquals("b\ncd", doc.substring(1, 5))
    }

    @Test
    fun `every kind of line break splits, and text joins with newlines`() {
        val doc = TextDocument("a\r\nb\rc\nd")
        assertEquals(listOf("a", "b", "c", "d"), (0 until doc.lineCount).map(doc::line))
        assertEquals("a\nb\nc\nd", doc.text())
    }

    @Test
    fun `text without carriage returns is handed back as is`() {
        val body = "{\n  \"a\": 1\n}"
        assertSame(body, TextDocument(body).text())
    }

    @Test
    fun `replace within a line`() {
        val doc = TextDocument("hello world")
        val change = doc.replace(6, 11, "there")
        assertEquals("hello there", doc.text())
        assertEquals("world", change.removed)
        assertEquals(0, change.lineDelta)
    }

    @Test
    fun `replace across lines reports the line span either side`() {
        val doc = TextDocument("one\ntwo\nthree\nfour")
        val change = doc.replace(2, 9, "X\nY\nZ")
        assertEquals("onX\nY\nZhree\nfour", doc.text())
        assertEquals(0, change.firstLine)
        assertEquals(2, change.oldLastLine)
        assertEquals(2, change.newLastLine)
        assertEquals("e\ntwo\nt", change.removed)
        assertEquals(doc.text().length, doc.length)
        assertEquals(listOf(0, 4, 6, 12), (0 until doc.lineCount).map(doc::lineStart))
    }

    @Test
    fun `inserting and deleting lines keeps the start table right`() {
        val doc = TextDocument((0 until 100).joinToString("\n") { "line $it" })
        doc.replace(doc.lineStart(50), doc.lineStart(50), "new\nlines\n")
        assertEquals(102, doc.lineCount)
        assertEquals("line 50", doc.line(52))
        assertEquals(doc.text().indexOf("line 99"), doc.lineStart(101))
        doc.replace(doc.lineStart(10), doc.lineStart(20), "")
        assertEquals(92, doc.lineCount)
        assertEquals("line 20", doc.line(10))
        assertEquals(doc.text().indexOf("line 20"), doc.lineStart(10))
    }

    @Test
    fun `inserted text is normalised too`() {
        val doc = TextDocument("ab")
        doc.replace(1, 1, "x\r\ny")
        assertEquals("ax\nyb", doc.text())
        assertEquals(2, doc.lineCount)
    }

    @Test
    fun `an empty document is one empty line`() {
        val doc = TextDocument("")
        assertEquals(1, doc.lineCount)
        assertEquals(0, doc.length)
        assertEquals(0, doc.lineOf(0))
    }
}
