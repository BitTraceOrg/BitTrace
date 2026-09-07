package org.bittrace.session

import org.bittrace.data.writeAtomically
import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import java.awt.EventQueue
import java.io.BufferedOutputStream
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.bittrace.ui.instantOf
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.NameValuePair
import org.bittrace.data.SessionStore
import org.bittrace.proxy.BodyCache
import org.bittrace.proxy.BodySide

/** What an export wrote, for the log line that follows it. */
class ExportResult(val written: Int, val skipped: Int, val error: String? = null)

/**
 * One flow, frozen for export.
 *
 * Copied on the event thread before the write begins. Snapshotting the row
 * *list* alone would not be enough: `TrafficRow.response`, `.completeRequest`
 * and `.completeResponse` are `mutableStateOf`, so reading them from the writer
 * thread could tear against a live merge mid-entry. These four references are
 * to already-immutable messages, so once copied they cannot change.
 */
private class ExportFlow(
    val id: String,
    val request: InitialRequestData,
    val response: InitialResponseData?,
    val completeRequest: CompleteRequestMessage?,
    val completeResponse: CompleteResponseMessage?,
)

/**
 * Writes a [SessionStore] to a HAR 1.2 file, one entry at a time.
 *
 * Nothing larger than a single entry is ever held: fields go straight to a
 * jackson `JsonGenerator`, and bodies are streamed through `writeBinaryField`,
 * which base64-encodes in chunks rather than building an encoded string.
 *
 * The file is written beside its destination and moved into place at the end,
 * so a failure part-way through cannot replace a good `.har` with a truncated
 * one. Call this off the UI thread.
 */
