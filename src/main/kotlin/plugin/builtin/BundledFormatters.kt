package org.bittrace.plugin.builtin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.bittrace.plugin.format.BodyFormatter

/** Raw UTF-8 text. */
class RawFormatter : BodyFormatter {
    override val id = "bittrace.raw"
    override val name = "RAW"
    override fun format(bytes: ByteArray, mimeType: String): String = String(bytes, Charsets.UTF_8)
}

/** Classic 16-bytes-per-line hex dump with an ASCII gutter. */
class HexFormatter : BodyFormatter {
    override val id = "bittrace.hex"
    override val name = "HEX"
    override fun format(bytes: ByteArray, mimeType: String): String = buildString {
        bytes.asIterable().chunked(16).forEachIndexed { i, chunk ->
            append("%08x  ".format(i * 16))
            chunk.forEach { append("%02x ".format(it)) }
            repeat(16 - chunk.size) { append("   ") }
            append(" |")
            chunk.forEach { b -> val c = b.toInt() and 0xFF; append(if (c in 0x20..0x7E) c.toChar() else '.') }
            append("|\n")
        }
    }
}

/** Pretty-printed JSON, or a notice when the body is not valid JSON. */
class JsonFormatter : BodyFormatter {
    override val id = "bittrace.json"
    override val name = "JSON"
    override fun format(bytes: ByteArray, mimeType: String): String = try {
        val element = pretty.parseToJsonElement(String(bytes, Charsets.UTF_8))
        pretty.encodeToString(JsonElement.serializer(), element)
    } catch (e: Exception) {
        "Body is not valid JSON."
    }

    private companion object {
        val pretty = Json { prettyPrint = true }
    }
}
