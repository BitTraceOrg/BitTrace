package org.bittrace.ui.layouts.forge.components

import org.bittrace.api.KeyValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The trailing blank row, which is where the table's only real rule lives.
 *
 * The bug these pin: the blank used to be a separate call site below the loop,
 * so appending put the new row at a different position while the caret stayed
 * in the blank field — which kept everything typed so far and appended it again
 * on the next keystroke. One row per letter. The list rules below are what the
 * single loop now drives, and they are worth holding still even though the
 * layout change is what actually fixed it.
 */
class KvEditorTest {

    private val rows = listOf(
        KeyValue("clientId", "abc"),
        KeyValue("scope", "read"),
    )

    @Test
    fun `an edit to an existing row replaces it in place`() {
        val out = kvEdited(rows, 1, KeyValue("scope", "read write"))
        assertEquals(listOf(KeyValue("clientId", "abc"), KeyValue("scope", "read write")), out)
    }

    @Test
    fun `an edit to the blank row appends exactly one`() {
        val out = kvEdited(rows, rows.size, KeyValue("c"))
        assertEquals(3, out?.size)
        assertEquals(KeyValue("c"), out?.last())
    }

    @Test
    fun `the letters after the first edit the appended row, they do not append again`() {
        // "c", then "cl", then "cli" — typed into what is now row 2.
        var current = rows
        current = kvEdited(current, current.size, KeyValue("c"))!!
        current = kvEdited(current, 2, KeyValue("cl"))!!
        current = kvEdited(current, 2, KeyValue("cli"))!!

        assertEquals(3, current.size)
        assertEquals(KeyValue("cli"), current.last())
    }

    @Test
    fun `an empty edit to the blank row appends nothing`() {
        assertNull(kvEdited(rows, rows.size, KeyValue()))
        // A value with no name is still worth keeping: it is half-typed, not empty.
        assertEquals(3, kvEdited(rows, rows.size, KeyValue(value = "x"))?.size)
    }

    @Test
    fun `toggling the blank row's checkbox alone does not append`() {
        // The blank row draws no checkbox, but `enabled` defaults true and an
        // otherwise-empty row must stay unappendable whatever the flag says.
        assertNull(kvEdited(rows, rows.size, KeyValue(enabled = false)))
    }

    @Test
    fun `removal is by position, not by value`() {
        val twins = listOf(KeyValue("x", "1"), KeyValue("x", "1"), KeyValue("y", "2"))
        assertEquals(listOf(KeyValue("x", "1"), KeyValue("y", "2")), kvRemoved(twins, 0))
    }

    @Test
    fun `removing the blank row is a no-op`() {
        assertEquals(rows, kvRemoved(rows, rows.size))
    }
}