class HarExporter(
    private val store: SessionStore,
    private val bodies: BodyCache,
    private val onLog: (String, String) -> Unit = { _, _ -> },
) {

    fun export(path: Path): ExportResult {
        val flows = snapshot()
        if (flows.isEmpty()) return ExportResult(0, 0, "nothing to export")

        var written = 0
        var skipped = 0

        try {
            val factory = JsonFactory()
            writeAtomically(path) { stream ->
                BufferedOutputStream(stream, 64 * 1024).use { out ->
                    factory.createGenerator(out, JsonEncoding.UTF8).use { g ->
                        g.writeStartObject()
                        g.writeObjectFieldStart("log")
                        g.writeStringField("version", "1.2")
                        g.writeObjectFieldStart("creator")
                        g.writeStringField("name", "BitTrace")
                        g.writeStringField("version", APP_VERSION)
                        g.writeEndObject()

                        g.writeArrayFieldStart("entries")
                        for (flow in flows) {
                            // A flow whose response never arrived would export as
                            // empty headers and invented timings — misleading, so
                            // it is counted and left out instead.
                            if (flow.response == null) {
                                skipped++
                                continue
                            }
                            writeEntry(g, flow)
                            written++
                        }
                        g.writeEndArray()

                        g.writeEndObject() // log
                        g.writeEndObject()
                    }
                }
            }
        } catch (e: Exception) {
            return ExportResult(written, skipped, e.message ?: e::class.simpleName)
        }

        if (skipped > 0) onLog("warn", "$skipped flow(s) still in flight were not exported")
        return ExportResult(written, skipped)
    }

    /** Copies the rows and their message references on the event thread. */
    private fun snapshot(): List<ExportFlow> {
        val out = ArrayList<ExportFlow>()
        val copy = {
            store.rows.forEach {
                out.add(ExportFlow(it.id, it.request, it.response, it.completeRequest, it.completeResponse))
            }
        }
        if (EventQueue.isDispatchThread()) copy() else EventQueue.invokeAndWait(copy)
        return out
    }

    private fun writeEntry(g: JsonGenerator, flow: ExportFlow) {
        val res = flow.response ?: return
        val timings = res.timings

        g.writeStartObject()
        g.writeStringField("startedDateTime", isoOffset(flow.request.startedDateTime))

        // The spec requires time to equal the sum of the non-negative timings,
        // so derive it rather than trusting two sources to agree.
        val phases = listOf(
            timings.blocked, timings.dns, timings.connect,
            timings.ssl, timings.send, timings.wait, timings.receive,
        )
        val summed = phases.filter { it >= 0 }.sum()
        g.writeNumberField("time", if (phases.any { it >= 0 }) summed else res.time)

        writeRequest(g, flow)
        writeResponse(g, flow, res)

        // Required member; the proxy has no cache information to report.
        g.writeObjectFieldStart("cache")
        g.writeEndObject()

        g.writeObjectFieldStart("timings")
        g.writeNumberField("blocked", timings.blocked)
        g.writeNumberField("dns", timings.dns)
        g.writeNumberField("connect", timings.connect)
        g.writeNumberField("ssl", timings.ssl)
        g.writeNumberField("send", timings.send)
        g.writeNumberField("wait", timings.wait)
        g.writeNumberField("receive", timings.receive)
        g.writeEndObject()

        if (res.serverIPAddress.isNotEmpty()) g.writeStringField("serverIPAddress", res.serverIPAddress)
        if (res.connection.isNotEmpty()) g.writeStringField("connection", res.connection)
        // Extensions, so a BitTrace HAR round-trips without losing these.
        g.writeStringField("_tls", flow.request.tls)
        g.writeBooleanField("_error", res.error)
        g.writeEndObject()
    }

    private fun writeRequest(g: JsonGenerator, flow: ExportFlow) {
        val head = flow.request.request
        val complete = flow.completeRequest?.request

        g.writeObjectFieldStart("request")
        g.writeStringField("method", head.method)
        g.writeStringField("url", head.url)
        g.writeStringField("httpVersion", head.httpVersion)
        writePairs(g, "cookies", complete?.cookies ?: emptyList())
        writePairs(g, "headers", complete?.headers ?: emptyList())
        writePairs(g, "queryString", head.queryString)

        // postData must carry text or params to be valid, so it is omitted
        // entirely when the body was never cached or has since been evicted.
        val body = bodies.get(flow.id, BodySide.REQUEST)
        if (body != null && body.isNotEmpty()) {
            g.writeObjectFieldStart("postData")
            g.writeStringField("mimeType", complete?.postData?.mimeType ?: "")
            val text = utf8OrNull(body)
            if (text != null) {
                g.writeStringField("text", text)
            } else {
                // HAR has no encoding field on postData, so binary goes in an
                // extension rather than as mojibake in `text`.
                g.writeBinaryField("_bodyBase64", body)
            }
            g.writeEndObject()
        }

        g.writeNumberField("headersSize", head.headersSize)
        g.writeNumberField("bodySize", head.bodySize)
        g.writeEndObject()
    }

    private fun writeResponse(g: JsonGenerator, flow: ExportFlow, res: InitialResponseData) {
        val head = res.response
        val complete = flow.completeResponse?.response

        g.writeObjectFieldStart("response")
        g.writeNumberField("status", head.status)
        g.writeStringField("statusText", head.statusText)
        g.writeStringField("httpVersion", head.httpVersion)

        g.writeArrayFieldStart("cookies")
        complete?.cookies?.forEach { cookie ->
            g.writeStartObject()
            g.writeStringField("name", cookie.name)
            g.writeStringField("value", cookie.value)
            cookie.path?.let { g.writeStringField("path", it) }
            cookie.domain?.let { g.writeStringField("domain", it) }
            cookie.expires?.let { g.writeStringField("expires", it) }
            cookie.httpOnly?.let { g.writeBooleanField("httpOnly", it) }
            cookie.secure?.let { g.writeBooleanField("secure", it) }
            g.writeEndObject()
        }
        g.writeEndArray()

        writePairs(g, "headers", complete?.headers ?: emptyList())

        val body = bodies.get(flow.id, BodySide.RESPONSE)
        g.writeObjectFieldStart("content")
        // size is the wire length from the metadata, which stays accurate even
        // when the body itself has been evicted from the cache.
        g.writeNumberField("size", complete?.content?.size ?: head.bodySize)
        g.writeStringField("mimeType", complete?.content?.mimeType ?: "")
        if (body != null && body.isNotEmpty()) {
            // Always base64: it sidesteps every encoding question, and viewers
            // that honour `encoding` (Chrome, Fiddler) decode it correctly.
            g.writeStringField("encoding", "base64")
            g.writeBinaryField("text", body)
        } else if (body == null) {
            g.writeStringField("comment", "body not retained")
        }
        g.writeEndObject()

        g.writeStringField("redirectURL", head.redirectURL)
        g.writeNumberField("headersSize", head.headersSize)
        g.writeNumberField("bodySize", head.bodySize)
        g.writeEndObject()
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

    /** The bytes as text, or null when they are not valid UTF-8. */
    private fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }

    /**
     * HAR requires an offset; the sidecar's timestamps do not always carry one
     * (see `instantOf`), so re-normalise and fall back to the raw value.
     */
    private fun isoOffset(value: String): String =
        instantOf(value)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            ?: value

    private companion object {
        const val APP_VERSION = "0.1.1"
    }
}
