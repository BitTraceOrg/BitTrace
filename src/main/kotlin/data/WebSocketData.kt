package org.bittrace.data

import kotlinx.serialization.Serializable

// Kotlin mirror of the WebSocket half of the sidecar protocol (tags 11 and 12).
//
// A WebSocket begins as an ordinary flow — `GET` with `Upgrade: websocket`,
// answered `101` — and that handshake arrives through the usual four frames, so
// it is already a [TrafficRow] by the time any of this lands. What follows is
// not HTTP: the messages travel outside any body and so appear in no
// `CompleteResponse`. They are reported as their own frames, sharing the
// handshake's flow id, and this is the only account of them there is.

/**
 * One WebSocket message. The payload travels in the frame's body segment; this
 * is only the metadata beside it.
 *
 * [seq] counts every message the sidecar *attempted*, per connection, so a gap
 * means the consumer fell behind and those messages are gone — the same
 * best-effort contract as a streamed body chunk. [WebSocketEndData.dropped]
 * says how many were lost in total.
 */
@Serializable
data class WebSocketMessageData(
    val id: String,
    val seq: Long = 0,
    /**
     * Client → server. This replaces the request/response `side` a body has:
     * both directions travel the one connection, so that split does not apply.
     */
    val fromClient: Boolean = false,
    /**
     * `"text"` or `"binary"`. mitmproxy assembles only these two — ping, pong
     * and close are handled by its protocol layer and never surface.
     */
    val type: String = "",
    /**
     * The message's real length, which is **not** the payload's length when
     * [truncated] — see there.
     */
    val size: Long = 0,
    /**
     * The payload was cut to the sidecar's per-message limit and only its first
     * part was sent. [size] still reports the whole.
     */
    val truncated: Boolean = false,
    /** ISO 8601, when the message was received. */
    val timestamp: String = "",
    /** mitmproxy generated this message rather than relaying it. */
    val injected: Boolean = false,
    /** mitmproxy blocked it, so it never reached the peer. */
    val dropped: Boolean = false,
)

/** Closes a WebSocket and reports what actually made it across. */
@Serializable
data class WebSocketEndData(
    val id: String,
    /**
     * The close handshake's code, e.g. `1000`. `1006` means the socket died
     * without one, and [closeReason] is then null.
     */
    val closeCode: Int? = null,
    val closeReason: String? = null,
    /** Which peer closed first, or null if neither did cleanly. */
    val closedByClient: Boolean? = null,
    /** Messages actually emitted. */
    val messages: Long = 0,
    /** Messages lost because the consumer fell behind. */
    val dropped: Long = 0,
    /** Messages that were sent cut short. */
    val truncated: Long = 0,
    /** Payload bytes seen in each direction, counted before any truncation. */
    val bytesFromClient: Long = 0,
    val bytesFromServer: Long = 0,
    /**
     * No clean close handshake: the flow errored, or the code was `1006` or
     * absent.
     */
    val aborted: Boolean = false,
) {
    /** The close handshake as one line, for display. */
    val closeSummary: String
        get() {
            val code = closeCode?.toString() ?: "—"
            val reason = closeReason?.takeIf { it.isNotBlank() }
            val by = when (closedByClient) {
                true -> "client"
                false -> "server"
                null -> null
            }
            return buildString {
                append(code)
                if (reason != null) append(" $reason")
                if (by != null) append(" (by $by)")
            }
        }
}

/**
 * One message as it is kept for display: its metadata plus as much of the
 * payload as was retained.
 *
 * [payload] is not always the whole message. The sidecar cuts one past its own
 * limit ([WebSocketMessageData.truncated]), and this end cuts one past
 * [clipped] — either way [WebSocketMessageData.size] is the real length, so
 * show that rather than the array's.
 *
 * Unlike a streamed body, the payload arrives **decoded**: a
 * `permessage-deflate` message is decompressed by mitmproxy before it reaches
 * the frame, so there is no content encoding to undo here.
 */
class WebSocketRecord(
    val message: WebSocketMessageData,
    val payload: ByteArray,
    /** This end kept only the first [payload].size bytes of what arrived. */
    val clipped: Boolean = false,
) {
    val fromClient: Boolean get() = message.fromClient
    val isText: Boolean get() = message.type.equals("text", ignoreCase = true)

    /** Whole bytes are held only when nothing cut the payload short. */
    val complete: Boolean get() = !clipped && !message.truncated
}
