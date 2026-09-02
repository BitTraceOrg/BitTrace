package org.bittrace.api.oauth

import org.bittrace.api.ApiAuth
import org.bittrace.api.SIG_HMAC_SHA1
import org.bittrace.api.SIG_HMAC_SHA256
import org.bittrace.api.SIG_PLAINTEXT
import org.bittrace.api.SIG_RSA_SHA1
import java.net.URI
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth 1.0a request signing (RFC 5849).
 *
 * Pure and synchronous, which is what OAuth 1.0 is: there is no token to fetch
 * and nothing to expire, only a signature computed fresh for every request from
 * its method, its URL and its parameters. That makes the whole of it testable
 * without a network, which matters more here than almost anywhere else in this
 * app — a signature is either byte-exact or rejected, with no partial credit and
 * no useful error from the server.
 *
 * Nearly every way this goes wrong is percent-encoding. See [percentEncode].
 */

/**
 * The `Authorization` header value for one request.
 *
 * [formParams] are the body's parameters when it is `application/x-www-form-
 * urlencoded`, which RFC 5849 §3.4.1.3.1 folds into the signature alongside the
 * query. Any other body is not signed — its bytes are not parameters, and a
 * server that expected them to be would be signing something it cannot
 * reconstruct.
 */
fun oauth1Header(
    method: String,
    url: String,
    auth: ApiAuth,
    formParams: List<Pair<String, String>> = emptyList(),
    nonce: String = newNonce(),
    timestamp: Long = System.currentTimeMillis() / 1000,
): Result<String> = runCatching {
    val oauth = buildMap {
        put("oauth_consumer_key", auth.consumerKey)
        put("oauth_nonce", nonce)
        put("oauth_signature_method", auth.signatureMethod)
        put("oauth_timestamp", timestamp.toString())
        put("oauth_version", "1.0")
        if (auth.oauthToken.isNotBlank()) put("oauth_token", auth.oauthToken)
    }

    val base = signatureBaseString(method, url, oauth, formParams)
    val signature = sign(base, auth)

    // `realm` is sent and never signed — it is the one header parameter that
    // stays out of the base string (§3.4.1.3.1).
    val header = buildList {
        if (auth.realm.isNotBlank()) add("realm" to auth.realm)
        oauth.forEach { (k, v) -> add(k to v) }
        add("oauth_signature" to signature)
    }
    header.joinToString(", ", prefix = "OAuth ") { (k, v) -> "${percentEncode(k)}=\"${percentEncode(v)}\"" }
}

/**
 * `METHOD&encodedUri&encodedParameters` — RFC 5849 §3.4.1.1.
 *
 * Exposed because it is the thing worth testing: every published OAuth 1.0
 * example ships its expected base string, and a signature that disagrees always
 * disagrees here first.
 */
fun signatureBaseString(
    method: String,
    url: String,
    oauthParams: Map<String, String>,
    formParams: List<Pair<String, String>> = emptyList(),
): String {
    val uri = URI(url)

    // §3.4.1.2: scheme and host lowercased, the default port dropped, and no
    // query or fragment — the query is a parameter, not part of the URI.
    val port = uri.port
    val scheme = uri.scheme.lowercase()
    val authority = buildString {
        append(uri.host.lowercase())
        val default = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        if (port > 0 && !default) append(':').append(port)
    }
    val baseUri = "$scheme://$authority${uri.rawPath.ifEmpty { "/" }}"

    val params = buildList {
        addAll(queryParams(uri.rawQuery))
        addAll(formParams)
        oauthParams.forEach { (k, v) -> add(k to v) }
    }

    // §3.4.1.3.2: encode both halves, then sort by encoded name and, for equal
    // names, by encoded value. Sorting before encoding gives a different order
    // for anything non-ASCII, which is the subtle version of this bug.
    val normalized = params
        .map { (name, value) -> percentEncode(name) to percentEncode(value) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { (name, value) -> "$name=$value" }

    return "${method.uppercase()}&${percentEncode(baseUri)}&${percentEncode(normalized)}"
}

/**
 * Percent-encoding as OAuth defines it (§3.6), which is **not**
 * `URLEncoder.encode`.
 *
 * The unreserved set is `A-Za-z0-9-._~` and nothing else. `URLEncoder` disagrees
 * in three places that each break a signature: it writes a space as `+` rather
 * than `%20`, it leaves `*` bare, and it escapes `~`. Those three characters are
 * the reason most first attempts at OAuth 1.0 fail against real servers, and the
 * reason this is a function rather than a call to the JDK.
 */
fun percentEncode(text: String): String = buildString {
    text.toByteArray(Charsets.UTF_8).forEach { byte ->
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() && byte >= 0 || c in UNRESERVED_PUNCTUATION) {
            append(c)
        } else {
            append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }
}

/** The signature itself, by whichever method the config names. */
private fun sign(base: String, auth: ApiAuth): String {
    val key = "${percentEncode(auth.consumerSecret)}&${percentEncode(auth.oauthTokenSecret)}"
    return when (auth.signatureMethod) {
        // PLAINTEXT *is* the signing key: no hashing, and only defensible over
        // HTTPS, since it puts both secrets on the wire.
        SIG_PLAINTEXT -> key
        SIG_HMAC_SHA1 -> hmac("HmacSHA1", key, base)
        SIG_HMAC_SHA256 -> hmac("HmacSHA256", key, base)
        SIG_RSA_SHA1 -> rsa(base, auth.privateKeyPath)
        else -> error("Unknown signature method '${auth.signatureMethod}'.")
    }
}

private fun hmac(algorithm: String, key: String, base: String): String {
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm))
    return Base64.getEncoder().encodeToString(mac.doFinal(base.toByteArray(Charsets.UTF_8)))
}

/**
 * RSA-SHA1, over a PKCS#8 PEM.
 *
 * PKCS#8 only. A PKCS#1 file — the one beginning `-----BEGIN RSA PRIVATE
 * KEY-----` — is refused with instructions rather than parsed: reading it means
 * walking ASN.1 by hand, and `openssl pkcs8` converts it in one line.
 */
private fun rsa(base: String, keyPath: String): String {
    require(keyPath.isNotBlank()) { "RSA-SHA1 needs a private key file." }
    val signature = Signature.getInstance("SHA1withRSA")
    signature.initSign(readPrivateKey(keyPath))
    signature.update(base.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(signature.sign())
}

/**
 * The query string as decoded name/value pairs.
 *
 * Decoded here and re-encoded by the caller, because §3.4.1.3.1 signs the
 * *decoded* values: a URL that arrives already encoded must not be encoded
 * twice, and one that arrives raw must not be signed raw.
 */
private fun queryParams(rawQuery: String?): List<Pair<String, String>> =
    rawQuery.orEmpty()
        .split('&')
        .filter { it.isNotBlank() }
        .map { pair ->
            val name = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            decode(name) to decode(value)
        }

/** Percent-decoding that leaves a malformed escape alone rather than throwing. */
private fun decode(text: String): String =
    runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)

/** A fresh nonce. Unique per request is the whole requirement. */
fun newNonce(): String {
    val bytes = ByteArray(NONCE_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private const val NONCE_BYTES = 16

private const val UNRESERVED_PUNCTUATION = "-._~"

private val HEX = "0123456789ABCDEF".toCharArray()
