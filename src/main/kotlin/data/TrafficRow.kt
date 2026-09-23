package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * One row of the traffic table: the request skeleton that opened the flow plus
 * everything that arrives later for the same flow id.
 *
 * The late-arriving parts are Compose snapshot state, so any composable reading
 * them recomposes when a response lands on a row already on screen.
 */
class TrafficRow(
    val rowCount: Int,
    val request: InitialRequestData,
    /** [LIVE_SESSION] for captured flows, or the id of the import it came from. */
    val sessionId: Int = LIVE_SESSION,
    /**
     * This row is a CONNECT tunnel rather than a request that went through one.
     * The two arrive on separate frames with unrelated ids, and everything
     * downstream reads the same HAR-shaped messages either way — this is the
     * one bit that says which kind of flow produced them.
     */
    val isConnect: Boolean = false,
    /** `host:port` of the client, on a CONNECT row; blank on every other. */
    val clientAddress: String = "",
    /**
     * This row is a connection's TLS handshake — the client hello and what the
     * server answered — rather than an HTTP flow. It has no response, headers
     * or body; everything it shows comes from [tls]. Advanced capture only.
     */
    val isTls: Boolean = false,
) {
    val id: String get() = request.id

    var response by mutableStateOf<InitialResponseData?>(null)
    var completeRequest by mutableStateOf<CompleteRequestMessage?>(null)
    var completeResponse by mutableStateOf<CompleteResponseMessage?>(null)

    /**
     * Whether this flow went wrong — a reset, or a 4xx/5xx.
     *
     * Written out four times as `it.error || it.response.status >= 400`, twice
     * more as its inverse, and once with the two halves in adjacent lines of
     * the same function. A predicate copied six times is a predicate that will
     * eventually mean six things.
     *
     * Null while the response is still outstanding: a flow in flight has not
     * failed and has not succeeded, and counting it either way is a lie the
     * status bar would tell for as long as the request takes.
     */
    val failed: Boolean?
        get() = if (isTls) tlsFailed else response?.let { it.error || it.response.status >= 400 }

    /**
     * A TLS row's outcome: failed as soon as either hop fails, succeeded once
     * the origin hop is up and the client hop has not failed, null before that.
     */
    private val tlsFailed: Boolean?
        get() {
            val t = tls ?: return null
            return when {
                t.failure != null -> true
                t.serverHandshake != null || t.clientHandshake != null -> false
                else -> null
            }
        }

    /**
     * Wire length of each body, taking the measured figure once the completing
     * frame has landed and the header's guess until then — see
     * [requestBodySizeOf] and [responseBodySizeOf] for why the two differ.
     * Read these rather than reaching into `request.request.bodySize`, which is
     * only ever the guess.
     */
    val requestBodySize: Long get() = requestBodySizeOf(request, completeRequest)

    /** Null until some part of the response has arrived. */
    val responseBodySize: Long? get() = responseBodySizeOf(response, completeResponse)

    /**
     * Bytes of a body that is still arriving, counted as its chunks land; 0
     * when nothing is in flight on that side.
     *
     * Only a streamed body has one. The sidecar streams a body it will not hold
     * — too large, too slow, or a live stream by content type — and a live
     * stream can stay open for minutes, so the completing frame that would
     * otherwise be the first news of a body is a long way off. This is the
     * progress in the meantime, and it is snapshot state so a view watching one
     * arrive repaints as it does.
     */
    var streamedRequestBytes by mutableStateOf(0L)
    var streamedResponseBytes by mutableStateOf(0L)

    /** How much of [side]'s body has arrived while it is still streaming. */
    fun streamedBytes(request: Boolean): Long =
        if (request) streamedRequestBytes else streamedResponseBytes

    // --- WebSocket ---

    /**
     * Messages exchanged after this flow's handshake, oldest first.
     *
     * Empty for every flow that is not a WebSocket, which is nearly all of
     * them — a snapshot list rather than a nullable one so a transcript filling
     * while it is on screen repaints without the row being replaced. Bounded:
     * see [SessionStore.onWebSocketMessage] for what happens when a connection
     * outruns the cap.
     */
    val webSocketMessages: SnapshotStateList<WebSocketRecord> = mutableStateListOf()

    /**
     * Payload bytes currently held in [webSocketMessages], maintained as they
     * are added and evicted.
     *
     * A plain var rather than snapshot state: it exists to enforce the cap, is
     * touched only by the store on the event thread, and nothing draws it. It
     * would have to be re-summed over the whole transcript on every message
     * otherwise, which on a busy socket is the one place that cost lands on the
     * UI thread.
     */
    var webSocketBytes: Long = 0

    /** The close handshake and totals, once the connection has ended. */
    var webSocketEnd by mutableStateOf<WebSocketEndData?>(null)

    /**
     * Messages this end dropped to stay within its cap — distinct from
     * [WebSocketEndData.dropped], which counts the ones the sidecar never sent.
     */
    var webSocketEvicted by mutableStateOf(0L)

    /**
     * This flow is a WebSocket.
     *
     * A `101` alone is not enough, which is the trap here: it says the
     * connection switched protocols, not which protocol it switched *to*. An
     * `Upgrade: h2c` answered `101` is not a WebSocket and will never produce a
     * message, so taking the status on its own gives that flow a transcript tab
     * that stays empty for the life of the row. The sidecar draws the same
     * distinction a level up — it captures from mitmproxy's `websocket_start`
     * hook, which fires for a WebSocket handshake and nothing else.
     *
     * So: the `Upgrade` header, which is what actually names the protocol, or
     * frames that have already arrived. The header is the one that matters
     * before any message has been sent — a socket can sit idle for minutes
     * after its handshake — and it is in hand by then, since the messages
     * follow the `CompleteResponse` that carries it. The other two are the
     * fallback for any ordering that beats it.
     */
    val isWebSocket: Boolean
        get() = webSocketMessages.isNotEmpty() ||
            webSocketEnd != null ||
            (response?.response?.status == 101 && upgradesToWebSocket)

    /** The response's `Upgrade` header naming WebSocket, per RFC 6455's handshake. */
    private val upgradesToWebSocket: Boolean
        get() = completeResponse?.response?.headers?.any {
            it.name.equals("upgrade", ignoreCase = true) &&
                it.value.trim().equals("websocket", ignoreCase = true)
        } == true

    /** Ties a request to the CONNECT that opened its tunnel, when there was one. */
    val clientConnectionId: String get() = request.clientConnectionId

    /**
     * The TLS handshakes on this row's client connection — shared with every
     * other row on it. Attached by [SessionStore] when a live row arrives; null
     * for imported rows and for any flow with no connection id. Its contents
     * stay empty unless advanced capture is on.
     */
    var tls: TlsConnection? = null

    override fun toString(): String =
        "TrafficRow(#$rowCount ${request.request.method} ${request.request.url} -> ${response?.response?.status})"
}
