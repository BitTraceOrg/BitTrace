package org.bittrace.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the line diff.
 *
 * A diff is the kind of thing that looks right on the screen while being wrong
 * in the corners — an off-by-one in a gutter, a change reported as a delete plus
 * an insert two rows apart — so the alignment is pinned here rather than left to
 * be noticed by eye.
 */
class DiffTest {

    @Test
    fun `identical input is all same`() {
        val lines = listOf("a", "b", "c")
        val result = diffLines(lines, lines)

        assertEquals(3, result.rows.size)
        assertTrue(result.rows.all { it.kind == DiffKind.SAME })
        assertFalse(result.truncated)
    }

    @Test
    fun `numbers each side against its own file`() {
        // The right file gains a line, so from there on the two gutters disagree
        // — which is the whole reason they are counted separately.
        val result = diffLines(listOf("a", "c"), listOf("a", "b", "c"))

        val added = result.rows.single { it.kind == DiffKind.ADDED }
        assertEquals(2, added.rightNumber)
        assertEquals(null, added.leftNumber)

        val last = result.rows.last()
        assertEquals(2, last.leftNumber)
        assertEquals(3, last.rightNumber)
    }

    @Test
    fun `a replaced line is one changed row, not a delete and an insert`() {
        val result = diffLines(listOf("a", "b", "c"), listOf("a", "B", "c"))

        val changed = result.rows.single { it.kind == DiffKind.CHANGED }
        assertEquals("b", changed.left)
        assertEquals("B", changed.right)
        assertEquals(2, changed.leftNumber)
        assertEquals(2, changed.rightNumber)
        // And nothing else moved.
        assertEquals(3, result.rows.size)
    }

    @Test
    fun `an uneven replacement pairs what it can and lists the rest`() {
        val result = diffLines(listOf("a", "x", "y", "z", "b"), listOf("a", "1", "b"))

        assertEquals(1, result.rows.count { it.kind == DiffKind.CHANGED })
        assertEquals(2, result.rows.count { it.kind == DiffKind.REMOVED })
        assertEquals(0, result.rows.count { it.kind == DiffKind.ADDED })
    }

    @Test
    fun `an empty side is entirely added`() {
        val result = diffLines(emptyList(), listOf("a", "b"))

        assertEquals(2, result.rows.size)
        assertTrue(result.rows.all { it.kind == DiffKind.ADDED })
        assertEquals(listOf(1, 2), result.rows.map { it.rightNumber })
    }

    @Test
    fun `a shared head and tail survive a change in the middle`() {
        val left = List(50) { "line $it" }
        val right = left.toMutableList().also { it[25] = "changed" }
        val result = diffLines(left, right)

        assertEquals(50, result.rows.size)
        assertEquals(1, result.rows.count { it.kind == DiffKind.CHANGED })
        assertEquals(26, result.rows.single { it.kind == DiffKind.CHANGED }.leftNumber)
    }

    @Test
    fun `two large unrelated files fall back rather than building the table`() {
        // Deliberately past the cell cap and sharing no head or tail, which is
        // the one case the exact alignment is not allowed to attempt.
        val left = List(2_500) { "left $it" }
        val right = List(2_500) { "right $it" }
        val result = diffLines(left, right)

        assertTrue(result.truncated)
        assertEquals(2_500, result.rows.size)
        assertTrue(result.rows.all { it.kind == DiffKind.CHANGED })
    }
}
