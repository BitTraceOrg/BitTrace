package org.bittrace.api.oauth

import org.bittrace.ui.layouts.inspector.components.matches
import org.bittrace.api.ApiAuth
import org.bittrace.api.AUTH_OAUTH1
import org.bittrace.api.SIG_HMAC_SHA1
import org.bittrace.api.SIG_PLAINTEXT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for OAuth 1.0a signing.
 *
 * A signature is byte-exact or rejected — servers answer a wrong one with a bare
 * 401 and no hint — so this is checked against a published vector rather than
 * against itself. Twitter's "Creating a signature" example is the one used
 * here: it publishes the inputs, the expected signature base string and the
 * expected signature, so a failure says *which* step is wrong instead of only
 * that something is.
 */
class OAuth1Test {

    // Twitter's worked example, verbatim.
    private val auth = ApiAuth(
        type = AUTH_OAUTH1,
        consumerKey = "xvz1evFS4wEEPTGEFPHBog",
        consumerSecret = "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
        oauthToken = "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
        oauthTokenSecret = "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
        signatureMethod = SIG_HMAC_SHA1,
    )
    private val url = "https://api.twitter.com/1.1/statuses/update.json?include_entities=true"
    private val form = listOf("status" to "Hello Ladies + Gentlemen, a signed OAuth request!")
    private val oauthParams = mapOf(
        "oauth_consumer_key" to auth.consumerKey,
        "oauth_nonce" to "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
        "oauth_signature_method" to "HMAC-SHA1",
        "oauth_timestamp" to "1318622958",
        "oauth_token" to auth.oauthToken,
        "oauth_version" to "1.0",
    )

    @Test
    fun `the signature base string matches the published one`() {
        val expected = "POST&https%3A%2F%2Fapi.twitter.com%2F1.1%2Fstatuses%2Fupdate.json&" +
            "include_entities%3Dtrue%26oauth_consumer_key%3Dxvz1evFS4wEEPTGEFPHBog%26" +
            "oauth_nonce%3DkYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg%26" +
            "oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1318622958%26" +
            "oauth_token%3D370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb%26" +
            "oauth_version%3D1.0%26" +
            "status%3DHello%2520Ladies%2520%252B%2520Gentlemen%252C%2520a%2520signed%2520OAuth%2520request%2521"

        assertEquals(expected, signatureBaseString("POST", url, oauthParams, form))
    }

