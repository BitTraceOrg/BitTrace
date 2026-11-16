package org.bittrace.ui.components.editor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindModelTest {

    @Test
    fun `finds every match, with and without case`() {
        assertContentEquals(intArrayOf(0, 8), findAll("Abc and abc", "abc", matchCase = false).let { intArrayOf(it[0], it[1]) })
        assertContentEquals(intArrayOf(8), findAll("Abc and abc", "abc", matchCase = true))
        assertContentEquals(intArrayOf(), findAll("abc", "", matchCase = false))
    }

    @Test
    fun `matches do not overlap`() {
        assertContentEquals(intArrayOf(0, 2), findAll("aaaa", "aa", matchCase = true))
    }

    @Test
    fun `results start at the match nearest the caret`() {
        val find = FindModel()
        find.setResults(intArrayOf(5, 20, 40), 3, nearOffset = 21)
        assertEquals(2, find.current)
        find.setResults(intArrayOf(5, 20, 40), 3, nearOffset = 41)
        assertEquals(0, find.current, "past the last match it wraps to the first")
    }

    @Test
    fun `the search is capped`() {
        val found = findAll("a".repeat(500), "a", matchCase = true, limit = 100)
        assertEquals(100, found.size)
        val find = FindModel()
        find.setResults(findAll("x".repeat(FindModel.MATCH_LIMIT + 5), "x", true), 1, 0)
        assertTrue(find.capped)
    }
}
