package org.bittrace.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

/**
 * A Model Context Protocol server over Streamable HTTP, running inside the app.
 *
 * In-process because the traffic is: flows live in `SessionStore` and bodies
 * in `BodyCache`, both in this JVM's heap, and a stdio server in a separate
 * process would have nothing to read. An MCP client connects with
 * `claude mcp add --transport http bittrace http://127.0.0.1:<port>/mcp`.
 *
 * Hand-rolled rather than built on the MCP SDK: the server only answers
 * requests — it never pushes, so it never needs the SSE half of the transport
 * — and what is left is a handful of JSON-RPC methods over the JDK's own HTTP
 * server, which is a smaller thing to carry than Ktor.
 *
 * Bound to loopback only, and requests carrying a non-loopback `Origin` are
 * refused, as the transport spec requires: captured traffic holds cookies and
 * tokens, and a web page must not be able to reach it by DNS rebinding.
 */
class McpServer(
    private val tools: McpTools,
    private val port: Int,
    private val version: String = "dev",
) {
    private var server: HttpServer? = null

    /** The port actually bound, which differs from the one asked for when that was 0. */
    val boundPort: Int? get() = server?.address?.port

    fun start() {
        check(server == null) { "already started" }
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
        http.executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "mcp-http").apply { isDaemon = true }
        }
        http.createContext(PATH) { exchange -> exchange.use { handle(it) } }
        http.start()
        server = http
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestURI.path.trimEnd('/') != PATH) return exchange.send(404)
        if (!originAllowed(exchange.requestHeaders.getFirst("Origin"))) return exchange.send(403)

        when (exchange.requestMethod) {
            "POST" -> Unit
            // No server-initiated stream, and no session to end.
            "GET" -> return exchange.send(405, extra = mapOf("Allow" to "POST, DELETE"))
            "DELETE" -> return exchange.send(200)
            else -> return exchange.send(405, extra = mapOf("Allow" to "POST, DELETE"))
        }

        val text = exchange.requestBody.readBytes().decodeToString()
        val message = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return exchange.sendJson(rpcError(JsonNull, PARSE_ERROR, "Parse error"))
        val reply = dispatch(message)
        if (reply == null) exchange.send(202) else exchange.sendJson(reply)
    }

    /** One JSON-RPC message in, its reply out — or null for a notification or a client's response. */
    internal fun dispatch(message: JsonElement): JsonElement? {
        val obj = message as? JsonObject
            ?: return rpcError(JsonNull, INVALID_REQUEST, "Expected a single JSON-RPC object; batches are not supported")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        // A notification, or a response to something the client was asked —
        // this server asks nothing, so there is nothing to match it against.
        if (id == null || id is JsonNull || method == null) {
            return if (method == null && id != null && "result" !in obj && "error" !in obj) {
                rpcError(id, INVALID_REQUEST, "Missing method")
            } else {
                null
            }
        }
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (method) {
                "initialize" -> rpcResult(id, initialize(params))
                "ping" -> rpcResult(id, JsonObject(emptyMap()))
                "tools/list" -> rpcResult(id, buildJsonObject { put("tools", tools.definitions) })
                "tools/call" -> callTool(id, params)
                else -> rpcError(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: Exception) {
            rpcError(id, INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") {
                put("name", "bittrace")
                put("version", version)
            }
            put(
                "instructions",
                "Read traffic captured by the BitTrace proxy. Call list_flows to see what was intercepted, " +
                    "then get_flow with a flow's id for its full request and response.",
            )
        }
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, INVALID_PARAMS, "Missing tool name")
        if (tools.definitions.none { (it as JsonObject)["name"] == JsonPrimitive(name) }) {
            return rpcError(id, INVALID_PARAMS, "Unknown tool: $name")
        }
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result = tools.call(name, args)
        return rpcResult(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", pretty.encodeToString(JsonElement.serializer(), result.payload))
                })
            })
            if (result.isError) put("isError", true)
        })
    }

    private companion object {
        const val PATH = "/mcp"

        /** Newest first: the one offered when the client asks for something else. */
        val PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        val json = Json { ignoreUnknownKeys = true }
        val pretty = Json { prettyPrint = true }

        fun rpcResult(id: JsonElement, result: JsonElement) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

        /** No `Origin` is a non-browser client; one that is present must be loopback. */
        fun originAllowed(origin: String?): Boolean {
            if (origin == null || origin == "null") return origin == null
            val host = runCatching { URI(origin).host }.getOrNull()?.trim('[', ']') ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "::1"
        }

        fun HttpExchange.send(status: Int, extra: Map<String, String> = emptyMap()) {
            extra.forEach { (k, v) -> responseHeaders.add(k, v) }
            sendResponseHeaders(status, -1)
        }

        fun HttpExchange.sendJson(body: JsonElement) {
            val bytes = json.encodeToString(JsonElement.serializer(), body).encodeToByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
