package org.bittrace.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Kotlin mirror of src/types/TrafficData.ts (HAR-compatible sub-types).

/** Header, query and request-cookie pairs; pooled as they decode (see [NameValuePairSerializer]). */
@Serializable(with = NameValuePairSerializer::class)
data class NameValuePair(
    val name: String,
    val value: String,
)

@Serializable(with = HarCookieSerializer::class)
data class HarCookie(
    val name: String,
    val value: String,
    val path: String? = null,
    val domain: String? = null,
    val expires: String? = null,
    val httpOnly: Boolean? = null,
    val secure: Boolean? = null,
)

@Serializable
data class HarTimings(
    val blocked: Double,
    val dns: Double,
    val connect: Double,
    val send: Double,
    val wait: Double,
    val receive: Double,
    val ssl: Double,
)

/**
 * The body itself is fetched on demand as raw bytes via the `get_body`
 * command (keyed by flow id) — it is not inlined/base64 here.
 */
@Serializable
data class HarContent(
    /**
     * The body length with `Content-Encoding` undone, whether or not the body
     * was streamed — for a streamed one the decoded length is counted as the
     * chunks go past. `-1` when the encoding was malformed and the decoded
     * length is genuinely unknowable.
     */
    val size: Long,
    @Serializable(with = InternedStringSerializer::class) val mimeType: String,
    /** Bytes actually received, before decoding. `-1` on a flow that predates the field. */
    val wireSize: Long = -1,
    /** `size - wireSize`, present only when decoding grew the body (per HAR). */
    val compression: Long? = null,
    /** The flow errored mid-body: these sizes are what did arrive, not a total. */
    val partial: Boolean = false,
)

// ---------------------------------------------------------------------------
// Initial messages  (emitted before full body is available)
// ---------------------------------------------------------------------------

/**
 * Emitted on `requestheaders`. Contains the HAR entry skeleton and the
 * request fields available before the body is read.
 */
@Serializable
data class InitialRequestData(
    val id: String,
    val startedDateTime: String,
    val request: RequestHead,
    @SerialName("_tls") val tls: String,
    /**
     * mitmproxy's client connection id, shared with the CONNECT that opened
     * this request's tunnel (see [ConnectRequestData]). Blank on a flow that
     * predates the field, or one that never went through a tunnel.
     */
    val clientConnectionId: String = "",
) {
    @Serializable
    data class RequestHead(
        val method: String,
        val url: String,
        val httpVersion: String,
        val headersSize: Long,
        /**
         * Guessed from `Content-Length`, and so `0` on a chunked upload where
         * the header is absent. [CompleteRequestMessage.RequestBody.bodySize]
         * corrects it once the body has gone past; see [requestBodySizeOf].
         */
        val bodySize: Long,
        val queryString: List<NameValuePair> = emptyList(),
    )
}

/**
 * Emitted on `responseheaders` (or `error`). Contains the response fields
 * available once headers arrive, including partial timings.
 */
@Serializable
data class InitialResponseData(
    val id: String,
    val serverIPAddress: String,
    val connection: String,
    @SerialName("_error") val error: Boolean,
    val response: ResponseHead,
    val timings: HarTimings,
    val time: Double,
) {
    @Serializable
    data class ResponseHead(
        val status: Int,
        val statusText: String,
        val httpVersion: String,
        val headersSize: Long,
        /**
         * The `Content-Length` **header**, so `-1` on every chunked and HTTP/2
         * response, where no such header exists. A hint to show while the body
         * is still in flight — [CompleteResponseMessage.ResponseBody.bodySize]
         * is the measured figure. See [responseBodySizeOf].
         */
        val bodySize: Long,
        val redirectURL: String,
        /**
         * Only on an errored flow that had already received part of its body.
         * No `CompleteResponse` follows one, so this is the only report of what
         * arrived, and it is flagged [HarContent.partial].
         */
        val content: HarContent? = null,
    )
}

// ---------------------------------------------------------------------------
// Complete messages  (emitted after full body is available)
// ---------------------------------------------------------------------------

/** Emitted on `request`. Contains headers, cookies, and optional postData. */
@Serializable
data class CompleteRequestMessage(
    val id: String,
    val request: RequestBody,
    /**
     * The body was too large to ride inline and arrived as `BodyChunk` frames
     * instead; this frame's body segment is empty. See
     * [org.bittrace.proxy.StreamedBodies].
     */
    @SerialName("_bodyStreamed") val bodyStreamed: Boolean = false,
) {
    @Serializable
    data class RequestBody(
        val headers: List<NameValuePair> = emptyList(),
        val cookies: List<NameValuePair> = emptyList(),
        /**
         * The body length as measured once all of it had been sent, which is
         * the figure to prefer over the `Content-Length` guess on
         * [InitialRequestData.RequestHead.bodySize]. `-1` on a flow that
         * predates the field, and on a CONNECT, which has no body.
         */
        val bodySize: Long = -1,
        /** Body fetched on demand via `get_body` (side: "request"). */
        val postData: PostData? = null,
    )

    @Serializable
    data class PostData(
        @Serializable(with = InternedStringSerializer::class) val mimeType: String,
    )
}

