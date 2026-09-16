package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    val failed: Boolean? get() = response?.let { it.error || it.response.status >= 400 }

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

    /** Ties a request to the CONNECT that opened its tunnel, when there was one. */
    val clientConnectionId: String get() = request.clientConnectionId

    override fun toString(): String =
        "TrafficRow(#$rowCount ${request.request.method} ${request.request.url} -> ${response?.response?.status})"
}
