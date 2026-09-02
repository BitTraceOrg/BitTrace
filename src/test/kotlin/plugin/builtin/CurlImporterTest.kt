package org.bittrace.plugin.builtin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The risk in importing cURL is the shell, not the flags: quoting, escapes and
 * line continuations decide whether anything downstream is even parseable.
 */
class CurlImporterTest {

    private val importer = CurlImporter()

    @Test
    fun `claims curl commands and nothing else`() {
        assertTrue(importer.canImport("curl https://example.com"))
        assertTrue(importer.canImport("  $ curl https://example.com"))
        assertTrue(importer.canImport("CURL https://example.com"))
        assertTrue(!importer.canImport("""fetch("https://example.com")"""))
        assertTrue(!importer.canImport("GET /users HTTP/1.1"))
    }

    @Test
    fun `imports a bare url as GET`() {
        val request = importer.import("curl https://example.com/users")!!
        assertEquals("GET", request.method)
        assertEquals("https://example.com/users", request.url)
        assertTrue(request.body.isEmpty())
    }

    @Test
    fun `reads headers, method and body across continued lines`() {
        // The shape every browser's "copy as cURL" produces.
        val command = """
            curl 'https://api.example.com/v1/users?page=2' \
              -X POST \
              -H 'Content-Type: application/json' \
              -H 'Authorization: Bearer abc123' \
              --data-raw '{"name":"Ada"}' \
              --compressed
        """.trimIndent()

        val request = importer.import(command)!!
        assertEquals("POST", request.method)
        assertEquals("https://api.example.com/v1/users?page=2", request.url)
        assertEquals("""{"name":"Ada"}""", request.body)
        assertEquals("application/json", request.contentType)
        assertEquals("Bearer abc123", request.headers.first { it.name == "Authorization" }.value)
        // --compressed describes curl, not the request.
        assertTrue(request.warnings.isEmpty(), "unexpected warnings: ${request.warnings}")
    }

    @Test
    fun `data implies POST and curl's default content type`() {
        val request = importer.import("curl https://example.com -d 'a=1&b=2'")!!
        assertEquals("POST", request.method)
        assertEquals("a=1&b=2", request.body)
        assertEquals("application/x-www-form-urlencoded", request.contentType)
    }

    @Test
    fun `handles attached short flags`() {
        val request = importer.import("curl -XPUT -H'X-Trace: 1' https://example.com")!!
        assertEquals("PUT", request.method)
        assertEquals("1", request.headers.first { it.name == "X-Trace" }.value)
        assertEquals("https://example.com", request.url)
    }

    @Test
    fun `keeps quoted whitespace and escaped quotes together`() {
        val request = importer.import("""curl https://example.com -H "X-Note: hello world" -d "{\"a\":\"b\"}"""")!!
        assertEquals("hello world", request.headers.first { it.name == "X-Note" }.value)
        assertEquals("""{"a":"b"}""", request.body)
    }

    @Test
    fun `turns basic auth into an Authorization header`() {
        val request = importer.import("curl https://example.com -u alice:secret")!!
        // base64("alice:secret")
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.headers.first { it.name == "Authorization" }.value)
    }

    @Test
    fun `warns rather than silently dropping form parts`() {
        val request = importer.import("curl https://example.com -F file=@a.png")!!
        assertTrue(request.warnings.any { it.contains("-F") }, "expected a warning, got ${request.warnings}")
    }

    @Test
    fun `returns null without a url`() {
        assertNull(importer.import("curl -X POST"))
    }
}
