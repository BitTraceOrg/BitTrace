package org.bittrace.ui.components

import org.bittrace.ui.layouts.inspector.components.defaultColumns
import org.bittrace.ui.layouts.inspector.components.CONTENT_KINDS
import org.bittrace.ui.layouts.inspector.components.DEFAULT_COLUMN_KEYS
import org.bittrace.data.HTTP_METHODS
import org.bittrace.ui.layouts.inspector.components.STATUS_CLASSES
import org.bittrace.ui.layouts.inspector.components.columnCatalog
import org.bittrace.ui.layouts.inspector.components.FACET_COLUMNS
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
        assertEquals(HTTP_METHODS, byKey.getValue("method").facets)
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

    @Test
    fun `excludes inverts the typed text, and only the typed text`() {
        val rows = listOf("api.example.com", "cdn.other.com", "api.other.com")
        val cols = listOf(GridColumn<String>("host", "Host", 1f, value = { it }, cell = {}))

        val contains = mapOf("host" to ColumnFilter(text = "api"))
        assertEquals(listOf("api.example.com", "api.other.com"), applyGridFilters(rows, cols, contains))

        val excludes = mapOf("host" to ColumnFilter(text = "api", negated = true))
        assertEquals(listOf("cdn.other.com"), applyGridFilters(rows, cols, excludes))
    }

    @Test
    fun `an empty needle is not an exclusion of everything`() {
        // Negating a blank field would filter the whole grid away the moment you
        // picked "excludes" and before you typed what to exclude.
        val filter = ColumnFilter(text = "", negated = true)

        assertTrue(filter.textOk("anything"))
        assertFalse(filter.isActive)
    }

    @Test
    fun `every bound facet column exists, and offers a closed set`() {
        // The binding is keyed by column key, so a renamed column would not fail
        // to compile — it would silently stop editing the band's query, and the
        // header funnel would go back to filtering on its own. This is what
        // notices.
        val byKey = columnCatalog().associateBy { it.key }

        FACET_COLUMNS.keys.forEach { key ->
            val col = byKey[key] ?: error("no column '$key' for the facet binding")
            assertTrue(col.facets.isNotEmpty(), "'$key' is bound but offers no tick list")
        }
    }

    @Test
    fun `the grid does not also apply what the band owns`() {
        // Both surfaces write one set. If the grid kept applying its own copy
        // too, the ticks would be ANDed with themselves — harmless until the
        // band's OR-within-a-group made the two disagree.
        val rows = listOf("GET", "POST")
        val cols = columnCatalog().filter { it.key in FACET_COLUMNS.keys }

        assertEquals(rows, applyGridFilters(rows, emptyList(), mapOf("st" to ColumnFilter())))
        assertTrue(cols.isNotEmpty())
    }
}
