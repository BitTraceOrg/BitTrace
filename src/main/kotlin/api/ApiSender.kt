package org.bittrace.api

import org.bittrace.api.oauth.OAuthTokens
import org.bittrace.api.oauth.oauth1Header
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The header that ties a sent request to the flow the sidecar captures. */
const val MARKER_HEADER = "X-BitTrace-Rid"

/** What one send produced, whether it succeeded or failed in transport. */
class SendOutcome(
    val method: String,
    val url: String,
    val startedAt: Instant,
    val elapsedMs: Double,
    val status: Int = 0,
    val httpVersion: String = "HTTP/1.1",
    val requestHeaders: List<Pair<String, String>> = emptyList(),
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val requestBody: ByteArray = ByteArray(0),
    val responseBody: ByteArray = ByteArray(0),
    val failure: String? = null,
    /** How many redirects were followed to reach this response. */
    val redirects: Int = 0,
    /** Where the response actually came from, once redirects were followed. */
    val finalUrl: String = url,
)

/**
 * Sends authored requests through BitTrace's own proxy.
 *
 * Routing through the sidecar is the whole point: the request is captured like
 * any other flow, so it lands in the grid, and the response the Inspector shows
 * is the one mitmproxy decoded — already un-gzipped, with real wire timings —
 * rather than whatever this client happened to receive.
 */
class ApiSender {

    private class Key(val viaProxy: Boolean, val port: Int) {
        override fun equals(other: Any?) = other is Key && other.viaProxy == viaProxy && other.port == port
        override fun hashCode() = port * 31 + viaProxy.hashCode()
    }

    private var cached: Pair<Key, HttpClient>? = null

