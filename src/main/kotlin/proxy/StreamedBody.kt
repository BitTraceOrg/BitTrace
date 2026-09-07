package org.bittrace.proxy

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlinx.serialization.Serializable

/**
 * A chunk of a streamed body. The bytes travel in the frame's body segment;
 * this is only the metadata beside them.
 *
 * [seq] counts every chunk the sidecar *attempted*, so a gap means the reader
 * fell behind and those bytes are gone — the totals on [BodyEndMessage] say how
 * many.
 */
@Serializable
data class BodyChunkMessage(
    val id: String,
    val side: String,
    val seq: Long = 0,
)

/** Closes a streamed body and reports what actually made it across. */
@Serializable
data class BodyEndMessage(
    val id: String,
    val side: String,
    /** Bytes seen on the wire. */
    val size: Long = 0,
    /** Bytes actually delivered as chunks; below [size] when chunks were dropped. */
    val captured: Long = 0,
    val chunks: Long = 0,
    val dropped: Long = 0,
    val truncated: Boolean = false,
    /** The flow errored before the body finished. */
    val aborted: Boolean = false,
    /** `Content-Encoding` still applied to the bytes, or blank for none. */
    val contentEncoding: String = "",
)

/**
 * Reassembles bodies that arrive as `BodyChunk` frames instead of inline.
 *
 * A body past the sidecar's streaming threshold is forwarded chunk by chunk so
 * that a large download is never held whole in the proxy. It is held whole
 * *here*, though — the inspector shows bytes, not a stream — so assembly stops
 * at [limit] and keeps the prefix rather than letting one download decide the
 * heap. What is kept is still worth having: the head of a file is where its
 * type, headers and first records are.
 *
 * Streamed bytes are also **not decoded** by mitmproxy: they come off the wire
 * with `Content-Encoding` still applied, unlike inline bodies. [end] undoes
 * that here so both kinds reach [BodyCache] in the same shape.
 *
 * Frames arrive on the sidecar's reader thread, but [clear] comes from the UI,
 * so the pending map is guarded.
 */
