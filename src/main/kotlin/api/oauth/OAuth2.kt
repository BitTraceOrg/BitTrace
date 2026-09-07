package org.bittrace.api.oauth

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import org.bittrace.api.ApiAuth
import org.bittrace.api.GRANT_AUTH_CODE
import org.bittrace.api.GRANT_CLIENT_CREDENTIALS
import org.bittrace.api.GRANT_DEVICE_CODE
import org.bittrace.api.GRANT_JWT_BEARER
import org.bittrace.api.GRANT_PASSWORD
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * OAuth 2.0: building the requests and reading the answers.
 *
 * Everything here is pure — no sockets, no browser, no clock beyond what is
 * passed in — so the parts that are easy to get subtly wrong (the PKCE
 * challenge, the form bodies, the error shape) are testable without a provider.
 * The I/O lives in `OAuthService`, and the interactive half in `LoopbackServer`.
 */

/** A token as the provider described it. */
class OAuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val refreshToken: String = "",
    /** When it stops working, or null when the provider did not say. */
    val expiresAt: Instant? = null,
    val scope: String = "",
) {
    /**
     * Whether it is worth refreshing now.
     *
     * A minute of slack, because a token that expires while the request is in
     * flight fails exactly like a wrong one and is far more confusing.
     */
    fun expired(now: Instant = Instant.now()): Boolean =
        expiresAt != null && !now.plusSeconds(EXPIRY_SLACK_SECONDS).isBefore(expiresAt)

    /** The header this token becomes. */
    fun header(): Pair<String, String> =
        "Authorization" to "${tokenType.ifBlank { "Bearer" }} $accessToken"
}

/** What a device-code authorisation is waiting on. */
class DeviceCodeGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String = "",
    val intervalSeconds: Long = 5,
    val expiresAt: Instant? = null,
)

/** A provider's `error` / `error_description` pair, which is the useful part of a failure. */
class OAuthError(val code: String, val description: String) {
    override fun toString(): String = if (description.isBlank()) code else "$code — $description"
}

// --- PKCE -------------------------------------------------------------------

/**
 * A fresh PKCE code verifier (RFC 7636 §4.1).
 *
 * Base64url of 32 random bytes: 43 characters, all from the unreserved set, at
 * the top of the entropy the spec asks for.
 */