    /**
     * A client for this proxy setting, rebuilt only when it changes — each one
     * owns selector threads, so they are not made per send.
     */
    private fun client(viaProxy: Boolean, port: Int): HttpClient {
        val key = Key(viaProxy, port)
        cached?.let { (existing, client) -> if (existing == key) return client }

        val builder = HttpClient.newBuilder()
            // Redirects stay off: an API client should show what the endpoint
            // actually answered, not silently follow to somewhere else.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
        if (viaProxy) builder.proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port)))
        ProxyTrust.sslContext()?.let(builder::sslContext)

        return builder.build().also { cached = key to it }
    }

    /**
     * Runs [request], returning what happened either way — a transport failure
     * is an outcome to display, not an exception to propagate.
     *
     * Cancellation aborts the in-flight exchange: this bridges `sendAsync`
     * rather than blocking in `send`, because a blocked socket read ignores
     * coroutine cancellation and would leave the Cancel button lying.
     */
    suspend fun execute(
        raw: ApiRequest,
        settings: ResolvedSettings,
        marker: String?,
        viaProxy: Boolean,
        port: Int,
        /** Tokens this session has obtained; consulted for an OAuth 2.0 request. */
        tokens: OAuthTokens? = null,
        /** The project's variables, for `{{name}}`. Empty for a request in no project. */
        variables: Map<String, String> = emptyMap(),
    ): SendOutcome {
        // Substituted here and nowhere else on this path. This is the last point
        // at which the whole request is intact and unread, so one call covers the
        // URL, the headers, the cookies, the body and every send-time auth field
        // — and the caller keeps the `{{name}}` form, which is what gets saved
        // and what the history records.
        val request = raw.resolved(variables)
        val url = withAuthQuery(buildUrl(request), request.auth, settings.urlEncoding)
        // A file body is read once here so the cached copy, the size and the
        // publisher all agree; an outsized one streams straight from disk.
        val file = request.body.filePath.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val streamed = file != null && runCatching { Files.size(file) > IN_MEMORY_LIMIT }.getOrDefault(false)
        val body = when {
            file != null && !streamed -> runCatching { Files.readAllBytes(file) }.getOrDefault(ByteArray(0))
            file != null -> ByteArray(0)
            else -> request.body.payload().toByteArray(Charsets.UTF_8)
        }
        val started = Instant.now()
        val startedNanos = System.nanoTime()

        fun elapsed() = (System.nanoTime() - startedNanos) / 1_000_000.0

        val sent = mutableListOf<Pair<String, String>>()
        val http = try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(settings.timeoutMs))
                .method(
                    request.method.uppercase(),
                    when {
                        file != null && streamed -> HttpRequest.BodyPublishers.ofFile(file)
                        body.isEmpty() -> HttpRequest.BodyPublishers.noBody()
                        else -> HttpRequest.BodyPublishers.ofByteArray(body)
                    },
                )

            // Left unset for `auto`, which is not the same as picking one: the
            // JDK's own default negotiates, and naming HTTP/2 explicitly makes
            // it an upgrade the server has to accept.
            versionOf(settings.httpVersion)?.let(builder::version)

            // Says BitTrace rather than Java-http-client in the captured flow.
            builder.header("User-Agent", USER_AGENT)
            sent += "User-Agent" to USER_AGENT

            request.activeHeaders().forEach { header ->
                // Content-Length is computed by the body publisher; forwarding a
                // user's value would either be ignored or rejected outright.
                if (header.name.equals("content-length", ignoreCase = true)) return@forEach
                builder.header(header.name, header.value)
                sent += header.name to header.value
            }
            // `wireContentType`, not the stored one: a GraphQL body is stored as
            // GraphQL so the editor knows what it is, and sent as the JSON
            // envelope because that is what a server reads.
            val contentType = request.body.wireContentType()
            if (contentType.isNotBlank() && request.activeHeaders().none {
                    it.name.equals("content-type", ignoreCase = true)
                }
            ) {
                builder.header("Content-Type", contentType)
                sent += "Content-Type" to contentType
            }
            // Auth goes on after the table so a hand-typed header of the same
            // name wins: overriding the scheme for one send is a deliberate act,
            // and re-typing the whole value is how you do it.
            //
            // The two OAuth schemes cannot be computed by `ApiAuth.header()`,
            // and for opposite reasons. OAuth 1.0 signs *this* request, so it
            // needs the method and the final URL, which only exist here. OAuth
            // 2.0 attaches a token that was fetched earlier, which lives in a
            // store rather than in the request.
            authHeaderFor(request, url, tokens)?.let { (name, value) ->
                if (request.activeHeaders().none { it.name.equals(name, ignoreCase = true) }) {
                    builder.header(name, value)
                    sent += name to value
                }
            }
            // Cookies are one header; an explicit Cookie header wins, since
            // typing one is a deliberate override of the table.
            val cookies = request.activeCookies()
            if (cookies.isNotEmpty() && request.activeHeaders().none { it.name.equals("cookie", true) }) {
                val value = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                builder.header("Cookie", value)
                sent += "Cookie" to value
            }
            marker?.let { builder.header(MARKER_HEADER, it); sent += MARKER_HEADER to it }
            builder.build()
        } catch (e: Exception) {
            return SendOutcome(
                method = request.method, url = url, startedAt = started, elapsedMs = elapsed(),
                requestBody = body, requestHeaders = sent,
                failure = e.message ?: e::class.simpleName,
            )
        }

        return try {
            val client = client(viaProxy, port)
            var response = client.await(http)
            var hops = 0

            // Redirects are followed here rather than by the client, and not for
            // want of a switch: `HttpClient.Redirect` is a property of the
            // client, which is cached and shared across sends, and the JDK
            // offers no way to cap the hops at all. Following them by hand gives
            // both — and every hop goes through the proxy, so the grid shows the
            // chain rather than just its ends.
            if (settings.followRedirects) {
                var current = http
                while (hops < settings.maxRedirects) {
                    val next = redirectOf(current, response, settings.timeoutMs) ?: break
                    response = client.await(next)
                    current = next
                    hops++
                }
            }

            SendOutcome(
                method = request.method,
                url = url,
                startedAt = started,
                elapsedMs = elapsed(),
                status = response.statusCode(),
                httpVersion = if (response.version() == HttpClient.Version.HTTP_2) "HTTP/2" else "HTTP/1.1",
                requestHeaders = sent,
                responseHeaders = response.headers().map().flatMap { (k, v) -> v.map { k to it } },
                requestBody = body,
                responseBody = response.body() ?: ByteArray(0),
                redirects = hops,
                finalUrl = response.uri().toString(),
            )
        } catch (e: Exception) {
            SendOutcome(
                method = request.method, url = url, startedAt = started, elapsedMs = elapsed(),
                requestHeaders = sent, requestBody = body,
                failure = e.message ?: e::class.simpleName,
            )
        }
    }

    /**
     * The request that follows [response], or null when nothing does.
     *
     * The method rules are the ones browsers settled on rather than the ones
     * RFC 7231 wrote down: 303 always becomes a GET, and 301/302 do too for
     * anything that was not already GET or HEAD — because that is what every
     * server on the internet has been built against for twenty years, and a
     * client that re-POSTs to a 302 surprises people. 307 and 308 exist
     * precisely to say "keep the method and the body", so they do.
     *
     * The original body publisher is reused rather than rebuilt. The JDK's
     * `ofByteArray` and `ofFile` both start a fresh subscription per send, so a
     * 307 keeps the body it promised to keep instead of quietly sending none.
     */
    private fun redirectOf(
        previous: HttpRequest,
        response: HttpResponse<ByteArray>,
        timeoutMs: Long,
    ): HttpRequest? {
        if (response.statusCode() !in REDIRECT_CODES) return null
        val location = response.headers().firstValue("location").orElse(null)?.takeIf { it.isNotBlank() }
            ?: return null
        // Relative locations are the common case, and resolving against the URI
        // the response actually came from is what makes a chain of them work.
        val target = runCatching { response.uri().resolve(location) }.getOrNull() ?: return null

        val wasSafe = previous.method().equals("GET", true) || previous.method().equals("HEAD", true)
        val becomesGet = response.statusCode() == 303 ||
            (response.statusCode() in setOf(301, 302) && !wasSafe)

        val builder = HttpRequest.newBuilder().uri(target).timeout(Duration.ofMillis(timeoutMs))
        previous.version().ifPresent(builder::version)

        val crossOrigin = !sameOrigin(previous.uri(), target)
        previous.headers().map().forEach { (name, values) ->
            val lower = name.lowercase()
            // Credentials do not travel across origins. A redirect to another
            // host is exactly how an Authorization header leaks somewhere it was
            // never meant for.
            if (crossOrigin && lower in ORIGIN_BOUND_HEADERS) return@forEach
            // The correlation marker rides only on the request the user
            // authored. Carrying it onto every hop would leave several captured
            // flows claiming to be the same send, and the poll takes the newest
            // — so the pane would show a redirect the user never wrote.
            if (lower == MARKER_HEADER.lowercase()) return@forEach
            // Length belongs to whatever body this new request carries.
            if (lower == "content-length") return@forEach
            values.forEach { builder.header(name, it) }
        }

        return if (becomesGet) {
            builder.method("GET", HttpRequest.BodyPublishers.noBody()).build()
        } else {
            val publisher = previous.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())
            builder.method(previous.method(), publisher).build()
        }
    }

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port

    private suspend fun HttpClient.await(request: HttpRequest): HttpResponse<ByteArray> =
        suspendCancellableCoroutine { continuation ->
            val future = sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (error != null) continuation.resumeWithException(error) else continuation.resume(response)
            }
        }

    companion object {
        private const val USER_AGENT = "BitTrace/1.0"

        /** The statuses that name somewhere else to go. */
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Headers that belong to the origin they were sent to, and no other. */
        private val ORIGIN_BOUND_HEADERS = setOf("authorization", "cookie")

        private fun versionOf(setting: String): HttpClient.Version? = when (setting) {
            HTTP_1_1 -> HttpClient.Version.HTTP_1_1
            HTTP_2 -> HttpClient.Version.HTTP_2
            else -> null
        }

        /** Above this a file body streams from disk instead of being buffered. */
        private const val IN_MEMORY_LIMIT = 2L * 1024 * 1024

        init {
            // Host and Connection are "restricted" and rejected outright unless
            // this is set before the first HttpClient loads. Content-Length is
            // deliberately left restricted — the body publisher owns it.
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host,connection,upgrade,expect")
        }
    }
}

