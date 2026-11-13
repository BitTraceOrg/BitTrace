package org.bittrace.ui.components.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldModelTest {

    @Test
    fun `rows and lines map both ways around folds`() {
        val folds = FoldModel()
        folds.fold(2, 4) // hides 3, 4
        folds.fold(10, 14) // hides 11..14
        assertEquals(20 - 6, folds.rowCount(20))
        // rows: 0 1 2 [3,4 hidden] 5..10 [11..14 hidden] 15..19
        val expected = listOf(0, 1, 2, 5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 19)
        assertEquals(expected, (0 until folds.rowCount(20)).map(folds::lineOfRow))
        for ((row, line) in expected.withIndex()) assertEquals(row, folds.rowOfLine(line))
        assertEquals(2, folds.rowOfLine(3), "a hidden line maps to its fold's head")
        assertTrue(folds.isHidden(12))
        assertFalse(folds.isHidden(10))
        assertFalse(folds.isHidden(15))
    }

    @Test
    fun `a fold around folds absorbs them`() {
        val folds = FoldModel()
        folds.fold(3, 4)
        folds.fold(6, 8)
        folds.fold(1, 9)
        assertEquals(listOf(2..9), folds.foldRanges())
    }

    @Test
    fun `edits shift the folds after them and drop the ones they touch`() {
        val folds = FoldModel()
        folds.fold(2, 4)
        folds.fold(10, 12)
        // Two lines inserted at line 6.
        folds.onChange(Change(0, "", "\n\n", firstLine = 6, oldLastLine = 6, newLastLine = 8))
        assertEquals(listOf(3..4, 13..14), folds.foldRanges())
        // An edit inside the first fold drops it.
        folds.onChange(Change(0, "x", "", firstLine = 3, oldLastLine = 3, newLastLine = 3))
        assertEquals(listOf(13..14), folds.foldRanges())
    }

    @Test
    fun `reveal unfolds what hides a line`() {
        val folds = FoldModel()
        folds.fold(0, 5)
        assertTrue(folds.reveal(3))
        assertEquals(0, folds.count)
        assertFalse(folds.reveal(3))
    }
}
