package org.bittrace.api

import kotlinx.serialization.Serializable
import org.bittrace.data.Settings
import java.net.URLEncoder

/**
 * What one request overrides about how it is sent.
 *
 * Every field is nullable, and that *is* the inheritance: null means "whatever
 * the app default says", so changing a default moves every request that never
 * had an opinion. The alternative — seeding a request with the defaults when it
 * is created — turns a default into a template, where changing it does nothing
 * to anything that already exists.
 *
 * The enum-ish fields are `String` for the same reason [ApiRequest.method] and
 * [ApiAuth.type] are: a request written by a later build may name an HTTP
 * version or an encoding this one has never heard of, and it has to load — and
 * fall back — rather than fail to parse the whole file.
 *
 * @property timeoutMs deadline for the whole exchange. The *connect* timeout is
 *   not here: it belongs to the shared `HttpClient`, and making it per-request
 *   would mean a client, and a set of selector threads, per distinct value.
 */
@Serializable
data class RequestSettings(
    val timeoutMs: Long? = null,
    val httpVersion: String? = null,
    val followRedirects: Boolean? = null,
    val maxRedirects: Int? = null,
    val urlEncoding: String? = null,
) {
    /** Whether anything here is set, which is what the tab's reset button needs. */
    val isDefault: Boolean
        get() = timeoutMs == null && httpVersion == null && followRedirects == null &&
            maxRedirects == null && urlEncoding == null
}

/**
 * A request's settings with every gap filled from the defaults.
 *
 * Exists so nothing downstream has to remember which fields might be null. The
 * sender takes one of these and reads plain values.
 */
class ResolvedSettings(
    val timeoutMs: Long,
    val httpVersion: String,
    val followRedirects: Boolean,
    val maxRedirects: Int,
    val urlEncoding: String,
)

/** The one place a request's overrides and the app's defaults are merged. */
fun RequestSettings.resolve(defaults: Settings): ResolvedSettings = ResolvedSettings(
    // Coerced, not trusted: these come off a text field and out of a settings
    // file, and a zero timeout would mean "fail immediately" rather than
    // "unset".
    timeoutMs = (timeoutMs ?: defaults.apiTimeoutMs).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
    httpVersion = httpVersion ?: defaults.apiHttpVersion,
    followRedirects = followRedirects ?: defaults.apiFollowRedirects,
    maxRedirects = (maxRedirects ?: defaults.apiMaxRedirects).coerceIn(0, MAX_REDIRECT_CAP),
    urlEncoding = urlEncoding ?: defaults.apiUrlEncoding,
)

// --- HTTP version ----------------------------------------------------------

/** Let the JDK negotiate, which is what the app did before this was settable. */
const val HTTP_AUTO = "auto"
const val HTTP_1_1 = "http/1.1"
const val HTTP_2 = "http/2"

val HTTP_VERSIONS = listOf(HTTP_AUTO, HTTP_1_1, HTTP_2)

fun httpVersionLabel(value: String): String = when (value) {
    HTTP_AUTO -> "Auto"
    HTTP_1_1 -> "HTTP/1.1"
    HTTP_2 -> "HTTP/2"
    else -> value
}

// --- URL encoding ----------------------------------------------------------

/**
 * `application/x-www-form-urlencoded`, as browsers and `URLSearchParams` do it:
 * space becomes `+`, and `*-._` survive. This is what the app has always sent,
 * which is why it is the default — turning it on changes nothing.
 */
const val ENCODING_WHATWG = "whatwg"

/** Percent-encoding proper: only `A-Za-z0-9-._~` survive, and space is `%20`. */
const val ENCODING_RFC3986 = "rfc3986"

/** Verbatim. For an endpoint that wants a character this app would escape. */
const val ENCODING_NONE = "none"

val URL_ENCODINGS = listOf(ENCODING_WHATWG, ENCODING_RFC3986, ENCODING_NONE)

fun urlEncodingLabel(value: String): String = when (value) {
    ENCODING_WHATWG -> "WHATWG"
    ENCODING_RFC3986 -> "RFC 3986"
    ENCODING_NONE -> "None"
    else -> value
}

/**
 * Encodes one query name or value under [mode].
 *
 * RFC 3986 is derived from the form encoder rather than written from scratch,
 * because the two differ in exactly four places and deriving keeps the
 * multi-byte UTF-8 handling — the part that is easy to get wrong — in the JDK
 * where it already works:
 *
 * - `+` back to `%20`, since a literal plus in a form-encoded value means space
 * - `*` to `%2A`; the form encoder leaves it bare, RFC 3986 does not
 * - `%7E` back to `~`, which is unreserved and should not have been escaped
 *
 * An unknown mode encodes rather than passes through: a request naming an
 * encoding this build does not know should still be *sendable*, and escaping is
 * the answer that cannot corrupt a URL.
 */
fun encodeQuery(text: String, mode: String): String = when (mode) {
    ENCODING_NONE -> text
    ENCODING_RFC3986 -> URLEncoder.encode(text, Charsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

    else -> URLEncoder.encode(text, Charsets.UTF_8)
}

/** A timeout below this is a mistake, not a choice. */
private const val MIN_TIMEOUT_MS = 100L

/** Ten minutes. Past this the Cancel button is the tool you want. */
private const val MAX_TIMEOUT_MS = 600_000L

/** Enough for any real chain; more is a loop you want reported, not followed. */
private const val MAX_REDIRECT_CAP = 20
