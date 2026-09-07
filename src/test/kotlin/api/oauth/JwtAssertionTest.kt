package org.bittrace.api.oauth

import org.bittrace.ui.layouts.inspector.components.matches
import org.bittrace.api.ApiAuth
import org.bittrace.api.AUTH_OAUTH2
import org.bittrace.api.GRANT_JWT_BEARER
import org.bittrace.api.JWT_HS256
import org.bittrace.api.JWT_RS256
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the JWT Bearer assertion (RFC 7523).
 *
 * A provider rejects a malformed assertion with an opaque `invalid_grant` and no
 * hint, so the format is pinned here: the encoding JOSE requires, the claim
 * types it requires, and the signature checked against a published example
 * rather than against itself.
 */
class JwtAssertionTest {

    private val auth = ApiAuth(
        type = AUTH_OAUTH2,
        grantType = GRANT_JWT_BEARER,
        jwtAlgorithm = JWT_HS256,
        clientSecret = "a-shared-secret",
        jwtIssuer = "service@example.com",
        tokenUrl = "https://example.com/token",
    )
    private val issuedAt = Instant.ofEpochSecond(1_700_000_000)

    private fun decode(part: String) = String(Base64.getUrlDecoder().decode(part))

    // --- the signature -------------------------------------------------------

    @Test
    fun `HS256 matches the published example`() {
        // jwt.io's default token, which publishes its input and its signature —
        // so this checks the crypto rather than checking it against itself.
        val input = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"

        assertEquals("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c", hs256(input, "your-256-bit-secret"))
    }

    @Test
    fun `base64url is url-safe and unpadded, as JOSE requires`() {
        // Bytes chosen so standard base64 would emit both `+` and `/`.
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte())
        val encoded = base64Url(bytes)

        assertFalse(encoded.contains('+'), encoded)
        assertFalse(encoded.contains('/'), encoded)
        assertFalse(encoded.contains('='), "JOSE forbids padding outright")
    }

    // --- the shape -----------------------------------------------------------

    @Test
    fun `an assertion is three base64url parts`() {
        val jwt = jwtAssertion(auth, issuedAt).getOrThrow()
        val parts = jwt.split('.')

        assertEquals(3, parts.size)
        assertTrue(parts.all { it.isNotEmpty() })
        assertFalse(jwt.contains('='))
    }

    @Test
    fun `the header names the algorithm`() {
        val header = decode(jwtAssertion(auth, issuedAt).getOrThrow().split('.')[0])

        assertTrue(header.contains(""""alg":"HS256""""), header)
        assertTrue(header.contains(""""typ":"JWT""""), header)
        // No `kid` unless one was set — a provider that does not rotate keys
        // has no use for it.
        assertFalse(header.contains("kid"), header)
    }

    @Test
    fun `a key id is carried when there is one`() {
        val header = decode(jwtAssertion(auth.copy(jwtKeyId = "key-7"), issuedAt).getOrThrow().split('.')[0])

        assertTrue(header.contains(""""kid":"key-7""""), header)
    }

    @Test
    fun `iat and exp are numbers, not strings`() {
        val claims = decode(jwtAssertion(auth, issuedAt).getOrThrow().split('.')[1])

        // A provider handed these as strings rejects the assertion, and says
        // nothing about why.
        assertTrue(claims.contains(""""iat":1700000000"""), claims)
        assertTrue(claims.contains(""""exp":1700000300"""), claims)
        assertFalse(claims.contains(""""iat":"""" ), claims)
    }

    @Test
    fun `the subject falls back to the issuer`() {
        val claims = decode(jwtAssertion(auth, issuedAt).getOrThrow().split('.')[1])

        assertTrue(claims.contains(""""sub":"service@example.com""""), claims)
    }

    @Test
    fun `the audience falls back to the token url`() {
        val claims = decode(jwtAssertion(auth, issuedAt).getOrThrow().split('.')[1])

        assertTrue(claims.contains(""""aud":"https://example.com/token""""), claims)
    }

    @Test
    fun `an explicit audience wins`() {
        val explicit = auth.copy(jwtAudience = "https://other/aud")
        val claims = decode(jwtAssertion(explicit, issuedAt).getOrThrow().split('.')[1])

        assertTrue(claims.contains(""""aud":"https://other/aud""""), claims)
    }

    @Test
    fun `validity is clamped, since every provider refuses a long-lived assertion`() {
        val long = jwtAssertion(auth.copy(jwtValiditySeconds = 99_999), issuedAt).getOrThrow()
        val claims = decode(long.split('.')[1])

        assertTrue(claims.contains(""""exp":1700003600"""), claims)
    }

    @Test
    fun `a quote in a claim cannot corrupt the token`() {
        val hostile = auth.copy(jwtIssuer = """ev"il""")
        val claims = decode(jwtAssertion(hostile, issuedAt).getOrThrow().split('.')[1])

        assertTrue(claims.contains("""ev\"il"""), claims)
    }

    // --- refusals ------------------------------------------------------------

    @Test
    fun `an assertion with no issuer is refused`() {
        assertTrue(jwtAssertion(auth.copy(jwtIssuer = "")).isFailure)
    }

    @Test
    fun `an assertion with nothing to be the audience is refused`() {
        assertTrue(jwtAssertion(auth.copy(tokenUrl = "", jwtAudience = "")).isFailure)
    }

    @Test
    fun `HS256 with no secret is refused rather than signed with nothing`() {
        assertTrue(jwtAssertion(auth.copy(clientSecret = "")).isFailure)
    }

    @Test
    fun `RS256 with no key file is refused`() {
        assertTrue(jwtAssertion(auth.copy(jwtAlgorithm = JWT_RS256, privateKeyPath = "")).isFailure)
    }

    @Test
    fun `an unknown algorithm is refused`() {
        assertTrue(jwtAssertion(auth.copy(jwtAlgorithm = "ES256")).isFailure)
    }

    // --- the request it goes into -------------------------------------------

    @Test
    fun `the token request carries the grant and the assertion`() {
        val body = tokenRequestBody(auth, assertion = "the.signed.jwt")

        assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"), body)
        assertTrue(body.contains("assertion=the.signed.jwt"), body)
    }
}
