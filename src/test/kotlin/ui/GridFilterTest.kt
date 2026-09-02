package org.bittrace.ui

import org.bittrace.components.CONTENT_KINDS
import org.bittrace.components.DEFAULT_COLUMN_KEYS
import org.bittrace.components.HTTP_METHOD_FACETS
import org.bittrace.components.STATUS_CLASSES
import org.bittrace.components.columnCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the flow table's filters.
 *
 * The first of these exists because of a real crash: `DEFAULT_COLUMN_KEYS` runs
 * `defaultColumns()` while the file is still initialising, so a facet list
 * declared below it was still null when a column asked for one. Touching the
 * catalog at all is what catches that — the failure was a null check on a
 * parameter that has a default, thrown several frames from its cause.
 */
class GridFilterTest {

    @Test
    fun `the column catalog builds`() {
        // Forces the file's initialisation, which is the whole point.
        val catalog = columnCatalog()

        assertTrue(catalog.isNotEmpty())
        assertTrue(DEFAULT_COLUMN_KEYS.isNotEmpty())
    }

    @Test
    fun `the closed-set columns carry their facets`() {
        val byKey = columnCatalog().associateBy { it.key }

        // Declared rather than derived: a list built from captured traffic
        // cannot offer 5xx until a 5xx has happened.
        assertEquals(STATUS_CLASSES, byKey.getValue("st").facets)
        assertEquals(HTTP_METHOD_FACETS, byKey.getValue("method").facets)
        assertEquals(CONTENT_KINDS, byKey.getValue("type").facets)
    }

    @Test
    fun `size is the column that compares`() {
        val byKey = columnCatalog().associateBy { it.key }

        assertTrue(byKey.getValue("size").numeric != null)
        // And nothing else is: a comparison on a URL means nothing.
        assertNull(byKey.getValue("url").numeric)
    }

    // --- the size operand ---------------------------------------------------

    @Test
    fun `a plain number is bytes`() {
        assertEquals(2048L, parseSize("2048"))
        assertEquals(0L, parseSize("0"))
    }

    @Test
    fun `units are accepted, because the column shows them`() {
        assertEquals(1024L, parseSize("1kb"))
        assertEquals(1024L, parseSize("1 KB"))
        assertEquals(1024L * 1024, parseSize("1mb"))
        assertEquals(1536L, parseSize("1.5kb"))
        assertEquals(1024L * 1024 * 1024, parseSize("1gb"))
    }

    @Test
    fun `nonsense does not parse, so a half-typed number is not a filter`() {
        assertNull(parseSize(""))
        assertNull(parseSize("   "))
        assertNull(parseSize("big"))
        assertNull(parseSize("12mbb"))
        assertNull(parseSize("1,024"))
    }

    // --- the comparison itself ---------------------------------------------

    @Test
    fun `a comparison with no operand filters nothing`() {
        val filter = ColumnFilter(op = OP_LARGER, operand = "")

        assertFalse(filter.isActive)
        assertTrue(filter.comparisonOk(5))
        assertTrue(filter.comparisonOk(null))
    }

    @Test
    fun `larger, smaller and equal each mean what they say`() {
        assertTrue(ColumnFilter(op = OP_LARGER, operand = "1kb").comparisonOk(2048))
        assertFalse(ColumnFilter(op = OP_LARGER, operand = "1kb").comparisonOk(1024))

        assertTrue(ColumnFilter(op = OP_SMALLER, operand = "1kb").comparisonOk(512))
        assertFalse(ColumnFilter(op = OP_SMALLER, operand = "1kb").comparisonOk(1024))

        assertTrue(ColumnFilter(op = OP_EQUAL, operand = "1kb").comparisonOk(1024))
        assertFalse(ColumnFilter(op = OP_EQUAL, operand = "1kb").comparisonOk(1025))
    }

    @Test
    fun `a row with no size cannot satisfy a comparison`() {
        // Treating a missing size as zero would put every request still in
        // flight under "smaller than".
        assertFalse(ColumnFilter(op = OP_SMALLER, operand = "1kb").comparisonOk(null))
        assertFalse(ColumnFilter(op = OP_LARGER, operand = "1kb").comparisonOk(null))
    }

    @Test
    fun `a filter stays active while any of its three parts is set`() {
        assertFalse(ColumnFilter().isActive)
        assertTrue(ColumnFilter(text = "x").isActive)
        assertTrue(ColumnFilter(selected = setOf("2xx")).isActive)
        assertTrue(ColumnFilter(op = OP_LARGER, operand = "1kb").isActive)
    }

    @Test
    fun `editing one part leaves the others alone`() {
        val filter = ColumnFilter(text = "a", selected = setOf("2xx"), op = OP_LARGER, operand = "1kb")

        assertEquals(setOf("2xx"), filter.withText("b").selected)
        assertEquals(OP_LARGER, filter.withText("b").op)
        assertEquals("a", filter.toggle("4xx").text)
        assertEquals(setOf("2xx", "4xx"), filter.toggle("4xx").selected)
        assertEquals("a", filter.withComparison(OP_EQUAL, "2kb").text)
    }
}
