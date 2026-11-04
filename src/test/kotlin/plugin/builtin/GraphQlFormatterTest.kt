package org.bittrace.plugin.builtin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphQlFormatterTest {

    private val gql = GraphQlFormatter()

    private fun format(text: String) = gql.format(text.toByteArray(), "application/json")

    @Test
    fun `envelope shows operation name and variables`() {
        val out = format("""{"query":"query GetUser(${'$'}id: ID!) { user(id: ${'$'}id) { name } }","operationName":"GetUser","variables":{"id":"42"}}""")
        assertContains(out, "# query GetUser")
        assertContains(out, "user(id: ${'$'}id)")
        assertContains(out, "# variables")
        assertContains(out, "#     \"id\": \"42\"")
    }

    @Test
    fun `name and kind come from the document when operationName is absent`() {
        val ops = graphQlOperations("""{"query":"mutation AddItem { add { id } }"}""")!!
        assertEquals("mutation", ops.single().kind)
        assertEquals("AddItem", ops.single().name)
        val anon = graphQlOperations("""{"query":"{ me { id } }","variables":null}""")!!.single()
        assertEquals("query", anon.kind)
        assertNull(anon.name)
        assertNull(anon.variables)
    }

    @Test
    fun `batched requests list each operation`() {
        val out = format("""[{"query":"query A { a }"},{"query":"query B { b }"}]""")
        assertContains(out, "# operation 1 of 2")
        assertContains(out, "# query A")
        assertContains(out, "# operation 2 of 2")
        assertContains(out, "# query B")
    }

    @Test
    fun `non-envelopes are not operations`() {
        assertNull(graphQlOperations("""{"foo":1}"""))
        assertNull(graphQlOperations("query { a }"))
        assertNull(graphQlOperations("[1,2]"))
        // A bare document still formats.
        assertContains(gql.format("query { a }".toByteArray(), "application/graphql"), "query")
    }
}