class StreamedBodies(
    private val limit: Int = DEFAULT_LIMIT,
    private val onLog: (LogEntry) -> Unit = {},
) {

    private class Pending {
        val bytes = ByteArrayOutputStream()
        var clipped = false
    }

    private val pending = HashMap<String, Pending>()

    /** Appends one chunk, up to [limit]; chunks past it are counted and dropped. */
    fun chunk(message: BodyChunkMessage, body: ByteArray) {
        if (body.isEmpty()) return
        val entry = synchronized(pending) { pending.getOrPut(key(message.id, message.side)) { Pending() } }
        synchronized(entry) {
            val room = limit - entry.bytes.size()
            if (room <= 0) {
                entry.clipped = true
                return
            }
            entry.bytes.write(body, 0, minOf(room, body.size))
            if (body.size > room) entry.clipped = true
        }
    }

    /**
     * Closes a streamed body and returns it decoded, or null when nothing was
     * captured. Anything lost — dropped chunks, an aborted flow, or a body past
     * [limit] — is logged, since the bytes returned here are then a prefix that
     * looks like a whole body to everything downstream.
     */
    fun end(message: BodyEndMessage): ByteArray? {
        val entry = synchronized(pending) { pending.remove(key(message.id, message.side)) } ?: return null
        val (raw, clipped) = synchronized(entry) {
            entry.bytes.toByteArray().also { entry.bytes.reset() } to entry.clipped
        }

        val complete = !clipped && !message.truncated && !message.aborted
        if (!complete) {
            val reasons = buildList {
                if (message.truncated) add("${message.dropped} chunk(s) dropped, the reader fell behind")
                if (message.aborted) add("the flow errored before the body finished")
                if (clipped) add("held to ${limit / (1024 * 1024)} MiB")
            }
            onLog(
                LogEntry(
                    level = "warn",
                    source = "proxy",
                    message = "partial ${message.side} body for ${message.id}: " +
                        "kept ${raw.size} of ${message.size} bytes — ${reasons.joinToString("; ")}",
                )
            )
        }

        if (raw.isEmpty()) return null
        return decode(raw, message.contentEncoding, complete, message.id)
    }

    /** Drops everything still being assembled (the capture was cleared, or stopped). */
    fun clear() = synchronized(pending) { pending.clear() }

    /**
     * Undoes `Content-Encoding`.
     *
     * Driven through [Inflater] directly rather than through the stream
     * wrappers, because a body cut short is the normal case here: the wrappers
     * throw at the end of a partial stream and take that call's already-inflated
     * bytes with them, while the inflater simply stops with everything it
     * managed to produce still in hand. A truncated page then reads as a page,
     * and only a body that yields nothing at all falls back to the encoded
     * bytes.
     */
    private fun decode(body: ByteArray, encoding: String, complete: Boolean, id: String): ByteArray =
        when (val name = encoding.trim().lowercase()) {
            "", "identity" -> body
            // Past the header, a gzip member is a raw deflate stream.
            "gzip", "x-gzip" ->
                gzipHeaderLength(body)?.let { inflate(body, it, nowrap = true, complete, id, name) } ?: body
            // Servers send both the zlib-wrapped and the bare form under this
            // name; a zlib header's low nibble is the deflate compression method.
            "deflate" ->
                inflate(body, 0, nowrap = body.size < 2 || (body[0].toInt() and 0x0F) != 0x08, complete, id, name)
            // brotli and zstd have no JDK decoder; the bytes are still the
            // body, just an encoded one, and the headers say which.
            else -> body.also {
                onLog(LogEntry("info", "proxy", "streamed body for $id left encoded: no decoder for '$encoding'"))
            }
        }

    private fun inflate(
        body: ByteArray,
        offset: Int,
        nowrap: Boolean,
        complete: Boolean,
        id: String,
        encoding: String,
    ): ByteArray {
        val out = ByteArrayOutputStream(body.size * 2)
        val inflater = Inflater(nowrap)
        try {
            inflater.setInput(body, offset, body.size - offset)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read > 0) {
                    out.write(buffer, 0, read)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    // Out of input: the whole body was there, or it was not.
                    break
                }
            }
        } catch (e: DataFormatException) {
            // Expected on a partial body; only a whole one failing is news.
            if (complete) onLog(LogEntry("warn", "proxy", "decoding '$encoding' body for $id failed: $e"))
        } finally {
            inflater.end()
        }
        return if (out.size() > 0) out.toByteArray() else body
    }

    /**
     * Length of the gzip member header, or null if these bytes do not open one.
     * RFC 1952: a fixed ten bytes, then whichever optional fields the flags
     * claim.
     */
    private fun gzipHeaderLength(body: ByteArray): Int? {
        fun u8(at: Int) = body[at].toInt() and 0xFF
        if (body.size < 10 || u8(0) != 0x1F || u8(1) != 0x8B || u8(2) != 0x08) return null

        val flags = u8(3)
        var at = 10
        if (flags and 0x04 != 0) { // FEXTRA: a two-byte length, then that many bytes
            if (at + 2 > body.size) return null
            at += 2 + (u8(at) or (u8(at + 1) shl 8))
        }
        if (flags and 0x08 != 0) at = afterZeroByte(body, at) ?: return null // FNAME
        if (flags and 0x10 != 0) at = afterZeroByte(body, at) ?: return null // FCOMMENT
        if (flags and 0x02 != 0) at += 2 // FHCRC
        return at.takeIf { it <= body.size }
    }

    /** The index just past the next NUL, or null if the string never ends. */
    private fun afterZeroByte(body: ByteArray, from: Int): Int? {
        var at = from
        while (at < body.size) {
            if (body[at].toInt() == 0) return at + 1
            at++
        }
        return null
    }

    private fun key(id: String, side: String): String = "$id\u0000${side.lowercase()}"

    companion object {
        /**
         * How much of one streamed body is kept. The sidecar streams precisely
         * so a large transfer is not buffered; holding all of it here would put
         * the cost back a process later.
         */
        const val DEFAULT_LIMIT = 32 * 1024 * 1024
    }
}
