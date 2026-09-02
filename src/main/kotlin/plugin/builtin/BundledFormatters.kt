package org.bittrace.plugin.builtin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.format.FormattedBody
import org.bittrace.plugin.format.Span

/**
 * A formatter whose highlighted view is its plain one, classified.
 *
 * Every bundled formatter but RAW works this way — format the bytes, run a lexer
 * over the result — and each had spelled the same three lines out in its own
 * `highlight`. A subclass says how to format and how to classify; the wiring
 * lives here once.
 */
abstract class HighlightedFormatter : BodyFormatter {

    /** Spans over [text], which is this formatter's own [format] output. */
    protected abstract fun spansOf(text: String): List<Span>

    final override fun highlight(bytes: ByteArray, mimeType: String): FormattedBody {
        val text = format(bytes, mimeType)
        return FormattedBody(text, spansOf(text))
    }
}

/** Raw UTF-8 text. */
class RawFormatter : BodyFormatter {
    override val id = "bittrace.raw"
    override val name = "Raw"
    override fun format(bytes: ByteArray, mimeType: String): String = String(bytes, Charsets.UTF_8)
}

/** Classic 16-bytes-per-line hex dump with an ASCII gutter. */
class HexFormatter : HighlightedFormatter() {
    override val id = "bittrace.hex"
    override val name = "Hex"
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

    override fun spansOf(text: String) = highlightHex(text)
}

/** Pretty-printed JSON, or a notice when the body is not valid JSON. */
class JsonFormatter : HighlightedFormatter() {
    override val id = "bittrace.json"
    override val name = "JSON"
    override fun handles(mimeType: String) = mimeType.contains("json", ignoreCase = true)
    override fun format(bytes: ByteArray, mimeType: String): String = try {
        val element = pretty.parseToJsonElement(String(bytes, Charsets.UTF_8))
        pretty.encodeToString(JsonElement.serializer(), element)
    } catch (e: Exception) {
        "Body is not valid JSON."
    }

    override fun spansOf(text: String) = highlightJson(text)

    private companion object {
        val pretty = Json { prettyPrint = true }
    }
}

/** Indented XML — one node per line, short leaf elements kept inline. */
class XmlFormatter : HighlightedFormatter() {
    override val id = "bittrace.xml"
    override val name = "XML"
    override fun handles(mimeType: String) =
        mimeType.contains("xml", ignoreCase = true) && !mimeType.contains("html", ignoreCase = true)

    override fun format(bytes: ByteArray, mimeType: String): String {
        val text = textOrNull(bytes)?.trim() ?: return "Body is not text."
        if (!text.startsWith("<")) return "Body is not XML."
        return formatMarkup(text)
    }

    override fun spansOf(text: String) = highlightMarkup(text)
}

/**
 * Indented HTML. Void elements (`<br>`, `<img>`, …) don't open a level, and
 * `<script>`/`<style>` contents are left alone rather than parsed as markup.
 */
class HtmlFormatter : HighlightedFormatter() {
    override val id = "bittrace.html"
    override val name = "HTML"
    override fun handles(mimeType: String) = mimeType.contains("html", ignoreCase = true)

    override fun format(bytes: ByteArray, mimeType: String): String {
        val text = textOrNull(bytes)?.trim() ?: return "Body is not text."
        if (!text.startsWith("<")) return "Body is not HTML."
        return formatMarkup(text, voidTags = VOID, rawTextTags = RAW_TEXT)
    }

    override fun spansOf(text: String) = highlightMarkup(text)

    private companion object {
        val VOID = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
        )
        val RAW_TEXT = setOf("script", "style")
    }
}

/** Brace-driven re-indent shared by the JavaScript and TypeScript formatters. */
abstract class ScriptFormatter(
    override val id: String,
    override val name: String,
    private val mimeHints: List<String>,
    private val typescript: Boolean,
) : HighlightedFormatter() {
    override fun handles(mimeType: String) = mimeHints.any { mimeType.contains(it, ignoreCase = true) }

    override fun format(bytes: ByteArray, mimeType: String): String {
        val text = textOrNull(bytes)?.trim() ?: return "Body is not text."
        if (text.isEmpty()) return ""
        return indentCode(text, CodeStyle())
    }

    override fun spansOf(text: String) = highlightScript(text, jsKeywords(typescript))
}

/** Minified-bundle-friendly JavaScript re-indent. */
class JsFormatter : ScriptFormatter(
    id = "bittrace.js",
    name = "JS",
    mimeHints = listOf("javascript", "ecmascript", "jsx"),
    typescript = false,
)

/** As [JsFormatter], plus the TypeScript-only keywords in the highlighter. */
class TsFormatter : ScriptFormatter(
    id = "bittrace.ts",
    name = "TS",
    mimeHints = listOf("typescript", "tsx"),
    typescript = true,
)

/**
 * GraphQL documents — one field per line inside a selection set, arguments kept
 * inline. Also recognises `#` comments.
 */
class GraphQlFormatter : HighlightedFormatter() {
    override val id = "bittrace.graphql"
    override val name = "GRAPHQL"
    override fun handles(mimeType: String) = mimeType.contains("graphql", ignoreCase = true)

    override fun format(bytes: ByteArray, mimeType: String): String {
        val text = textOrNull(bytes)?.trim() ?: return "Body is not text."
        if (text.isEmpty()) return ""
        // GraphQL is very often POSTed as JSON ({query, variables}); pull the
        // document out so the chip does something useful there too.
        val document = queryFromJsonEnvelope(text) ?: text
        return indentCode(document, STYLE)
    }

    override fun spansOf(text: String) = highlightGraphQl(text)

    /** Returns the `query` field of a `{"query": "...", …}` envelope, if any. */
    private fun queryFromJsonEnvelope(text: String): String? = try {
        val obj = Json.parseToJsonElement(text) as? JsonObject
        (obj?.get("query") as? JsonPrimitive)?.content
    } catch (e: Exception) {
        null
    }

    private companion object {
        val STYLE = CodeStyle(
            lineComments = listOf("#"),
            blockComments = false,
            quotes = "\"",
            breakOnSemicolon = false,
            breakInsideBraces = true,
        )
    }
}
