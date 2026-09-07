package org.bittrace.api

import org.bittrace.ui.components.editor.graphql
import org.bittrace.data.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for per-request settings.
 *
 * Three things here are worth pinning rather than eyeballing: that a null field
 * really does follow the default (the whole inheritance model rests on it), that
 * each encoder produces the bytes it claims to, and that a request saved before
 * settings existed does not come back pinned to a timeout nobody chose.
 */
class RequestSettingsTest {

    private val defaults = Settings(
        apiTimeoutMs = 30_000,
        apiHttpVersion = HTTP_AUTO,
        apiFollowRedirects = false,
        apiMaxRedirects = 5,
        apiUrlEncoding = ENCODING_WHATWG,
    )

    // --- inheritance -------------------------------------------------------

    @Test
    fun `an untouched request follows every default`() {
        val resolved = RequestSettings().resolve(defaults)

        assertEquals(30_000, resolved.timeoutMs)
        assertEquals(HTTP_AUTO, resolved.httpVersion)
        assertEquals(false, resolved.followRedirects)
        assertEquals(5, resolved.maxRedirects)
        assertEquals(ENCODING_WHATWG, resolved.urlEncoding)
        assertTrue(RequestSettings().isDefault)
    }

    @Test
    fun `an override wins over the default, field by field`() {
        val settings = RequestSettings(followRedirects = true, httpVersion = HTTP_1_1)
        val resolved = settings.resolve(defaults)

        assertEquals(true, resolved.followRedirects)
        assertEquals(HTTP_1_1, resolved.httpVersion)
        // The fields it said nothing about still follow.
        assertEquals(30_000, resolved.timeoutMs)
        assertEquals(5, resolved.maxRedirects)
    }

    @Test
    fun `changing a default moves a request that never overrode it`() {
        val settings = RequestSettings(timeoutMs = 1_000)
        val moved = defaults.copy(apiTimeoutMs = 60_000, apiMaxRedirects = 9)
        val resolved = settings.resolve(moved)

        assertEquals(1_000, resolved.timeoutMs, "an override is not moved by the default")
        assertEquals(9, resolved.maxRedirects, "a field with no override follows")
    }

    @Test
    fun `nonsense values are clamped rather than sent`() {
        val resolved = RequestSettings(timeoutMs = 0, maxRedirects = 500).resolve(defaults)

        assertTrue(resolved.timeoutMs >= 100, "a zero timeout would fail every request instantly")
        assertTrue(resolved.maxRedirects <= 20)
    }

    // --- encoding ----------------------------------------------------------

    @Test
    fun `whatwg encoding is what the app has always sent`() {
        assertEquals("a+b", encodeQuery("a b", ENCODING_WHATWG))
        assertEquals("%2B", encodeQuery("+", ENCODING_WHATWG))
        // The form encoder leaves these bare.
        assertEquals("*", encodeQuery("*", ENCODING_WHATWG))
        assertEquals("%7E", encodeQuery("~", ENCODING_WHATWG))
    }

    @Test
    fun `rfc 3986 escapes to the unreserved set`() {
        assertEquals("a%20b", encodeQuery("a b", ENCODING_RFC3986))
        assertEquals("%2B", encodeQuery("+", ENCODING_RFC3986))
        assertEquals("%2A", encodeQuery("*", ENCODING_RFC3986))
        // Unreserved, so it should never have been escaped.
        assertEquals("~", encodeQuery("~", ENCODING_RFC3986))
        assertEquals("-._~", encodeQuery("-._~", ENCODING_RFC3986))
    }

    @Test
    fun `both encoders agree on multi-byte characters`() {
        assertEquals("%C3%A9", encodeQuery("é", ENCODING_WHATWG))
        assertEquals("%C3%A9", encodeQuery("é", ENCODING_RFC3986))
    }

    @Test
    fun `none passes the text through untouched`() {
        assertEquals("a b+c~d*", encodeQuery("a b+c~d*", ENCODING_NONE))
    }

