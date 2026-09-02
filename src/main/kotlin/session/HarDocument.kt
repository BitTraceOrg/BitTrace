package org.bittrace.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HAR 1.2 mirror types, used only for **reading**.
 *
 * These exist rather than decoding straight into the wire model because the
 * wire model describes what the sidecar always sends, not what a HAR may
 * contain: `InitialRequestData.tls`, `InitialResponseData.serverIPAddress` and
 * every member of `HarTimings` are non-null with no default, so any HAR written
 * by another tool would fail to decode. Everything here defaults, so a sparse
 * document from Chrome, Firefox, Charles or Fiddler still loads.
 *
 * The write side does not use these — the exporter streams fields straight to a
 * JsonGenerator, which avoids materialising an object (and its base64 body) per
 * entry.
 */

@Serializable
class HarFile(val log: HarLog = HarLog())

@Serializable
class HarLog(
    val version: String = "1.2",
    val creator: HarCreator = HarCreator(),
    val entries: List<HarEntry> = emptyList(),
)

@Serializable
class HarCreator(val name: String = "", val version: String = "")

@Serializable
class HarEntry(
    val startedDateTime: String = "",
    val time: Double = -1.0,
    val request: HarRequestJson = HarRequestJson(),
    val response: HarResponseJson = HarResponseJson(),
    val timings: HarTimingsJson = HarTimingsJson(),
    val serverIPAddress: String = "",
    val connection: String = "",
    /** BitTrace's own extension, present when the file came from this app. */
    @SerialName("_tls") val tls: String = "",
)

@Serializable
class HarRequestJson(
    val method: String = "",
    val url: String = "",
    val httpVersion: String = "HTTP/1.1",
    val headers: List<HarPair> = emptyList(),
    val cookies: List<HarPair> = emptyList(),
    val queryString: List<HarPair> = emptyList(),
    val postData: HarPostData? = null,
    val headersSize: Long = -1,
    val bodySize: Long = -1,
)

@Serializable
class HarResponseJson(
    val status: Int = 0,
    val statusText: String = "",
    val httpVersion: String = "HTTP/1.1",
    val headers: List<HarPair> = emptyList(),
    val cookies: List<HarCookieJson> = emptyList(),
    val content: HarContentJson = HarContentJson(),
    val redirectURL: String = "",
    val headersSize: Long = -1,
    val bodySize: Long = -1,
)

@Serializable
class HarPostData(
    val mimeType: String = "",
    val text: String? = null,
    /** BitTrace's extension for a body that is not valid UTF-8. */
    @SerialName("_bodyBase64") val bodyBase64: String? = null,
)

@Serializable
class HarContentJson(
    val size: Long = -1,
    val mimeType: String = "",
    val text: String? = null,
    /** "base64", or absent/anything else for literal text. */
    val encoding: String? = null,
)

/**
 * Decoded as a plain pair rather than reusing the wire model's `NameValuePair`,
 * whose custom serializer expects exactly two fields; HAR pairs may also carry
 * `comment`. Interning happens when these are mapped into the wire model.
 */
@Serializable
class HarPair(val name: String = "", val value: String = "")

@Serializable
class HarCookieJson(
    val name: String = "",
    val value: String = "",
    val path: String? = null,
    val domain: String? = null,
    val expires: String? = null,
    val httpOnly: Boolean? = null,
    val secure: Boolean? = null,
)

/** `-1.0` is HAR's sentinel for a phase that does not apply. */
@Serializable
class HarTimingsJson(
    val blocked: Double = -1.0,
    val dns: Double = -1.0,
    val connect: Double = -1.0,
    val send: Double = -1.0,
    val wait: Double = -1.0,
    val receive: Double = -1.0,
    val ssl: Double = -1.0,
)
