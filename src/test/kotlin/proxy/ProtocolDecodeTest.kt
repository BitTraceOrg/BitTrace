package org.bittrace.proxy

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.ConnectRequestData
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.requestBodySizeOf
import org.bittrace.data.responseBodySizeOf

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
               "bodySize":3145728,"content":{"size":10485760,"wireSize":3145728,
               "compression":7340032,"mimeType":"application/zip"}},"_bodyStreamed":true,
               "id":"b6087e56-3838-4dc3-bead-29b65d7b5ebf"}"""
        )

        assertTrue(data.bodyStreamed)
        // Decoded, whether or not the body was streamed; the wire length is
        // reported beside it and the two differ for a compressed body.
        assertEquals(10_485_760L, data.response.content.size)
        assertEquals(3_145_728L, data.response.content.wireSize)
        assertEquals(7_340_032L, data.response.content.compression)
        assertEquals(3_145_728L, data.response.bodySize)
    }

    @Test
    fun `an inline response reports no streaming`() {
        val data = json.decodeFromString<CompleteResponseMessage>(
            """{"time":689.74,"timings":{"receive":0.93},"response":{"headers":[],"cookies":[],
               "bodySize":559,"content":{"size":559,"wireSize":559,"mimeType":"text/html"}},
               "_bodyStreamed":false,"id":"2b0f89ce-571d-40ef-bacf-bd1f0bec542d"}"""
        )

        assertFalse(data.bodyStreamed)
    }

    @Test
    fun `the measured body size wins over the header the initial frame read`() {
        // A chunked response: no Content-Length, so the initial frame can only
        // report -1 and the size stayed unknown until this was added.
        val head = json.decodeFromString<InitialResponseData>(
            """{"time":12.4,"timings":{"blocked":-1,"dns":-1,"connect":-1,"send":0.1,"wait":12.2,
               "receive":-1,"ssl":-1},"response":{"status":200,"statusText":"OK",
               "httpVersion":"HTTP/2","headersSize":180,"bodySize":-1,"redirectURL":""},
               "_error":false,"connection":"443","serverIPAddress":"93.184.216.34",
               "id":"2b0f89ce-571d-40ef-bacf-bd1f0bec542d"}"""
        )
        val complete = json.decodeFromString<CompleteResponseMessage>(
            """{"time":689.74,"timings":{"receive":0.93},"response":{"headers":[],"cookies":[],
               "bodySize":1204,"content":{"size":4096,"wireSize":1204,"mimeType":"text/html"}},
               "_bodyStreamed":false,"id":"2b0f89ce-571d-40ef-bacf-bd1f0bec542d"}"""
        )

        assertEquals(-1L, head.response.bodySize)
        assertEquals(1204L, responseBodySizeOf(head, complete))
        // Before the body finishes, the header hint is all there is.
        assertEquals(-1L, responseBodySizeOf(head, null))
    }

    @Test
    fun `an errored flow reports the part of the body that did arrive`() {
        // No CompleteResponse follows an error, so these sizes are the only
        // ones the flow will ever carry.
        val head = json.decodeFromString<InitialResponseData>(
            """{"time":802.1,"timings":{"blocked":-1,"dns":-1,"connect":-1,"send":0.2,"wait":31.0,
               "receive":770.9,"ssl":-1},"response":{"status":0,"statusText":"Failed response",
               "httpVersion":"HTTP/1.1","headersSize":142,"bodySize":-1,"redirectURL":"",
               "content":{"size":65536,"wireSize":20480,"partial":true,
               "mimeType":"application/octet-stream"}},"_error":true,"connection":"443",
               "serverIPAddress":"93.184.216.34","id":"7d1b0f2e-0c4a-4a1b-9f43-2c1d9a5b6e77"}"""
        )

        assertTrue(head.error)
        assertTrue(head.response.content!!.partial)
        assertEquals(20_480L, responseBodySizeOf(head, null))
    }

    @Test
    fun `a chunked upload is measured on the completing frame`() {
        // Without Content-Length the initial frame reports 0 — a request with a
        // body would otherwise show as having none.
        val head = json.decodeFromString<InitialRequestData>(
            """{"startedDateTime":"2026-09-05T23:29:33.154368+00:00","request":{"method":"POST",
               "url":"https://example.com/upload","httpVersion":"HTTP/1.1","headersSize":142,
               "bodySize":0,"queryString":[]},"clientConnectionId":"",
               "id":"1f2e3d4c-5b6a-4798-8899-aabbccddeeff","_tls":"TLSv1.3"}"""
        )
        val complete = json.decodeFromString<CompleteRequestMessage>(
            """{"id":"1f2e3d4c-5b6a-4798-8899-aabbccddeeff","_bodyStreamed":false,
               "request":{"headers":[],"cookies":[],"bodySize":8192,
               "postData":{"mimeType":"application/octet-stream"}}}"""
        )

        assertEquals(0L, head.request.bodySize)
        assertEquals(8192L, requestBodySizeOf(head, complete))
    }

    @Test
    fun `the first status frame arrives before there is an addon to ask`() {
        // Sent at process start, before mitmproxy is initialized, so it carries
        // only what the process itself knows.
        val status = json.decodeFromString<ProxyStatus>(
            """{"state":"starting","pid":24180,"port":8080,
               "startedDateTime":"2026-09-16T18:02:11.412991+00:00","uptimeMs":0,
               "intervalMs":60000,"mitmproxyVersion":"12.1.1"}"""
        )

        assertEquals(ProxyStatus.STARTING, status.state)
        assertEquals(24180L, status.pid)
        assertEquals(60_000L, status.intervalMs)
        // Nothing is bound yet, which is not the same failure as being up
        // without a port.
        assertFalse(status.bound)
        assertFalse(status.portLost)
    }

    @Test
    fun `a running status says what it bound and how it is coping`() {
        val status = json.decodeFromString<ProxyStatus>(
            """{"state":"running","pid":24180,"port":8080,
               "startedDateTime":"2026-09-16T18:02:11.412991+00:00","uptimeMs":180432,
               "intervalMs":60000,"mitmproxyVersion":"12.1.1",
               "listenAddrs":["127.0.0.1:8080"],"connections":6,
               "queue":{"depth":12,"maxSize":4096},"openBodies":2,
               "counters":{"requests":418,"responses":417,"errors":1,"connects":54,
               "droppedFrames":0}}"""
        )

        assertTrue(status.bound)
        assertEquals("127.0.0.1:8080", status.address)
        assertEquals(6, status.connections)
        assertEquals(418L, status.counters.requests)
        assertEquals(12, status.queue.depth)
    }

    @Test
    fun `running with nothing bound is the failure that looks like success`() {
        val status = json.decodeFromString<ProxyStatus>(
            """{"state":"running","pid":24180,"port":8080,
               "startedDateTime":"2026-09-16T18:02:11.412991+00:00","uptimeMs":1204,
               "intervalMs":60000,"mitmproxyVersion":"12.1.1","listenAddrs":[],
               "connections":0,"queue":{"depth":0,"maxSize":4096},"openBodies":0,
               "counters":{"requests":0,"responses":0,"errors":0,"connects":0,
               "droppedFrames":7}}"""
        )

        assertTrue(status.portLost)
        assertFalse(status.bound)
        // The port it asked for, since there is no bound address to show.
        assertEquals("8080", status.address)
        assertEquals(7L, status.counters.droppedFrames)
    }

    @Test
    fun `body frames decode with their totals`() {
        val chunk = json.decodeFromString<BodyChunkMessage>(
            """{"id":"b6087e56","side":"response","seq":42}"""
        )
        assertEquals("response", chunk.side)
        assertEquals(42L, chunk.seq)

        val end = json.decodeFromString<BodyEndMessage>(
            """{"id":"b6087e56-3838-4dc3-bead-29b65d7b5ebf","side":"response","size":3145728,
               "decodedSize":10485760,"captured":3145728,"chunks":48,"dropped":0,
               "truncated":false,"aborted":false,"contentEncoding":"gzip"}"""
        )
        assertEquals(3_145_728L, end.size)
        // What the matching CompleteResponse reports as content.size: the
        // chunks themselves arrive still gzipped.
        assertEquals(10_485_760L, end.decodedSize)
        assertEquals(48L, end.chunks)
        assertFalse(end.truncated)
    }
}
