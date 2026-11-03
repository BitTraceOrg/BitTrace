package org.bittrace.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.HarContent
import org.bittrace.data.HarTimings
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.NameValuePair
import org.bittrace.data.SessionStore
import java.awt.EventQueue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the server over a real socket, as a client would: the handshake, the
 * tool listing, both tools, and the refusals the transport spec asks for.
 */
class McpServerTest {

    private val store = SessionStore()
    private val bodies = HashMap<Pair<String, Boolean>, ByteArray>()
    private lateinit var server: McpServer
    private val http = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        store.onInitialRequest(request("a", "https://example.test/users", "GET"))
        store.onInitialResponse(response("a", 200))
        store.onCompleteRequest(
            CompleteRequestMessage(
                id = "a",
                request = CompleteRequestMessage.RequestBody(headers = listOf(NameValuePair("Accept", "application/json"))),
            ),
        )
        store.onCompleteResponse(
            CompleteResponseMessage(
                id = "a",
                response = CompleteResponseMessage.ResponseBody(
                    headers = listOf(NameValuePair("Content-Type", "application/json")),
                    bodySize = 13,
                    content = HarContent(size = 13, mimeType = "application/json", wireSize = 13),
                ),
                timings = CompleteResponseMessage.Timings(receive = 0.5),
                time = 4.0,
            ),
        )
        store.onInitialRequest(request("b", "https://other.test/upload", "POST"))
        repeat(4) { EventQueue.invokeAndWait { } }

        bodies["a" to false] = """{"ok":true}""".encodeToByteArray()
        bodies["b" to true] = byteArrayOf(0xff.toByte(), 0x00, 0x10)

        server = McpServer(McpTools(store) { id, request -> bodies[id to request] }, port = 0)
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop()

    @Test
    fun `initialize negotiates and advertises tools`() {
        val reply = rpc("initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        val result = reply["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
        assertEquals("bittrace", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools list names both tools`() {
        val tools = rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(setOf("list_flows", "get_flow"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `list_flows returns every flow and filters`() {
        val all = tool("list_flows", "{}")
        assertEquals(2, all["total"]!!.jsonPrimitive.int)
        assertEquals("a", all["flows"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(200, all["flows"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.int)

        val filtered = tool("list_flows", """{"filter":"upload"}""")
        assertEquals(1, filtered["total"]!!.jsonPrimitive.int)
        assertEquals("b", filtered["flows"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_flow returns headers and bodies`() {
        val flow = tool("get_flow", """{"id":"a"}""")
        val req = flow["request"]!!.jsonObject
        assertEquals("Accept", req["headers"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val res = flow["response"]!!.jsonObject
        assertEquals(200, res["status"]!!.jsonPrimitive.int)
        val body = res["body"]!!.jsonObject
        assertEquals("utf-8", body["encoding"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", body["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `binary bodies come back as base64`() {
        val body = tool("get_flow", """{"id":"b"}""")["request"]!!.jsonObject["body"]!!.jsonObject
        assertEquals("base64", body["encoding"]!!.jsonPrimitive.content)
        assertEquals("/wAQ", body["base64"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown flow id is a tool error`() {
        val result = rpc("tools/call", """{"name":"get_flow","arguments":{"id":"nope"}}""")["result"]!!.jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
    }

    @Test
    fun `notification is accepted without a body`() {
        val res = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, res.statusCode())
    }

    @Test
    fun `foreign origin is refused`() {
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "https://evil.example")
        assertEquals(403, res.statusCode())
        assertEquals(200, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "http://localhost:3000").statusCode())
    }

    // --- helpers -----------------------------------------------------------

    private fun post(body: String, origin: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.boundPort}/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        origin?.let { builder.header("Origin", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rpc(method: String, params: String = "{}"): JsonObject {
        val res = post("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
        assertEquals(200, res.statusCode())
        return Json.parseToJsonElement(res.body()).jsonObject
    }

    /** Calls a tool and parses the JSON its text content carries. */
    private fun tool(name: String, args: String): JsonObject {
        val result = rpc("tools/call", """{"name":"$name","arguments":$args}""")["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun request(id: String, url: String, method: String) = InitialRequestData(
        id = id,
        startedDateTime = "2026-09-17T10:00:00.000Z",
        request = InitialRequestData.RequestHead(
            method = method,
            url = url,
            httpVersion = "HTTP/1.1",
            headersSize = 0,
            bodySize = 0,
        ),
        tls = "TLSv1.3",
    )

    private fun response(id: String, status: Int) = InitialResponseData(
        id = id,
        serverIPAddress = "127.0.0.1",
        connection = "1",
        error = false,
        response = InitialResponseData.ResponseHead(
            status = status,
            statusText = "OK",
            httpVersion = "HTTP/1.1",
            headersSize = 0,
            bodySize = 13,
            redirectURL = "",
        ),
        timings = HarTimings(0.0, 0.0, 0.0, 0.0, 1.0, 0.5, 0.0),
        time = 4.0,
    )
}