/**
 * The `Authorization` header for [request], whichever scheme it uses.
 *
 * Signing and token-attaching both need something `ApiAuth` does not have — the
 * request being sent, and the tokens this session holds — so the schemes that
 * need neither stay on `ApiAuth.header()` and these two live here.
 *
 * An OAuth 2.0 request with no token sends **no header at all** rather than an
 * empty one. `Bearer ` comes back as a generic 401 that reads like a server
 * problem; a missing header comes back as a 401 that reads like what it is.
 */
private fun authHeaderFor(request: ApiRequest, url: String, tokens: OAuthTokens?): Pair<String, String>? =
    when (request.auth.type) {
        AUTH_OAUTH1 -> oauth1Header(
            method = request.method,
            url = url,
            auth = request.auth,
            // Only a form body is signed: RFC 5849 §3.4.1.3.1 folds its
            // parameters into the signature, and any other body is bytes the
            // server cannot reconstruct as parameters.
            formParams = formParamsOf(request),
        ).map { "Authorization" to it }.getOrNull()

        AUTH_OAUTH2 -> tokens?.of(request.auth)?.header()

        else -> request.auth.header()
    }

/** A form body's parameters, or nothing when the body is not a form. */
private fun formParamsOf(request: ApiRequest): List<Pair<String, String>> {
    if (!request.body.contentType.contains("x-www-form-urlencoded", ignoreCase = true)) return emptyList()
    return request.body.text
        .split('&')
        .filter { it.isNotBlank() }
        .map { pair ->
            val name = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            decodeParam(name) to decodeParam(value)
        }
}