    @Test
    fun `the signature matches the published one`() {
        val header = oauth1Header(
            method = "POST",
            url = url,
            auth = auth,
            formParams = form,
            nonce = "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
            timestamp = 1318622958,
        ).getOrThrow()

        // Percent-encoded in the header, as every value there is.
        assertTrue(
            header.contains("""oauth_signature="hCtSmYh%2BiHYCEqBWrE7C7hYmtUk%3D""""),
            header,
        )
    }

    @Test
    fun `the header carries every oauth parameter, and quotes them`() {
        val header = oauth1Header("GET", "https://example.com/a", auth, nonce = "n", timestamp = 1).getOrThrow()

        assertTrue(header.startsWith("OAuth "))
        assertTrue(header.contains("""oauth_consumer_key="xvz1evFS4wEEPTGEFPHBog""""))
        assertTrue(header.contains("""oauth_version="1.0""""))
        assertTrue(header.contains("""oauth_nonce="n""""))
    }

    @Test
    fun `realm is sent but never signed`() {
        val withRealm = auth.copy(realm = "Photos")
        val header = oauth1Header("GET", "https://example.com/a", withRealm, nonce = "n", timestamp = 1).getOrThrow()
        val without = oauth1Header("GET", "https://example.com/a", auth, nonce = "n", timestamp = 1).getOrThrow()

        assertTrue(header.contains("""realm="Photos""""))
        // Same signature either way: realm is a header parameter and stays out
        // of the base string (RFC 5849 §3.4.1.3.1).
        val signatureOf = { h: String -> h.substringAfter("oauth_signature=") }
        assertEquals(signatureOf(without), signatureOf(header))
    }

    // --- percent-encoding, where this usually goes wrong --------------------

    @Test
    fun `encoding follows OAuth, not URLEncoder`() {
        // The three characters URLEncoder disagrees on, each of which alone
        // breaks a signature.
        assertEquals("%20", percentEncode(" "), "a space is %20, not +")
        assertEquals("%2A", percentEncode("*"), "an asterisk is escaped")
        assertEquals("~", percentEncode("~"), "a tilde is unreserved and is not")
    }

    @Test
    fun `the unreserved set survives and everything else does not`() {
        assertEquals("abcXYZ019-._~", percentEncode("abcXYZ019-._~"))
        assertEquals("%2B", percentEncode("+"))
        assertEquals("%3D", percentEncode("="))
        assertEquals("%26", percentEncode("&"))
    }

    @Test
    fun `multi-byte characters are encoded per UTF-8 byte`() {
        assertEquals("%C3%A9", percentEncode("é"))
        assertEquals("%E2%82%AC", percentEncode("€"))
    }

    @Test
    fun `hex digits are upper case`() {
        // Lower-case hex is a valid URL and an invalid signature.
        assertEquals("%2A", percentEncode("*"))
        assertTrue(percentEncode("é").none { it in 'a'..'f' })
    }

    // --- the base string's own rules ---------------------------------------

    @Test
    fun `a default port is left out of the base uri`() {
        val base = signatureBaseString("GET", "https://example.com:443/a", emptyMap())

        assertTrue(base.contains("https%3A%2F%2Fexample.com%2Fa"), base)
    }

    @Test
    fun `a non-default port is kept`() {
        val base = signatureBaseString("GET", "https://example.com:8443/a", emptyMap())

        assertTrue(base.contains("https%3A%2F%2Fexample.com%3A8443%2Fa"), base)
    }

    @Test
    fun `the scheme and host are lower-cased, the path is not`() {
        val base = signatureBaseString("get", "HTTPS://Example.COM/Path", emptyMap())

        assertTrue(base.startsWith("GET&"), "the method is upper-cased")
        assertTrue(base.contains("https%3A%2F%2Fexample.com%2FPath"), base)
    }

    @Test
    fun `parameters sort by encoded name, then by encoded value`() {
        val base = signatureBaseString(
            "GET",
            "https://example.com/a?b=2&a=1&b=1",
            emptyMap(),
        )
        val params = base.substringAfterLast("&")

        assertEquals("a%3D1%26b%3D1%26b%3D2", params)
    }

    // --- the other methods --------------------------------------------------

    @Test
    fun `plaintext is the signing key itself`() {
        val plain = auth.copy(signatureMethod = SIG_PLAINTEXT)
        val header = oauth1Header("GET", "https://example.com/a", plain, nonce = "n", timestamp = 1).getOrThrow()

        val expected = percentEncode("${percentEncode(plain.consumerSecret)}&${percentEncode(plain.oauthTokenSecret)}")
        assertTrue(header.contains("""oauth_signature="$expected""""), header)
    }

    @Test
    fun `an unknown method fails rather than signing with nothing`() {
        val broken = auth.copy(signatureMethod = "MD5-OF-HOPE")

        assertTrue(oauth1Header("GET", "https://example.com/a", broken).isFailure)
    }

    @Test
    fun `a pkcs1 key is refused with instructions`() {
        val rsa = auth.copy(signatureMethod = org.bittrace.api.SIG_RSA_SHA1, privateKeyPath = pkcs1File())
        val error = oauth1Header("GET", "https://example.com/a", rsa).exceptionOrNull()

        assertTrue(error?.message?.contains("openssl pkcs8") == true, error?.message.orEmpty())
    }

    private fun pkcs1File(): String {
        val file = java.nio.file.Files.createTempFile("bittrace-key", ".pem")
        java.nio.file.Files.writeString(file, "-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----\n")
        file.toFile().deleteOnExit()
        return file.toString()
    }
}
