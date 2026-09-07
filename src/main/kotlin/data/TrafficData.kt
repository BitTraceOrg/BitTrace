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
    val size: Long,
    @Serializable(with = InternedStringSerializer::class) val mimeType: String,
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
        val bodySize: Long,
        val redirectURL: String,
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
     * the frame's body segment is empty and `content.size` is the *compressed*
     * wire length. See [org.bittrace.proxy.StreamedBodies].
     */
    @SerialName("_bodyStreamed") val bodyStreamed: Boolean = false,
) {
    @Serializable
    data class ResponseBody(
        val headers: List<NameValuePair> = emptyList(),
        val cookies: List<HarCookie> = emptyList(),
        val content: HarContent,
    )

    @Serializable
    data class Timings(val receive: Double)
}

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
        request = CompleteRequestMessage.RequestBody(headers = request.headers),
    )
}
