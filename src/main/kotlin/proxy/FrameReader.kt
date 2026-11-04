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

    /** CONNECT tunnel setup. Its own flow, linked by `clientConnectionId`. Advanced capture only. */
    const val CONNECT_REQUEST = 6
    const val CONNECT_RESPONSE = 7

    /** One chunk of a body too large to ride inline on a `COMPLETE_*` frame. */
    const val BODY_CHUNK = 8

    /** End of a streamed body, with its totals and content encoding. */
    const val BODY_END = 9

    /**
     * The sidecar's own state, on a schedule rather than in reaction to
     * traffic — the only frame that arrives while nothing is being captured.
     */
    const val STATUS = 10

    /**
     * One WebSocket message, after the handshake flow it shares an id with.
     * Messages travel outside any body, so they appear in no `COMPLETE_*`
     * frame — this is the only place they are reported.
     */
    const val WEBSOCKET_MESSAGE = 11

    /** The WebSocket closed, with its close handshake and totals. */
    const val WEBSOCKET_END = 12

    /** A client began a TLS handshake. Advanced capture only. */
    const val TLS_CLIENT_HELLO = 13

    /** One hop's handshake finished or failed. Advanced capture only. */
    const val TLS_HANDSHAKE = 14

    /**
     * The outcome of one attempt to load a rule file or the manifest — sent
     * for every attempt, so a rule that did not parse is visible rather than
     * looking active. See [org.bittrace.rules].
     */
    const val RULE_STATUS = 15

    /** A flow paused at a breakpoint; the body segment is that side's decoded body. */
    const val BREAKPOINT_HIT = 16

    /** A paused flow went on: resumed, timed out, or its client or this app went away. */
    const val BREAKPOINT_RELEASED = 17
}

/**
 * Tags for the frames this end writes to the sidecar's stdin — a separate
 * numbering space, matching `core.Identifiers.Commands`. Rules are files, so
 * most commands only say which one to re-read; see [ControlChannel].
 */
object Commands {
    /** Re-read `<rules-dir>/<id>.yaml`. json: `{id}` */
    const val RULE_CHANGED = 1

    /** Forget a rule. json: `{id}` */
    const val RULE_REMOVED = 2

    /** Re-read `rules.yaml`: order, enabled flags, switches. json: `{}` */
    const val RULES_ORDER = 3

    /** Re-read the whole folder. json: `{}` */
    const val RULES_RELOAD = 4

    /** Release a paused flow; body = the replacement body, when there is one. */
    const val BREAKPOINT_RESUME = 5
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

/**
 * Writes frames in the layout [FrameReader] reads — the direction BitTrace ->
 * sidecar uses the same framing, so one description of it covers both.
 */
object FrameWriter {
    fun write(out: java.io.OutputStream, tag: Int, json: ByteArray, body: ByteArray = ByteArray(0)) {
        val header = ByteArray(9)
        header[0] = tag.toByte()
        header.writeLeInt(1, json.size)
        header.writeLeInt(5, body.size)
        out.write(FrameReader.MAGIC)
        out.write(header)
        if (json.isNotEmpty()) out.write(json)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun ByteArray.writeLeInt(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
        this[at + 2] = (value ushr 16).toByte()
        this[at + 3] = (value ushr 24).toByte()
    }
}
