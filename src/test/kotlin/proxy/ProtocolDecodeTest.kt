package org.bittrace.proxy

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.ConnectRequestData
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData

/**
 * Field mapping against payloads the sidecar actually wrote.
 *
 * Every string below was captured from `MITMConnect.exe` driven with curl, not
 * written by hand from the spec — the models exist to match what comes down the
 * pipe, and a spec transcribed twice can agree with itself while disagreeing
 * with the wire.
 */
class ProtocolDecodeTest {

    /** The same configuration [ProxyProcess] decodes frames with. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `initial request carries the client connection it tunnelled through`() {
        val data = json.decodeFromString<InitialRequestData>(
            """{"startedDateTime":"2026-09-05T23:29:33.154368+00:00","request":{"method":"GET",
               "url":"https://example.com/","httpVersion":"HTTP/1.1","headersSize":57,"bodySize":0,
               "queryString":[]},"clientConnectionId":"8e364c83-91b8-416d-bd40-19d14236b26e",
               "id":"758adfad-7a8d-476f-a3d3-6f6a030b5584","_tls":"TLSv1.3"}"""
        )

        assertEquals("8e364c83-91b8-416d-bd40-19d14236b26e", data.clientConnectionId)
        assertEquals("TLSv1.3", data.tls)
        assertEquals("https://example.com/", data.request.url)
    }

    @Test
    fun `a connect becomes a row with its headers already in place`() {
        val data = json.decodeFromString<ConnectRequestData>(
            """{"startedDateTime":"2026-09-05T23:29:33.007487+00:00","request":{"method":"CONNECT",
               "url":"example.com:443","httpVersion":"HTTP/1.1","headersSize":78,"bodySize":0,
               "queryString":[],"headers":[{"name":"Host","value":"example.com:443"},
               {"name":"User-Agent","value":"curl/8.16.0"}]},
               "clientConnectionId":"8e364c83-91b8-416d-bd40-19d14236b26e",
               "clientAddress":"127.0.0.1:60121","id":"849d94fd-b06b-43e4-9fce-d839fffab2e5","_tls":""}"""
        )

        assertEquals("127.0.0.1:60121", data.clientAddress)

        val request = data.toInitialRequest()
        assertEquals("CONNECT", request.request.method)
        // The authority, not a URL: the tunnel has no path.
        assertEquals("example.com:443", request.request.url)
        // The link back to the requests that will travel inside this tunnel.
        assertEquals("8e364c83-91b8-416d-bd40-19d14236b26e", request.clientConnectionId)
        assertEquals(request.id, data.toCompleteRequest().id)
        assertEquals("Host", data.toCompleteRequest().request.headers.first().name)
    }

    @Test
    fun `a refused connect decodes as an errored response`() {
        val data = json.decodeFromString<InitialResponseData>(
            """{"time":0.4589557647705078,"timings":{"blocked":0.4589557647705078,"dns":-1,"connect":-1,
               "send":-1,"wait":-1,"receive":-1,"ssl":-1},"response":{"status":0,
               "statusText":"CONNECT failed","httpVersion":"HTTP/1.1","headersSize":-1,"bodySize":0,
               "redirectURL":""},"_error":true,"connection":"443","serverIPAddress":"",
               "clientConnectionId":"201109c3-f2fc-4e3c-8abc-710d351d0c79",
               "id":"326ebd27-5be6-4bac-9614-dea88a3edf65"}"""
        )

        assertTrue(data.error)
        assertEquals(0, data.response.status)
        assertEquals("CONNECT failed", data.response.statusText)
    }

    @Test
    fun `a streamed response says so and leaves its body segment empty`() {
        val data = json.decodeFromString<CompleteResponseMessage>(
            """{"time":2241.08,"timings":{"receive":1843.42},"response":{"headers":[],"cookies":[],
               "content":{"size":10485760,"mimeType":"application/zip"}},"_bodyStreamed":true,
               "id":"b6087e56-3838-4dc3-bead-29b65d7b5ebf"}"""
        )

        assertTrue(data.bodyStreamed)
        // The compressed wire length, for a streamed body — not the decoded one.
        assertEquals(10_485_760L, data.response.content.size)
    }

    @Test
    fun `an inline response reports no streaming`() {
        val data = json.decodeFromString<CompleteResponseMessage>(
            """{"time":689.74,"timings":{"receive":0.93},"response":{"headers":[],"cookies":[],
               "content":{"size":559,"mimeType":"text/html"}},"_bodyStreamed":false,
               "id":"2b0f89ce-571d-40ef-bacf-bd1f0bec542d"}"""
        )

        assertFalse(data.bodyStreamed)
    }

    @Test
    fun `body frames decode with their totals`() {
        val chunk = json.decodeFromString<BodyChunkMessage>(
            """{"id":"b6087e56","side":"response","seq":42}"""
        )
        assertEquals("response", chunk.side)
        assertEquals(42L, chunk.seq)

        val end = json.decodeFromString<BodyEndMessage>(
            """{"id":"b6087e56-3838-4dc3-bead-29b65d7b5ebf","side":"response","size":10485760,
               "captured":10485760,"chunks":160,"dropped":0,"truncated":false,"aborted":false,
               "contentEncoding":""}"""
        )
        assertEquals(10_485_760L, end.size)
        assertEquals(160L, end.chunks)
        assertFalse(end.truncated)
    }
}
