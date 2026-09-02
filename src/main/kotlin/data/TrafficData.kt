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
