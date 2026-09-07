package org.bittrace.proxy

import java.io.InputStream

/**
 * One decoded frame from the sidecar: a message tag, JSON metadata, and an
 * optional raw body segment.
 */
class Frame(val tag: Int, val json: ByteArray, val body: ByteArray)

/** Message tags — must match `core.Identifiers.Identifiers` on the Python side. */
object Tags {
    const val LOG = 0
    const val PID = 1
    const val INITIAL_REQUEST = 2
    const val INITIAL_RESPONSE = 3
    const val COMPLETE_REQUEST = 4
    const val COMPLETE_RESPONSE = 5

    /** CONNECT tunnel setup. Its own flow, linked by `clientConnectionId`. */
    const val CONNECT_REQUEST = 6
    const val CONNECT_RESPONSE = 7

    /** One chunk of a body too large to ride inline on a `COMPLETE_*` frame. */
    const val BODY_CHUNK = 8

    /** End of a streamed body, with its totals and content encoding. */
    const val BODY_END = 9
}

/**
 * Reads length-prefixed binary frames from the sidecar's stdout.
 *
 * Layout per frame: `MAGIC(4) | tag(1) | json_len(4 LE) | body_len(4 LE) |
 * json | body`. On a corrupted header the reader scans forward byte-by-byte
 * until it re-finds [MAGIC], so a stray write into stdout costs one frame
 * rather than the whole stream.
 */
object FrameReader {

    /**
     * 4-byte sync marker prefixing every frame. Matches `MAGIC` in
     * MITMConnect/core/Utils.py.
     */
    val MAGIC = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte())

    /**
     * Upper bound on a single frame's json/body length. A length larger than
     * this is treated as stream corruption (resync) rather than attempting a
     * huge allocation. Generous enough for large media bodies.
     */
    const val MAX_SEGMENT_LEN = 512 * 1024 * 1024

    /** Reads frames until the stream ends, invoking [onFrame] for each. */
    fun read(input: InputStream, onFrame: (Frame) -> Unit) {
        val magic = ByteArray(4)
        if (!input.readFully(magic)) return

        while (true) {
            // Resynchronize: slide one byte at a time until the window holds MAGIC.
            while (!magic.contentEquals(MAGIC)) {
                magic.copyInto(magic, 0, 1, 4)
                val next = input.read()
                if (next < 0) return
                magic[3] = next.toByte()
            }

            val header = ByteArray(9)
            if (!input.readFully(header)) return
            val tag = header[0].toInt() and 0xFF
            val jsonLen = header.readLeInt(1)
            val bodyLen = header.readLeInt(5)

            // Guard against absurd lengths from a bad resync: discard and re-scan.
            if (jsonLen > MAX_SEGMENT_LEN || bodyLen > MAX_SEGMENT_LEN || jsonLen < 0 || bodyLen < 0) {
                magic.fill(0)
                if (!input.readFully(magic)) return
                continue
            }

            val json = ByteArray(jsonLen)
            if (!input.readFully(json)) return
            val body = ByteArray(bodyLen)
            if (!input.readFully(body)) return

            onFrame(Frame(tag, json, body))

            // Read the next frame's magic (or end the stream).
            if (!input.readFully(magic)) return
        }
    }

    /** Fills [buffer] completely; false if the stream ended first. */
    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    /** Little-endian u32 at [at]; negative only if the value exceeds Int.MAX_VALUE. */
    private fun ByteArray.readLeInt(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)
}
