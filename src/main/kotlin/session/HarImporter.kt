package org.bittrace.session

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.HarContent
import org.bittrace.data.HarCookie
import org.bittrace.data.HarTimings
import org.bittrace.data.ImportedFlow
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficStrings
import org.bittrace.proxy.BodyCache
import org.bittrace.proxy.BodySide

/** What an import did, for the log line that follows it. */
class ImportResult(
    val imported: Int,
    val skipped: Int,
    val bodiesSkipped: Int,
    val error: String? = null,
)

/**
 * Reads a HAR 1.2 file into a [SessionStore], one entry at a time.
 *
 * The file is never held in memory. jackson walks to `log.entries` and lifts
 * out a single entry's raw JSON, which kotlinx then decodes into the
 * [HarEntry] mirror types; only that one entry is materialised. This matters
 * because a HAR of a real browsing session runs to hundreds of MB once bodies
 * are inlined as base64, and decoding the document whole would spike the heap
 * to several times the file size.
 *
 * Call this off the UI thread. Rows reach the store in batches, and the store
 * marshals each batch onto the event thread itself.
 */
class HarImporter(
    private val store: SessionStore,
    private val bodies: BodyCache,
    private val onLog: (String, String) -> Unit = { _, _ -> },
) {

    fun import(path: Path): ImportResult {
        // The banner name is the file's base name: "checkout-run.har" reads as
        // SESSION checkout-run START.
        val sessionId = store.beginSession(path.fileName.toString().substringBeforeLast('.'))
        val generation = store.generation
        var imported = 0
        var skipped = 0
        var bodiesSkipped = 0
        var error: String? = null
        val batch = ArrayList<ImportedFlow>(BATCH)

        try {
            val factory = JsonFactory()
            factory.createParser(Files.newInputStream(path).buffered()).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return ImportResult(0, 0, 0, "not a JSON object")
                }
                forEachEntry(factory, parser) { raw ->
                    // A single pathological entry (a HAR carrying a video body)
                    // would otherwise materialise raw JSON + base64 + bytes.
                    if (raw.length > MAX_ENTRY_CHARS) {
                        skipped++
                        return@forEachEntry true
                    }
                    val entry = runCatching { json.decodeFromString<HarEntry>(raw) }.getOrNull()
                    if (entry == null) {
                        skipped++
                        return@forEachEntry true
                    }

                    val id = "s$sessionId-${imported + skipped}"
                    batch.add(toFlow(id, entry))
                    if (!cacheBodies(id, entry)) bodiesSkipped++
                    imported++

                    if (batch.size >= BATCH) {
                        if (store.generation != generation) return@forEachEntry false
                        store.importBatch(sessionId, ArrayList(batch))
                        batch.clear()
                    }
                    imported < MAX_ENTRIES
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }

        if (batch.isNotEmpty() && store.generation == generation) {
            store.importBatch(sessionId, batch)
        }
        store.endSession(sessionId)

        if (bodiesSkipped > 0) onLog("warn", "$bodiesSkipped body/bodies too large to cache")
        if (skipped > 0) onLog("warn", "$skipped entry/entries could not be read")
        return ImportResult(imported, skipped, bodiesSkipped, error)
    }

    /**
     * Walks to `log.entries` and hands each element's raw JSON to [onEntry],
     * stopping early when it returns false.
     *
     * jackson quirk worth knowing: after `nextToken()` lands on a FIELD_NAME you
     * must advance again before `skipChildren()`, which is a no-op on a field
     * name and would silently mis-parse the rest of the document.
     */
    private inline fun forEachEntry(
        factory: JsonFactory,
        parser: JsonParser,
        onEntry: (String) -> Boolean,
    ) {
        while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
            if (parser.currentName() != "log") {
                parser.nextToken(); parser.skipChildren(); continue
            }
            parser.nextToken() // START_OBJECT of log
            while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
                if (parser.currentName() != "entries") {
                    parser.nextToken(); parser.skipChildren(); continue
                }
                if (parser.nextToken() != JsonToken.START_ARRAY) return
                while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                    val raw = StringWriter()
                    factory.createGenerator(raw).use { it.copyCurrentStructure(parser) }
                    if (!onEntry(raw.toString())) return
                }
                return
            }
        }
    }

    /** Maps one HAR entry onto the four messages the store already understands. */
    private fun toFlow(id: String, entry: HarEntry): ImportedFlow {
        val req = entry.request
        val res = entry.response

        val initialRequest = InitialRequestData(
            id = id,
            startedDateTime = entry.startedDateTime,
            request = InitialRequestData.RequestHead(
                method = TrafficStrings.intern(req.method),
                url = req.url,
                httpVersion = TrafficStrings.intern(req.httpVersion),
                headersSize = req.headersSize,
                bodySize = req.bodySize,
                queryString = req.queryString.map { TrafficStrings.pair(it.name, it.value) },
            ),
            tls = TrafficStrings.intern(entry.tls),
        )

        // A HAR carries no notion of "response not yet received"; status 0 is
        // what every tool writes for a flow that never completed.
        val initialResponse = InitialResponseData(
            id = id,
            serverIPAddress = TrafficStrings.intern(entry.serverIPAddress),
            connection = TrafficStrings.intern(entry.connection),
            error = res.status == 0,
            response = InitialResponseData.ResponseHead(
                status = res.status,
                statusText = TrafficStrings.intern(res.statusText),
                httpVersion = TrafficStrings.intern(res.httpVersion),
                headersSize = res.headersSize,
                bodySize = res.bodySize,
                redirectURL = res.redirectURL,
            ),
            timings = entry.timings.let {
                HarTimings(it.blocked, it.dns, it.connect, it.send, it.wait, it.receive, it.ssl)
            },
            time = entry.time,
        )

        val completeRequest = CompleteRequestMessage(
            id = id,
            request = CompleteRequestMessage.RequestBody(
                headers = req.headers.map { TrafficStrings.pair(it.name, it.value) },
                // HAR request cookies may carry path/domain/etc; the wire model
                // keeps only name/value on this side (responses keep the rest).
                cookies = req.cookies.map { TrafficStrings.pair(it.name, it.value) },
                postData = req.postData?.let {
                    CompleteRequestMessage.PostData(TrafficStrings.intern(it.mimeType))
                },
            ),
        )

        val completeResponse = CompleteResponseMessage(
            id = id,
            response = CompleteResponseMessage.ResponseBody(
                headers = res.headers.map { TrafficStrings.pair(it.name, it.value) },
                cookies = res.cookies.map {
                    HarCookie(
                        name = TrafficStrings.intern(it.name),
                        value = it.value,
                        path = it.path?.let(TrafficStrings::intern),
                        domain = it.domain?.let(TrafficStrings::intern),
                        expires = it.expires,
                        httpOnly = it.httpOnly,
                        secure = it.secure,
                    )
                },
                content = HarContent(
                    size = res.content.size,
                    mimeType = TrafficStrings.intern(res.content.mimeType),
                ),
            ),
            timings = CompleteResponseMessage.Timings(entry.timings.receive),
            time = entry.time,
        )

        return ImportedFlow(initialRequest, initialResponse, completeRequest, completeResponse)
    }

    /** Decodes both bodies into the cache. False when one was too big to keep. */
    private fun cacheBodies(id: String, entry: HarEntry): Boolean {
        var ok = true

        entry.request.postData?.let { post ->
            val bytes = when {
                post.bodyBase64 != null -> decodeBase64(post.bodyBase64)
                post.text != null -> post.text.toByteArray(Charsets.UTF_8)
                else -> null
            }
            if (bytes != null) {
                if (bytes.size > MAX_BODY_BYTES) ok = false
                else if (bytes.isNotEmpty()) bodies.put(id, BodySide.REQUEST, bytes)
            }
        }

        val content = entry.response.content
        content.text?.let { text ->
            val bytes = if (content.encoding.equals("base64", ignoreCase = true)) {
                decodeBase64(text)
            } else {
                text.toByteArray(Charsets.UTF_8)
            }
            if (bytes != null) {
                if (bytes.size > MAX_BODY_BYTES) ok = false
                else if (bytes.isNotEmpty()) bodies.put(id, BodySide.RESPONSE, bytes)
            }
        }
        return ok
    }

    private fun decodeBase64(text: String): ByteArray? =
        runCatching { Base64.getMimeDecoder().decode(text) }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Rows handed to the store at once — see SessionStore.importBatch. */
        const val BATCH = 200

        /** Hard ceiling on one import, so a huge file cannot exhaust the heap. */
        const val MAX_ENTRIES = 50_000

        /** Skip an entry whose raw JSON is this large; it is a body, not metadata. */
        const val MAX_ENTRY_CHARS = 8 * 1024 * 1024

        /** Bodies above this are dropped rather than cached. */
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
