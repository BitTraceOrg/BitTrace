package org.bittrace.ui.layouts.inspector.components

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import org.bittrace.data.HarCookie
import org.bittrace.data.NameValuePair
import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide
import java.io.StringWriter

/**
 * One captured flow, rendered as something you can paste somewhere else.
 *
 * Both forms take the body through the same `(id, side) -> ByteArray?` lambda
 * the Inspector uses, so a flow whose body was evicted still exports — without
 * one, rather than failing.
 */

/**
 * The flow as a `curl` command.
 *
 * Single-quoted arguments, because that is the only quoting that needs one
 * escape rule rather than several: inside single quotes a shell takes every
 * character literally, so the sole case to handle is a quote itself.
 */
fun curlOf(row: TrafficRow, body: (String, BodySide) -> ByteArray?): String {
    val req = row.request.request
    val parts = mutableListOf("curl")

    // GET is curl's default, so naming it only adds noise.
    if (!req.method.equals("GET", ignoreCase = true)) {
        parts += "-X ${req.method}"
    }
    parts += shellQuote(req.url)

    row.completeRequest?.request?.headers.orEmpty()
        // curl sets these itself from the body and the URL; repeating them
        // produces a command that contradicts what curl is about to send.
        .filterNot { it.name.lowercase() in SELF_MANAGED_HEADERS }
        .forEach { parts += "-H ${shellQuote("${it.name}: ${it.value}")}" }

    body(row.id, BodySide.REQUEST)
        ?.takeIf { it.isNotEmpty() }
        ?.let { parts += "--data-raw ${shellQuote(it.decodeToString())}" }

    // Wrapped so a long request stays readable when pasted into a terminal.
    return parts.joinToString(" \\\n  ")
}

/**
 * The flow as a HAR 1.2 document holding one entry.
 *
 * A whole document rather than a bare entry: HAR consumers expect a `log` with
 * a `version` and `entries`, and a fragment would have to be assembled by hand
 * before anything could read it. Streamed through Jackson for the same reason
 * the session exporter is — a body can be large, and this way it is never
 * built up as an intermediate string.
 */
fun harOf(row: TrafficRow, body: (String, BodySide) -> ByteArray?): String {
    val out = StringWriter()
    JsonFactory().createGenerator(out).useDefaultPrettyPrinter().use { g ->
        g.writeStartObject()
        g.writeObjectFieldStart("log")
        g.writeStringField("version", "1.2")
        g.writeObjectFieldStart("creator")
        g.writeStringField("name", "BitTrace")
        g.writeStringField("version", "1.0")
        g.writeEndObject()
        g.writeArrayFieldStart("entries")
        writeEntry(g, row, body)
        g.writeEndArray()
        g.writeEndObject()
        g.writeEndObject()
    }
    return out.toString()
}

private fun writeEntry(g: JsonGenerator, row: TrafficRow, body: (String, BodySide) -> ByteArray?) {
    val req = row.request.request
    val resp = row.response

    g.writeStartObject()
    g.writeStringField("startedDateTime", row.request.startedDateTime)
    g.writeNumberField("time", resp?.time ?: 0.0)

    g.writeObjectFieldStart("request")
    g.writeStringField("method", req.method)
    g.writeStringField("url", req.url)
    g.writeStringField("httpVersion", req.httpVersion)
    writePairs(g, "headers", row.completeRequest?.request?.headers.orEmpty())
    writePairs(g, "cookies", row.completeRequest?.request?.cookies.orEmpty())
    g.writeArrayFieldStart("queryString"); g.writeEndArray()
    g.writeNumberField("headersSize", req.headersSize)
    g.writeNumberField("bodySize", req.bodySize)
    body(row.id, BodySide.REQUEST)?.takeIf { it.isNotEmpty() }?.let { bytes ->
        g.writeObjectFieldStart("postData")
        g.writeStringField("mimeType", row.completeRequest?.request?.postData?.mimeType.orEmpty())
        g.writeStringField("text", bytes.decodeToString())
        g.writeEndObject()
    }
    g.writeEndObject()

    g.writeObjectFieldStart("response")
    if (resp == null) {
        // A flow still in flight: a zero status is how HAR says "no response",
        // and it keeps the document valid rather than half-written.
        g.writeNumberField("status", 0)
        g.writeStringField("statusText", "")
        g.writeStringField("httpVersion", "")
        g.writeArrayFieldStart("headers"); g.writeEndArray()
        g.writeArrayFieldStart("cookies"); g.writeEndArray()
        g.writeObjectFieldStart("content")
        g.writeNumberField("size", 0)
        g.writeStringField("mimeType", "")
        g.writeEndObject()
        g.writeStringField("redirectURL", "")
        g.writeNumberField("headersSize", 0)
        g.writeNumberField("bodySize", 0)
    } else {
        val r = resp.response
        g.writeNumberField("status", r.status)
        g.writeStringField("statusText", r.statusText)
        g.writeStringField("httpVersion", r.httpVersion)
        writePairs(g, "headers", row.completeResponse?.response?.headers.orEmpty())
        writeCookies(g, row.completeResponse?.response?.cookies.orEmpty())
        g.writeObjectFieldStart("content")
        g.writeNumberField("size", r.bodySize)
        g.writeStringField("mimeType", row.completeResponse?.response?.content?.mimeType.orEmpty())
        body(row.id, BodySide.RESPONSE)?.takeIf { it.isNotEmpty() }?.let {
            g.writeStringField("text", it.decodeToString())
        }
        g.writeEndObject()
        g.writeStringField("redirectURL", r.redirectURL)
        g.writeNumberField("headersSize", r.headersSize)
        g.writeNumberField("bodySize", r.bodySize)
    }
    g.writeEndObject()

    g.writeStringField("cache", "")
    g.writeObjectFieldStart("timings")
    val t = resp?.timings
    g.writeNumberField("send", t?.send ?: 0.0)
    g.writeNumberField("wait", t?.wait ?: 0.0)
    g.writeNumberField("receive", t?.receive ?: 0.0)
    g.writeEndObject()
    g.writeEndObject()
}

/** Response cookies carry more than a name and a value, so they get their own. */
private fun writeCookies(g: JsonGenerator, cookies: List<HarCookie>) {
    g.writeArrayFieldStart("cookies")
    cookies.forEach {
        g.writeStartObject()
        g.writeStringField("name", it.name)
        g.writeStringField("value", it.value)
        it.path?.let { v -> g.writeStringField("path", v) }
        it.domain?.let { v -> g.writeStringField("domain", v) }
        it.expires?.let { v -> g.writeStringField("expires", v) }
        it.httpOnly?.let { v -> g.writeBooleanField("httpOnly", v) }
        it.secure?.let { v -> g.writeBooleanField("secure", v) }
        g.writeEndObject()
    }
    g.writeEndArray()
}

private fun writePairs(g: JsonGenerator, field: String, pairs: List<NameValuePair>) {
    g.writeArrayFieldStart(field)
    pairs.forEach {
        g.writeStartObject()
        g.writeStringField("name", it.name)
        g.writeStringField("value", it.value)
        g.writeEndObject()
    }
    g.writeEndArray()
}

/** `'` ends the quoted run, escapes itself outside it, and opens a new one. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private val SELF_MANAGED_HEADERS = setOf("content-length", "host")
