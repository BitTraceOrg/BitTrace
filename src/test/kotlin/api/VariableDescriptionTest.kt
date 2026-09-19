package org.bittrace.api

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-variable description: that it survives a save, and that adding it did
 * not quietly change every request file in every collection.
 *
 * That second one is the whole reason `KeyValue.description` carries
 * `@EncodeDefault(NEVER)`. `appYaml` is configured with `encodeDefaults = true`,
 * and headers and params are made of the same row type — so without the
 * annotation, opening and saving any request would append `description: ""` to
 * each of its header and param lines. Nothing would break; the diff would just
 * be enormous and say nothing. It is a one-word change to lose, and no other
 * test would notice, so it is pinned here.
 */
class VariableDescriptionTest {

    private val temp: Path = Files.createTempDirectory("bittrace-var-desc")

    @AfterTest
    fun cleanUp() {
        runCatching {
            Files.walk(temp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // --- persistence --------------------------------------------------------

    @Test
    fun `a description survives a write and read`() {
        val rows = listOf(
            KeyValue("host", "api.example.com", description = "staging gateway"),
            KeyValue("key", "abc123"),
        )
        ProjectVariables.write(temp, rows)

        assertEquals(rows, ProjectVariables.read(temp))
    }

    @Test
    fun `a row with only a description is kept rather than dropped on save`() {
        ProjectVariables.write(temp, listOf(KeyValue(description = "the tenant, once we know it")))

        val back = ProjectVariables.read(temp)
        assertEquals(1, back.size)
        assertEquals("the tenant, once we know it", back.single().description)
    }

    @Test
    fun `a wholly empty row is still dropped`() {
        ProjectVariables.write(temp, listOf(KeyValue()))

        assertFalse(Files.exists(ProjectVariables.pathIn(temp)), "an empty table should remove the file")
    }

    @Test
    fun `a description does not make a variable substitutable on its own`() {
        val rows = listOf(KeyValue(description = "not named yet"), KeyValue("host", "example.com"))

        assertEquals(mapOf("host" to "example.com"), ProjectVariables.lookup(rows))
    }

    // --- the request files it must not touch --------------------------------

    @Test
    fun `an empty description is left out of a saved variables file`() {
        ProjectVariables.write(temp, listOf(KeyValue("host", "example.com")))

        val text = Files.readString(ProjectVariables.pathIn(temp))
        assertFalse("description" in text, "an unused description should not be written:\n$text")
    }

    @Test
    fun `a used description is written`() {
        ProjectVariables.write(temp, listOf(KeyValue("host", "example.com", description = "the gateway")))

        assertContains(Files.readString(ProjectVariables.pathIn(temp)), "the gateway")
    }

    @Test
    fun `saving a request does not add a description to its headers or params`() {
        val request = ApiRequest(
            name = "Get user",
            url = "https://example.com/users/1",
            headers = listOf(KeyValue("Accept", "application/json")),
            params = listOf(KeyValue("verbose", "true")),
        )

        val text = RequestYaml.encode(request)

        assertFalse(
            "description" in text,
            "adding KeyValue.description must not change what a request file looks like:\n$text",
        )
    }

    @Test
    fun `a request round-trips unchanged through the new field`() {
        val request = ApiRequest(
            name = "Get user",
            url = "https://example.com/users/1",
            headers = listOf(KeyValue("Accept", "application/json")),
        )

        assertEquals(request, RequestYaml.decode(RequestYaml.encode(request)))
    }

    @Test
    fun `a variables file written before descriptions existed still reads`() {
        val legacy = """
            variables:
            - name: "host"
              value: "example.com"
              enabled: true
        """.trimIndent()
        Files.writeString(ProjectVariables.pathIn(temp), legacy)

        val back = ProjectVariables.read(temp)
        assertEquals(listOf(KeyValue("host", "example.com")), back)
        assertTrue(back.single().description.isEmpty())
    }
}
