package org.bittrace.api.oauth

import org.bittrace.api.ApiAuth
import org.bittrace.api.JWT_HS256
import org.bittrace.api.JWT_RS256
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The signed assertion for the JWT Bearer grant (RFC 7523).
 *
 * A grant with no user in it and no browser: the client proves who it is by
 * signing a short-lived JWT with a key the provider already trusts, and trades
 * that for an access token. It is what a Google service account does, and what
 * anything server-to-server with a key pair rather than a shared secret does.
 *
 * Everything here is pure but for reading the key file, so the format — which is
 * exacting, and fails as an opaque 400 when it is wrong — is testable without a
 * provider.
 */

/**
 * A JWT for [auth], valid from [issuedAt].
 *
 * The claim set is RFC 7523 §3's required one: issuer, subject, audience,
 * expiry and issued-at. Providers differ on what `sub` means — Google wants the
 * service account, an impersonating client wants the user — so it is a field
 * rather than a copy of `iss`, even though the two are equal as often as not.
 */
fun jwtAssertion(auth: ApiAuth, issuedAt: Instant = Instant.now()): Result<String> = runCatching {
    require(auth.jwtIssuer.isNotBlank()) { "A JWT assertion needs an issuer." }
    val audience = auth.jwtAudience.ifBlank { auth.tokenUrl }
    require(audience.isNotBlank()) { "A JWT assertion needs an audience — usually the token URL." }

    val header = buildMap {
        put("alg", auth.jwtAlgorithm)
        put("typ", "JWT")
        // Providers that rotate keys need to be told which one signed this, and
        // reject the assertion outright without it.
        if (auth.jwtKeyId.isNotBlank()) put("kid", auth.jwtKeyId)
    }
    val expiry = issuedAt.plusSeconds(auth.jwtValiditySeconds.coerceIn(MIN_VALIDITY, MAX_VALIDITY))
    val claims = buildMap {
        put("iss", auth.jwtIssuer)
        put("sub", auth.jwtSubject.ifBlank { auth.jwtIssuer })
        put("aud", audience)
        put("iat", issuedAt.epochSecond.toString())
        put("exp", expiry.epochSecond.toString())
        if (auth.scope.isNotBlank()) put("scope", auth.scope)
    }
    // `iat` and `exp` are numbers in JSON, not strings — a provider reading them
    // as strings rejects the assertion, and the message says nothing useful.
    val numeric = setOf("iat", "exp")

    val input = "${base64Url(json(header, emptySet()))}.${base64Url(json(claims, numeric))}"
    "$input.${sign(input, auth)}"
}

/**
 * HMAC-SHA256 over the signing input, base64url.
 *
 * Separate from [jwtAssertion] so the crypto can be checked against a published
 * example rather than against itself.
 */
fun hs256(input: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return base64Url(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
}

/**
 * Base64url without padding, as JOSE requires (RFC 7515 §2).
 *
 * Not `Base64.getEncoder()`: standard base64 uses `+` and `/`, both of which
 * have to be escaped in a URL and neither of which a JWT parser accepts, and it
 * pads with `=`, which JOSE forbids outright.
 */
fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * Reads a PKCS#8 PEM private key.
 *
 * PKCS#8 only — a PKCS#1 file (`BEGIN RSA PRIVATE KEY`) is refused with the one
 * command that converts it, rather than parsed by walking ASN.1 by hand. Shared
 * with OAuth 1.0's RSA-SHA1, which wants exactly the same thing.
 */
fun readPrivateKey(path: String): PrivateKey {
    require(path.isNotBlank()) { "No private key file is set." }
    val pem = Files.readString(Path.of(path))
    require(!pem.contains("BEGIN RSA PRIVATE KEY")) {
        "That key is PKCS#1. Convert it first: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem"
    }
    val body = pem.replace(Regex("-----(BEGIN|END)[^-]*-----"), "").filterNot { it.isWhitespace() }
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
}

private fun sign(input: String, auth: ApiAuth): String = when (auth.jwtAlgorithm) {
    JWT_HS256 -> {
        require(auth.clientSecret.isNotBlank()) { "HS256 signs with the client secret, which is empty." }
        hs256(input, auth.clientSecret)
    }

    JWT_RS256 -> {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(readPrivateKey(auth.privateKeyPath))
        signature.update(input.toByteArray(Charsets.UTF_8))
        base64Url(signature.sign())
    }

    else -> error("Unknown JWT algorithm '${auth.jwtAlgorithm}'.")
}

/**
 * A flat JSON object, written by hand.
 *
 * By hand because the alternative is a databind dependency this app has
 * deliberately never taken, and because a JWT's header and claim set are a
 * handful of strings and two numbers. [numeric] names the keys written without
 * quotes: `iat` and `exp` are numbers in JSON, and a provider handed them as
 * strings rejects the assertion.
 */
private fun json(fields: Map<String, String>, numeric: Set<String>): ByteArray =
    fields.entries
        .joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
            val written = if (key in numeric) value else "\"${escape(value)}\""
            "\"${escape(key)}\":$written"
        }
        .toByteArray(Charsets.UTF_8)

/** The escapes JSON requires. A claim carrying a quote is rare and must not corrupt the token. */
private fun escape(text: String): String = buildString {
    text.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}

/** Below this the assertion may expire before it is read. */
private const val MIN_VALIDITY = 30L

/** RFC 7523 leaves this to the provider; every one of them refuses a long-lived assertion. */
private const val MAX_VALIDITY = 3600L
