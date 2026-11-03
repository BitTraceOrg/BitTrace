package org.bittrace.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bittrace.data.NameValuePair
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficRow
import java.awt.EventQueue
import java.util.Base64

/** A tool's answer: the JSON it produced, and whether that JSON describes a failure. */
class ToolResult(val payload: JsonElement, val isError: Boolean = false)

/**
 * The tools the MCP server offers, kept apart from the HTTP transport so they
 * can be exercised without a socket.
 *
 * Two tools, because that is the shape of the question an agent asks: what
 * went past, then everything about one of them. `list_flows` stays compact so
 * a busy session fits in a context window; `get_flow` carries the headers and
 * bodies the list leaves out.
 *
 * Rows are Compose snapshot state written on the event thread, so every read
 * hops there and copies plain values out (see [onUi]). Bodies come through
 * [body], which is the proxy's own lookup and is safe from any thread.
 */
class McpTools(
    private val store: SessionStore,
    private val body: (id: String, request: Boolean) -> ByteArray?,
) {

    val definitions: JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("name", "list_flows")
            put("description", "List the HTTP(S) flows BitTrace has intercepted, oldest first. " +
                "Returns compact metadata per flow; pass a flow's `id` to get_flow for headers and bodies.")
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("filter") {
                        put("type", "string")
                        put("description", "Case-insensitive substring matched against the method and URL.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Maximum flows to return (default $DEFAULT_LIMIT, max $MAX_LIMIT).")
                    }
                    putJsonObject("offset") {
                        put("type", "integer")
                        put("description", "Flows to skip after filtering (default 0).")
                    }
                }
            }
        })
        add(buildJsonObject {
            put("name", "get_flow")
            put("description", "Get one intercepted flow by id: the full request and response, " +
                "with headers, cookies, timings and bodies.")
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "The flow id, as list_flows reports it.")
                    }
                    putJsonObject("includeBodies") {
                        put("type", "boolean")
                        put("description", "Include request and response bodies (default true).")
                    }
                    putJsonObject("maxBodyBytes") {
                        put("type", "integer")
                        put("description", "Truncate each body to this many bytes (default $DEFAULT_MAX_BODY).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            }
        })
    }

    fun call(name: String, args: JsonObject): ToolResult = when (name) {
        "list_flows" -> listFlows(args)
        "get_flow" -> getFlow(args)
        else -> error("Unknown tool: $name")
    }

    private fun listFlows(args: JsonObject): ToolResult {
        val filter = args.string("filter")?.lowercase()?.takeIf { it.isNotBlank() }
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)

        return onUi {
            val matching = store.rows.filter { row ->
                filter == null ||
                    row.request.request.url.lowercase().contains(filter) ||
                    row.request.request.method.lowercase().contains(filter)
            }
            val page = matching.drop(offset).take(limit)
            ToolResult(buildJsonObject {
                put("total", matching.size)
                put("offset", offset)
                put("returned", page.size)
                putJsonArray("flows") { page.forEach { add(summary(it)) } }
            })
        }
    }

    private fun summary(row: TrafficRow): JsonObject = buildJsonObject {
        val head = row.request.request
        val res = row.response
        put("id", row.id)
        put("rowCount", row.rowCount)
        put("method", head.method)
        put("url", head.url)
        put("startedDateTime", row.request.startedDateTime)
        put("status", res?.response?.status)
        put("failed", row.failed)
        put("time", res?.time)
        put("requestBodySize", row.requestBodySize)
        put("responseBodySize", row.responseBodySize)
        put("mimeType", row.completeResponse?.response?.content?.mimeType)
        if (row.isConnect) put("isConnect", true)
        if (row.isTls) put("isTls", true)
        if (row.isWebSocket) put("isWebSocket", true)
    }

    private fun getFlow(args: JsonObject): ToolResult {
        val id = args.string("id") ?: return failure("Missing required argument `id`.")
        val includeBodies = args.boolean("includeBodies") ?: true
        val maxBody = (args.int("maxBodyBytes") ?: DEFAULT_MAX_BODY).coerceAtLeast(0)

        // Metadata on the event thread; bodies after, off it — a large body
        // being encoded should not hold up the UI.
        val flow = onUi { store.get(id)?.let(::detail) }
            ?: return failure("No flow with id `$id`. It may have been cleared or evicted; call list_flows.")

        if (!includeBodies) return ToolResult(flow)
        return ToolResult(JsonObject(flow.toMutableMap().apply {
            put("request", withBody(this["request"], body(id, true), maxBody))
            this["response"]?.takeIf { it is JsonObject }?.let { put("response", withBody(it, body(id, false), maxBody)) }
        }))
    }

    private fun detail(row: TrafficRow): JsonObject = buildJsonObject {
        val head = row.request.request
        val req = row.completeRequest?.request
        put("id", row.id)
        put("rowCount", row.rowCount)
        put("startedDateTime", row.request.startedDateTime)
        put("tls", row.request.tls)
        if (row.isConnect) put("isConnect", true)
        if (row.isTls) put("isTls", true)
        putJsonObject("request") {
            put("method", head.method)
            put("url", head.url)
            put("httpVersion", head.httpVersion)
            put("headers", pairs(req?.headers.orEmpty()))
            put("cookies", pairs(req?.cookies.orEmpty()))
            put("queryString", pairs(head.queryString))
            put("bodySize", row.requestBodySize)
            put("mimeType", req?.postData?.mimeType)
            put("complete", req != null)
        }
        val res = row.response
        if (res == null) {
            put("response", null as String?)
        } else {
            val complete = row.completeResponse
            putJsonObject("response") {
                put("status", res.response.status)
                put("statusText", res.response.statusText)
                put("httpVersion", res.response.httpVersion)
                put("error", res.error)
                put("serverIPAddress", res.serverIPAddress)
                put("redirectURL", res.response.redirectURL)
                put("headers", pairs(complete?.response?.headers.orEmpty()))
                putJsonArray("cookies") {
                    complete?.response?.cookies.orEmpty().forEach { c ->
                        add(buildJsonObject {
                            put("name", c.name)
                            put("value", c.value)
                            c.path?.let { put("path", it) }
                            c.domain?.let { put("domain", it) }
                            c.expires?.let { put("expires", it) }
                            c.httpOnly?.let { put("httpOnly", it) }
                            c.secure?.let { put("secure", it) }
                        })
                    }
                }
                put("bodySize", row.responseBodySize)
                (complete?.response?.content ?: res.response.content)?.let { content ->
                    putJsonObject("content") {
                        put("size", content.size)
                        put("wireSize", content.wireSize)
                        put("mimeType", content.mimeType)
                        if (content.partial) put("partial", true)
                    }
                }
                put("time", complete?.time ?: res.time)
                putJsonObject("timings") {
                    val t = res.timings
                    put("blocked", t.blocked)
                    put("dns", t.dns)
                    put("connect", t.connect)
                    put("ssl", t.ssl)
                    put("send", t.send)
                    put("wait", t.wait)
                    put("receive", complete?.timings?.receive ?: t.receive)
                }
                put("complete", complete != null)
            }
        }
        if (row.isWebSocket) {
            putJsonObject("webSocket") {
                put("messages", row.webSocketMessages.size)
                put("evicted", row.webSocketEvicted)
                put("closed", row.webSocketEnd != null)
            }
        }
    }

    private fun withBody(side: JsonElement?, bytes: ByteArray?, max: Int): JsonElement {
        val obj = side as? JsonObject ?: return side ?: JsonPrimitive(null as String?)
        return JsonObject(obj + ("body" to encodeBody(bytes, max)))
    }

    private fun failure(message: String) = ToolResult(buildJsonObject { put("error", message) }, isError = true)

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 5_000
        const val DEFAULT_MAX_BODY = 256 * 1024

        fun pairs(list: List<NameValuePair>): JsonArray = buildJsonArray {
            list.forEach { add(buildJsonObject { put("name", it.name); put("value", it.value) }) }
        }

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        /** Runs [block] on the event thread, where the store's rows are written. */
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var out: Result<T>? = null
            EventQueue.invokeAndWait { out = runCatching(block) }
            return out!!.getOrThrow()
        }
    }
}

/**
 * A body as JSON: UTF-8 text when it decodes cleanly, base64 otherwise, cut to
 * [max] bytes. Null bytes mean there is no body to show — none was sent, it is
 * still to arrive, or the cache has evicted it — and say so rather than
 * pretending the body was empty.
 */
internal fun encodeBody(bytes: ByteArray?, max: Int): JsonObject = buildJsonObject {
    if (bytes == null) {
        put("available", false)
        return@buildJsonObject
    }
    val truncated = bytes.size > max
    val slice = if (truncated) bytes.copyOf(max) else bytes
    put("available", true)
    put("size", bytes.size)
    put("truncated", truncated)
    // A cut can land inside a multi-byte character; drop up to three trailing
    // bytes before giving up on text, so a truncated UTF-8 body stays text.
    val text = utf8OrNull(slice)
        ?: if (truncated) (1..3).firstNotNullOfOrNull { utf8OrNull(slice.copyOf(slice.size - it)) } else null
    if (text != null) {
        put("encoding", "utf-8")
        put("text", text)
    } else {
        put("encoding", "base64")
        put("base64", Base64.getEncoder().encodeToString(slice))
    }
}

private fun utf8OrNull(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
} catch (e: java.nio.charset.CharacterCodingException) {
    null
}