fun pkceVerifier(): String {
    val bytes = ByteArray(VERIFIER_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The `S256` challenge for a verifier (RFC 7636 §4.2).
 *
 * Base64url of the SHA-256 of the verifier's **ASCII bytes** — not of its
 * decoded value, which is the misreading that produces a challenge the provider
 * rejects with a message that says nothing about why.
 */
fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

// --- Requests ---------------------------------------------------------------

/** The loopback URL the provider must be told to redirect to. */
fun redirectUri(auth: ApiAuth): String =
    "http://127.0.0.1:${auth.redirectPort}${auth.redirectPath.ifBlank { "/" }}"

/**
 * The URL to open in the browser for the authorization-code grant.
 *
 * `127.0.0.1` rather than `localhost` deliberately: RFC 8252 §8.3 prefers the
 * literal address, because `localhost` can resolve through a name service and
 * does not always mean the machine you are on.
 */
fun authorizationUrl(auth: ApiAuth, state: String, challenge: String?): String {
    val params = buildList {
        add("response_type" to "code")
        add("client_id" to auth.clientId)
        add("redirect_uri" to redirectUri(auth))
        add("state" to state)
        if (auth.scope.isNotBlank()) add("scope" to auth.scope)
        if (auth.audience.isNotBlank()) add("audience" to auth.audience)
        if (challenge != null) {
            add("code_challenge" to challenge)
            add("code_challenge_method" to "S256")
        }
    }
    val separator = if ('?' in auth.authUrl) '&' else '?'
    return auth.authUrl + separator + params.joinToString("&") { (k, v) -> "${form(k)}=${form(v)}" }
}

/**
 * The form body for a token request.
 *
 * [code] is the authorization code, [deviceCode] the device code, and
 * [refreshToken] a refresh — exactly one of them applies per grant, which is
 * why they are separate parameters rather than one `extra` map that every
 * caller would have to fill correctly.
 */
fun tokenRequestBody(
    auth: ApiAuth,
    code: String? = null,
    verifier: String? = null,
    deviceCode: String? = null,
    refreshToken: String? = null,
    /** The signed JWT, for the JWT Bearer grant. Built by the caller: it has a clock in it. */
    assertion: String? = null,
): String {
    val params = buildList {
        when {
            refreshToken != null -> {
                add("grant_type" to "refresh_token")
                add("refresh_token" to refreshToken)
            }

            auth.grantType == GRANT_AUTH_CODE -> {
                add("grant_type" to GRANT_AUTH_CODE)
                add("code" to code.orEmpty())
                add("redirect_uri" to redirectUri(auth))
                verifier?.let { add("code_verifier" to it) }
            }

            auth.grantType == GRANT_CLIENT_CREDENTIALS -> add("grant_type" to GRANT_CLIENT_CREDENTIALS)

            auth.grantType == GRANT_PASSWORD -> {
                add("grant_type" to GRANT_PASSWORD)
                add("username" to auth.username)
                add("password" to auth.password)
            }

            auth.grantType == GRANT_DEVICE_CODE -> {
                add("grant_type" to GRANT_DEVICE_CODE)
                add("device_code" to deviceCode.orEmpty())
            }

            auth.grantType == GRANT_JWT_BEARER -> {
                add("grant_type" to GRANT_JWT_BEARER)
                add("assertion" to assertion.orEmpty())
            }
        }
        // Scope belongs on a refresh only when narrowing, and providers differ;
        // it is sent on the grants where it is unambiguous.
        if (auth.scope.isNotBlank() && refreshToken == null) add("scope" to auth.scope)
        if (auth.audience.isNotBlank()) add("audience" to auth.audience)
        // Credentials go in the body only when asked for. The default is the
        // Basic header, which RFC 6749 §2.3.1 says clients SHOULD prefer.
        if (auth.credentialsInBody) {
            add("client_id" to auth.clientId)
            if (auth.clientSecret.isNotBlank()) add("client_secret" to auth.clientSecret)
        }
    }
    return params.joinToString("&") { (k, v) -> "${form(k)}=${form(v)}" }
}

/** The device-authorization request body, which asks only for scope. */
fun deviceCodeRequestBody(auth: ApiAuth): String {
    val params = buildList {
        add("client_id" to auth.clientId)
        if (auth.scope.isNotBlank()) add("scope" to auth.scope)
    }
    return params.joinToString("&") { (k, v) -> "${form(k)}=${form(v)}" }
}

// --- Responses --------------------------------------------------------------

/**
 * A token response, or the error the provider returned instead.
 *
 * Both shapes come back as 200s from some providers and 400s from others, so
 * the body decides rather than the status: an `error` field means a failure
 * whatever the code said.
 */
fun parseTokenResponse(json: String): Result<OAuthToken> = runCatching {
    val fields = readObject(json)
    fields["error"]?.let { throw OAuthException(OAuthError(it, fields["error_description"].orEmpty())) }
    val access = fields["access_token"] ?: throw OAuthException(
        OAuthError("invalid_response", "the response carried no access_token"),
    )
    OAuthToken(
        accessToken = access,
        tokenType = fields["token_type"] ?: "Bearer",
        refreshToken = fields["refresh_token"].orEmpty(),
        expiresAt = fields["expires_in"]?.toLongOrNull()?.let { Instant.now().plusSeconds(it) },
        scope = fields["scope"].orEmpty(),
    )
}

/** A device-authorization response, or the error instead. */
fun parseDeviceCodeResponse(json: String): Result<DeviceCodeGrant> = runCatching {
    val fields = readObject(json)
    fields["error"]?.let { throw OAuthException(OAuthError(it, fields["error_description"].orEmpty())) }
    DeviceCodeGrant(
        deviceCode = fields["device_code"].orEmpty(),
        userCode = fields["user_code"].orEmpty(),
        verificationUri = fields["verification_uri"] ?: fields["verification_url"].orEmpty(),
        verificationUriComplete = fields["verification_uri_complete"].orEmpty(),
        intervalSeconds = fields["interval"]?.toLongOrNull() ?: DEFAULT_POLL_SECONDS,
        expiresAt = fields["expires_in"]?.toLongOrNull()?.let { Instant.now().plusSeconds(it) },
    )
}

/** The error in a response, for the device poll, which reads them as status. */
fun errorIn(json: String): String? = runCatching { readObject(json)["error"] }.getOrNull()

/** Carries an [OAuthError] so a failure keeps the provider's own words. */
class OAuthException(val error: OAuthError) : Exception(error.toString())

/**
 * The top level of a JSON object, flattened to strings.
 *
 * Only the top level, and only scalars: every field OAuth defines is one, and
 * reading with the streaming parser avoids a databind dependency the app has
 * deliberately never taken.
 */
private fun readObject(json: String): Map<String, String> = buildMap {
    JsonFactory().createParser(json).use { parser ->
        if (parser.nextToken() != JsonToken.START_OBJECT) return@buildMap
        var depth = 1
        while (depth > 0) {
            val token = parser.nextToken() ?: break
            when (token) {
                JsonToken.START_OBJECT, JsonToken.START_ARRAY -> depth++
                JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                JsonToken.FIELD_NAME if depth == 1 -> {
                    val name = parser.currentName()
                    val value = parser.nextToken()
                    when {
                        value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY -> {
                            parser.skipChildren()
                        }

                        value != null && value != JsonToken.VALUE_NULL -> put(name, parser.text)
                        else -> Unit
                    }
                }

                JsonToken.NOT_AVAILABLE -> TODO()
                JsonToken.FIELD_NAME -> TODO()
                JsonToken.VALUE_EMBEDDED_OBJECT -> TODO()
                JsonToken.VALUE_STRING -> TODO()
                JsonToken.VALUE_NUMBER_INT -> TODO()
                JsonToken.VALUE_NUMBER_FLOAT -> TODO()
                JsonToken.VALUE_TRUE -> TODO()
                JsonToken.VALUE_FALSE -> TODO()
                JsonToken.VALUE_NULL -> TODO()
            }
        }
    }
}

/** `application/x-www-form-urlencoded`, which is what a token endpoint reads. */
private fun form(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

private const val VERIFIER_BYTES = 32

private const val EXPIRY_SLACK_SECONDS = 60L

private const val DEFAULT_POLL_SECONDS = 5L