/**
 * The URL to send.
 *
 * The URL field is authoritative: the params table and the query string are
 * kept in step as they are edited, so by send time the URL already carries
 * exactly the enabled params. Appending them again here would double the query.
 */
fun buildUrl(request: ApiRequest): String = request.url.trim().substringBefore('#')

/**
 * [url] with an API-key query parameter appended, when the auth sends one.
 *
 * Appended here rather than merged into the params table, because the table is
 * the URL as authored: putting the key in it would write the credential into
 * the address field and then into the request's saved `url` on disk.
 */
fun withAuthQuery(url: String, auth: ApiAuth, encoding: String = ENCODING_WHATWG): String {
    val (name, value) = auth.queryParam() ?: return url
    val fragment = url.substringAfter('#', "")
    val base = url.substringBefore('#')
    val pair = "${encodeQuery(name, encoding)}=${encodeQuery(value, encoding)}"
    return buildString {
        append(base)
        append(if ('?' in base) '&' else '?')
        append(pair)
        if (fragment.isNotEmpty()) append('#').append(fragment)
    }
}

/** The query string of [url], as table rows. Disabled rows cannot survive a URL. */
fun paramsOf(url: String): List<KeyValue> {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return emptyList()
    return query.split('&').filter { it.isNotBlank() }.map { pair ->
        KeyValue(
            name = decodeParam(pair.substringBefore('=')),
            value = if ('=' in pair) decodeParam(pair.substringAfter('=')) else "",
        )
    }
}

/**
 * [url] with its query replaced by the enabled [params].
 *
 * Disabled rows simply do not appear, which is how switching one off removes it
 * from the request without losing the row.
 */
fun urlWithParams(url: String, params: List<KeyValue>, encoding: String = ENCODING_WHATWG): String {
    val fragment = url.substringAfter('#', "")
    val base = url.substringBefore('#').substringBefore('?')
    val active = params.filter { it.enabled && it.name.isNotBlank() }
    val query = active.joinToString("&") {
        "${encodeQuery(it.name, encoding)}=${encodeQuery(it.value, encoding)}"
    }
    return buildString {
        append(base)
        if (query.isNotEmpty()) append('?').append(query)
        if (fragment.isNotEmpty()) append('#').append(fragment)
    }
}

/** Percent-decoding that leaves a malformed escape alone rather than throwing. */
private fun decodeParam(text: String): String =
    runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)
