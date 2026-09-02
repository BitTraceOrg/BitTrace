package org.bittrace.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for the collection file format.
 *
 * These earn their keep twice over: they prove kaml works against the
 * kotlinx-serialization version this project resolves to (kaml declares an
 * older one, which Gradle upgrades), and they pin the body edge cases where a
 * YAML emitter can silently corrupt content.
 */
class RequestYamlTest {

    @Test
    fun `round-trips a plain request`() {
        val request = ApiRequest(
            name = "Create user",
            method = "POST",
            url = "https://api.example.com/v1/users",
            headers = listOf(KeyValue("Content-Type", "application/json")),
            params = listOf(KeyValue("verbose", "true", enabled = false)),
            body = ApiBody("application/json", """{"name":"Ada"}"""),
        )
        assertEquals(request, RequestYaml.decode(RequestYaml.encode(request)))
    }

    @Test
    fun `round-trips a multi-line body`() {
        val body = """
            {
              "name": "Ada",
              "tags": ["a", "b"]
            }
        """.trimIndent()
        val request = ApiRequest(name = "x", body = ApiBody("application/json", body))
        val encoded = RequestYaml.encode(request)
        assertEquals(body, RequestYaml.decode(encoded).body.text)
        // A readable block scalar, not one escaped double-quoted line.
        assertTrue(encoded.contains("|"), "expected a block scalar, got:\n$encoded")
    }

    @Test
    fun `survives bodies that break naive emitters`() {
        // Each of these defeats a hand-rolled emitter: a document marker, a
        // trailing space (block scalars cannot represent it), a leading blank
        // line, an indented first line, and a tab.
        val hostile = "---\ntrailing space \n\n    indented first\n\ttabbed\n...\n"
        val request = ApiRequest(name = "x", body = ApiBody(text = hostile))
        assertEquals(hostile, RequestYaml.decode(RequestYaml.encode(request)).body.text)
    }

    @Test
    fun `normalises CRLF on the way to disk`() {
        val request = ApiRequest(name = "x", body = ApiBody(text = "a\r\nb\r\n"))
        assertEquals("a\nb\n", RequestYaml.decode(RequestYaml.encode(request)).body.text)
    }

    @Test
    fun `tolerates a file missing optional keys`() {
        val decoded = RequestYaml.decode("name: Minimal\nurl: https://example.com\n")
        assertEquals("Minimal", decoded.name)
        assertEquals("GET", decoded.method)
        assertTrue(decoded.headers.isEmpty())
        assertTrue(decoded.body.isEmpty)
    }

    @Test
    fun `ignores keys it does not know`() {
        val decoded = RequestYaml.decode("name: Future\nauthProfile: oauth2\n")
        assertEquals("Future", decoded.name)
    }

    @Test
    fun `rejects names that cannot be file names`() {
        assertEquals("List users", fileNameFor("  List users  "))
        assertNull(fileNameFor("GET /users?q=1"))
        assertNull(fileNameFor("con"))
        assertNull(fileNameFor("trailing dot."))
        assertNull(fileNameFor(""))
    }
}
