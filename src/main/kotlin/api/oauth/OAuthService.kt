package org.bittrace.api.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.bittrace.api.ApiAuth
import org.bittrace.api.GRANT_AUTH_CODE
import org.bittrace.api.GRANT_DEVICE_CODE
import org.bittrace.api.GRANT_JWT_BEARER
import org.bittrace.api.ProxyTrust
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Getting a token: the part with sockets, a browser and a clock.
 *
 * Token requests go through the app's own proxy, like every other send, so the
 * exchange lands in the traffic grid. That is this app's whole thesis applied to
 * itself — when a token request fails, the useful thing is not a message box but
 * the request and the response, side by side, in the tool you already have open.
 */
class OAuthService(
    private val tokens: OAuthTokens,
    private val proxyPort: () -> Int,
    private val viaProxy: () -> Boolean,
) {

    /**
     * Runs whichever grant [auth] names, and stores what comes back.
     *
     * [onStatus] carries progress to the tab: "waiting for the browser", the
     * device code to type, the error a provider gave. Everything a user needs
     * while an authorisation is in flight arrives through it.
     */
    suspend fun authorize(auth: ApiAuth, onStatus: (String) -> Unit): Result<OAuthToken> = catching {
        val token = when (auth.grantType) {
            GRANT_AUTH_CODE -> authorizationCode(auth, onStatus)
            GRANT_DEVICE_CODE -> deviceCode(auth, onStatus)

            // The assertion is built here rather than in `tokenRequestBody`,
            // which is pure: a JWT carries `iat` and `exp`, so it needs a clock.
            GRANT_JWT_BEARER -> {
                onStatus("Signing the assertion…")
                val assertion = jwtAssertion(auth).getOrThrow()
                exchange(auth, tokenRequestBody(auth, assertion = assertion))
            }

            // Client credentials and password need nothing but the exchange.
            else -> exchange(auth, tokenRequestBody(auth))
        }
        tokens.put(auth, token)
        token
    }

    /** Trades the refresh token for a new access token, keeping the old refresh if none comes back. */
    suspend fun refresh(auth: ApiAuth): Result<OAuthToken> = catching {
        val existing = tokens.of(auth)
        val refreshToken = existing?.refreshToken.orEmpty()
        require(refreshToken.isNotBlank()) { "There is no refresh token to use." }

        val fresh = exchange(auth, tokenRequestBody(auth, refreshToken = refreshToken))
        // Providers may omit the refresh token on a refresh, which means "keep
        // the one you have" rather than "you no longer have one".
        val merged = if (fresh.refreshToken.isBlank()) {
            OAuthToken(fresh.accessToken, fresh.tokenType, refreshToken, fresh.expiresAt, fresh.scope)
        } else {
            fresh
        }
        tokens.put(auth, merged)
        merged
    }

    /**
     * The browser-based grant: open, wait, exchange.
     *
     * The listener starts *before* the browser opens. A provider that redirects
     * instantly — a session already signed in, a consent already granted — would
     * otherwise arrive at a port with nothing listening, and the whole flow
     * would fail on the fast path rather than the slow one.
     */
    private suspend fun authorizationCode(auth: ApiAuth, onStatus: (String) -> Unit): OAuthToken = coroutineScope {
        val state = pkceVerifier()
        val verifier = if (auth.usePkce) pkceVerifier() else null
        val challenge = verifier?.let(::pkceChallenge)

        val redirect = async { awaitRedirect(auth.redirectPort, REDIRECT_TIMEOUT_SECONDS) }

        val url = authorizationUrl(auth, state, challenge)
        onStatus("Waiting for the browser…")
        openBrowser(url).onFailure {
            onStatus("Could not open a browser. Open this yourself:\n$url")
        }

        val result = redirect.await().getOrElse {
            error("No redirect arrived on port ${auth.redirectPort} — ${it.message ?: "timed out"}")
        }

        result.error?.let { error("Authorisation refused: $it${result.errorDescription?.let { d -> " — $d" }.orEmpty()}") }
        // A returned state that is not the one we sent means this redirect
        // belongs to somebody else's authorisation, and the code with it is not
        // ours to exchange.
        require(result.state == state) { "The redirect's state did not match. Authorisation refused." }
        val code = result.code ?: error("The redirect carried no authorization code.")

        onStatus("Exchanging the code…")
        exchange(auth, tokenRequestBody(auth, code = code, verifier = verifier))
    }

    /** The device grant: show a code, then poll until somebody types it. */
    private suspend fun deviceCode(auth: ApiAuth, onStatus: (String) -> Unit): OAuthToken {
        require(auth.deviceAuthUrl.isNotBlank()) { "The device grant needs a device authorization URL." }
        val started = post(auth.deviceAuthUrl, deviceCodeRequestBody(auth), auth)
        val grant = parseDeviceCodeResponse(started).getOrThrow()

        onStatus("Go to ${grant.verificationUri} and enter ${grant.userCode}")
        grant.verificationUriComplete.takeIf { it.isNotBlank() }?.let { openBrowser(it) }

        var wait = grant.intervalSeconds
        while (true) {
            delay(wait * 1000)
            if (grant.expiresAt?.isBefore(java.time.Instant.now()) == true) {
                error("That device code expired before it was entered.")
            }
            val body = post(auth.tokenUrl, tokenRequestBody(auth, deviceCode = grant.deviceCode), auth)
            when (errorIn(body)) {
                // The two the spec defines as "keep waiting" — the second also
                // asks to be asked less often.
                "authorization_pending" -> Unit
                "slow_down" -> wait += SLOW_DOWN_SECONDS
                null -> return parseTokenResponse(body).getOrThrow()
                else -> return parseTokenResponse(body).getOrThrow()
            }
        }
    }

    /** One token-endpoint round trip. */
    private suspend fun exchange(auth: ApiAuth, body: String): OAuthToken {
        require(auth.tokenUrl.isNotBlank()) { "No token URL is set." }
        return parseTokenResponse(post(auth.tokenUrl, body, auth)).getOrThrow()
    }

    private suspend fun post(url: String, body: String, auth: ApiAuth): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(EXCHANGE_TIMEOUT_SECONDS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))

        // RFC 6749 §2.3.1: the client SHOULD authenticate with HTTP Basic, and
        // only put its credentials in the body when the server insists.
        if (!auth.credentialsInBody && auth.clientId.isNotBlank()) {
            val credentials = "${auth.clientId}:${auth.clientSecret}"
            builder.header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8)),
            )
        }

        client().send(builder.build(), HttpResponse.BodyHandlers.ofString()).body().orEmpty()
    }

    /**
     * A client for the exchange, routed through the proxy when it is running.
     *
     * Built per call rather than cached: an exchange happens when somebody
     * presses a button, so the cost is invisible, and a cached one would have to
     * notice the proxy starting or stopping underneath it.
     */
    private fun client(): HttpClient {
        val builder = HttpClient.newBuilder()
            // The provider's own redirects are its business, and following them
            // is how a token endpoint's error page gets mistaken for an answer.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        if (viaProxy()) builder.proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxyPort())))
        ProxyTrust.sslContext()?.let(builder::sslContext)
        return builder.build()
    }

    /**
     * Opens the system browser, off the UI thread.
     *
     * `Desktop.browse` blocks while the platform starts a browser, which on a
     * cold start is a visible fraction of a second and occasionally much worse.
     * It ran on whichever thread called it, and the caller is the UI — so this
     * is the difference between a click and a frozen window.
     */
    /**
     * `runCatching`, minus the one exception it must never catch.
     *
     * `runCatching` swallows `CancellationException`, which turns Stop into a
     * failed authorisation: the job completes normally, its completion handler
     * sees no cause, and the tab reports an error for something the user asked
     * for. Catching it also breaks structured concurrency, since a cancelled
     * scope is supposed to keep unwinding.
     */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun openBrowser(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                "No browser available."
            }
            Desktop.getDesktop().browse(URI.create(url))
        }
    }

    private companion object {
        /** Long enough to sign in and consent, short enough to free the port. */
        const val REDIRECT_TIMEOUT_SECONDS = 300L
        const val EXCHANGE_TIMEOUT_SECONDS = 30L
        const val CONNECT_TIMEOUT_SECONDS = 15L

        /** What `slow_down` adds to the poll interval, per RFC 8628 §3.5. */
        const val SLOW_DOWN_SECONDS = 5L
    }
}