/** Emitted on `response`. Contains headers, cookies, content, and final timings. */
@Serializable
data class CompleteResponseMessage(
    val id: String,
    val response: ResponseBody,
    val timings: Timings,
    val time: Double,
    /**
     * The body was streamed as `BodyChunk` frames rather than carried here, so
     * this frame's body segment is empty — the bytes were assembled from the
     * chunks and cached before this arrived. The sizes mean the same either
     * way: `content.size` is the decoded length, `content.wireSize` the length
     * on the wire. See [org.bittrace.proxy.StreamedBodies].
     */
    @SerialName("_bodyStreamed") val bodyStreamed: Boolean = false,
) {
    @Serializable
    data class ResponseBody(
        val headers: List<NameValuePair> = emptyList(),
        val cookies: List<HarCookie> = emptyList(),
        /**
         * Bytes received for the body before decoding — the same figure as
         * `content.wireSize`, measured rather than read off a header the way
         * [InitialResponseData.ResponseHead.bodySize] is. `-1` on a flow that
         * predates the field. See [responseBodySizeOf].
         */
        val bodySize: Long = -1,
        val content: HarContent,
    )

    @Serializable
    data class Timings(val receive: Double)
}

// ---------------------------------------------------------------------------
// Body sizes  (the same figure arrives twice, and the two are not equal)
// ---------------------------------------------------------------------------

/**
 * Bytes sent for the request body, on the wire.
 *
 * The initial frame can only guess from `Content-Length`, which a chunked
 * upload does not send — it reports `0` there, for a request that may carry
 * megabytes. The completing frame measures the body it actually saw, so it
 * wins whenever it has arrived.
 *
 * Neither is [HarContent.size], which is the *decoded* length.
 */
fun requestBodySizeOf(head: InitialRequestData, complete: CompleteRequestMessage?): Long =
    complete?.request?.bodySize?.takeIf { it >= 0 } ?: head.request.bodySize

/**
 * Bytes received for the response body, on the wire, or null before any part of
 * the response has arrived.
 *
 * Same split as [requestBodySizeOf], and it matters more often: the initial
 * frame reads `Content-Length`, which is absent on every chunked and HTTP/2
 * response, so the size showed as unknown for most of the traffic on a modern
 * site until the measured figure landed.
 *
 * An errored flow never produces a completing frame at all; what it received
 * before failing is reported on the error frame's [HarContent] instead, which
 * is the last branch here.
 */
fun responseBodySizeOf(head: InitialResponseData?, complete: CompleteResponseMessage?): Long? =
    complete?.response?.bodySize?.takeIf { it >= 0 }
        ?: head?.response?.content?.wireSize?.takeIf { it >= 0 }
        ?: head?.response?.bodySize

// ---------------------------------------------------------------------------
// CONNECT  (tunnel setup — its own pair of frames)
// ---------------------------------------------------------------------------

/**
 * Emitted on `http_connect`, when a client asks the proxy to open a tunnel.
 *
 * mitmproxy answers CONNECT itself without raising the ordinary request and
 * response hooks, so a tunnel — and any failure to open one — would otherwise
 * be invisible. A CONNECT flow has its own [id], unrelated to the ids of the
 * requests that later travel inside it; [clientConnectionId] is the only link
 * back, and it also appears on [InitialRequestData].
 *
 * The payload is an ordinary HAR request head with two additions ([headers]
 * arrive here rather than on a later frame, since a CONNECT has no body to
 * wait for), so it converts into the pair of messages the store already merges
 * into a row — see [toInitialRequest] and [toCompleteRequest]. The matching
 * `ConnectResponse` frame carries exactly the [InitialResponseData] field set
 * and is decoded as one.
 */
@Serializable
data class ConnectRequestData(
    val id: String,
    val clientConnectionId: String = "",
    val startedDateTime: String,
    /** `host:port` of the client that asked for the tunnel. */
    val clientAddress: String = "",
    val request: ConnectHead,
    @SerialName("_tls") val tls: String = "",
) {
    @Serializable
    data class ConnectHead(
        val method: String,
        /** The authority (`host:port`) the tunnel is for — CONNECT has no path. */
        val url: String,
        val httpVersion: String,
        val headersSize: Long,
        val bodySize: Long,
        val queryString: List<NameValuePair> = emptyList(),
        val headers: List<NameValuePair> = emptyList(),
    )

    fun toInitialRequest(): InitialRequestData = InitialRequestData(
        id = id,
        startedDateTime = startedDateTime,
        request = InitialRequestData.RequestHead(
            method = request.method,
            url = request.url,
            httpVersion = request.httpVersion,
            headersSize = request.headersSize,
            bodySize = request.bodySize,
            queryString = request.queryString,
        ),
        tls = tls,
        clientConnectionId = clientConnectionId,
    )

    /**
     * The headers as the "complete" half of the flow. There is no second
     * request frame for a CONNECT, so without this the inspector would show a
     * tunnel as a row with no headers at all.
     */
    fun toCompleteRequest(): CompleteRequestMessage = CompleteRequestMessage(
        id = id,
        request = CompleteRequestMessage.RequestBody(
            headers = request.headers,
            // A CONNECT is a header and nothing else; the zero is known, not a
            // missing measurement.
            bodySize = 0,
        ),
    )
}