    @Test
    fun `an unknown encoding escapes rather than passing through`() {
        // A request from a later build must still be sendable, and escaping is
        // the answer that cannot corrupt a URL.
        assertEquals("a+b", encodeQuery("a b", "some-future-mode"))
    }

    @Test
    fun `the query is built with the chosen encoding`() {
        val params = listOf(KeyValue("q", "a b"))

        assertEquals("http://x/?q=a+b", urlWithParams("http://x/", params, ENCODING_WHATWG))
        assertEquals("http://x/?q=a%20b", urlWithParams("http://x/", params, ENCODING_RFC3986))
        assertEquals("http://x/?q=a b", urlWithParams("http://x/", params, ENCODING_NONE))
    }

    // --- migration ---------------------------------------------------------

    @Test
    fun `an old request keeps a timeout somebody set`() {
        val loaded = RequestYaml.decode("name: x\nmethod: GET\nurl: \"http://x/\"\ntimeoutMs: 5000\n")

        assertEquals(5_000, loaded.settings.timeoutMs)
        assertNull(loaded.timeoutMs, "the legacy field is not carried forward")
    }

    @Test
    fun `an old request does not get pinned to the old default`() {
        // Every request saved before settings existed carries this, because the
        // encoder writes defaults — reading it as an override would opt all of
        // them out of a default they never chose.
        val loaded = RequestYaml.decode("name: x\nmethod: GET\nurl: \"http://x/\"\ntimeoutMs: 30000\n")

        assertNull(loaded.settings.timeoutMs)
        assertEquals(30_000, loaded.settings.resolve(defaults).timeoutMs)
        assertEquals(60_000, loaded.settings.resolve(defaults.copy(apiTimeoutMs = 60_000)).timeoutMs)
    }

    // --- the graphql envelope ---------------------------------------------

    @Test
    fun `a graphql body is sent as the json envelope, not as raw graphql`() {
        val body = ApiBody(contentType = "application/graphql", text = "query { id }")

        assertEquals("application/json", body.wireContentType())
        assertEquals("""{"query":"query { id }"}""", body.payload())
    }

    @Test
    fun `variables go in as json, not as a string`() {
        val body = ApiBody(
            contentType = "application/graphql",
            text = "query(\$id: ID!) { user(id: \$id) { name } }",
            graphqlVariables = """{"id": "42"}""",
        )

        // Re-encoding what was typed would turn the object into a string, and
        // every server would reject it.
        assertTrue(body.payload().contains(""""variables":{"id": "42"}"""), body.payload())
    }

    @Test
    fun `blank variables are left out entirely`() {
        val body = ApiBody(contentType = "application/graphql", text = "{ id }", graphqlVariables = "   ")

        assertFalse(body.payload().contains("variables"), "null variables are rejected by some servers")
    }

    @Test
    fun `an operation with quotes and newlines is escaped`() {
        val body = ApiBody(contentType = "application/graphql", text = "query {\n  find(q: \"a\")\n}")

        // The envelope has to stay parseable whatever the operation contains,
        // which is why it is built by a JSON writer rather than by hand.
        assertEquals("{\"query\":\"query {\\n  find(q: \\\"a\\\")\\n}\"}", body.payload())
    }

    @Test
    fun `a plain body is untouched by any of this`() {
        val body = ApiBody(contentType = "application/json", text = """{"a":1}""")

        assertEquals("""{"a":1}""", body.payload())
        assertEquals("application/json", body.wireContentType())
    }

    @Test
    fun `settings round-trip through yaml`() {
        val request = ApiRequest(
            name = "x",
            settings = RequestSettings(
                timeoutMs = 2_500,
                httpVersion = HTTP_2,
                followRedirects = true,
                maxRedirects = 3,
                urlEncoding = ENCODING_RFC3986,
            ),
        )
        val back = RequestYaml.decode(RequestYaml.encode(request))

        assertEquals(request.settings, back.settings)
    }

    @Test
    fun `an untouched request round-trips as untouched`() {
        val back = RequestYaml.decode(RequestYaml.encode(ApiRequest(name = "x")))

        assertTrue(back.settings.isDefault, "writing defaults must not turn them into overrides")
    }
}
