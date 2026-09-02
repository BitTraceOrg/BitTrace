package org.bittrace.api.oauth

import org.bittrace.api.ApiAuth
import org.bittrace.api.AUTH_OAUTH2
import org.bittrace.api.GRANT_AUTH_CODE
import org.bittrace.api.GRANT_CLIENT_CREDENTIALS
import org.bittrace.api.GRANT_PASSWORD
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure half of OAuth 2.0 — the requests built and the answers
 * read. The sockets are elsewhere; these are the parts that fail silently.
 */
class OAuth2Test {

    private val auth = ApiAuth(
        type = AUTH_OAUTH2,
        clientId = "client-1",
        clientSecret = "s3cret",
        authUrl = "https://example.com/authorize",
        tokenUrl = "https://example.com/token",
        scope = "read write",
        redirectPort = 8081,
        redirectPath = "/callback",
    )

    // --- PKCE ---------------------------------------------------------------

    @Test
    fun `the S256 challenge matches RFC 7636's published pair`() {
        // Appendix B of the RFC, which exists precisely so implementations can
        // check this one step.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge(verifier))
    }

    @Test
    fun `a generated verifier is long enough and uses only unreserved characters`() {
        val verifier = pkceVerifier()

        // RFC 7636 §4.1: 43 to 128 characters from `A-Za-z0-9-._~`.
        assertTrue(verifier.length in 43..128, verifier)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" }, verifier)
        assertTrue(pkceVerifier() != verifier, "each one is fresh")
    }

    // --- the authorization URL ---------------------------------------------

    @Test
    fun `the authorization url carries what the provider needs`() {
        val url = authorizationUrl(auth, state = "st4te", challenge = "chal")

        assertTrue(url.startsWith("https://example.com/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-1"))
        assertTrue(url.contains("state=st4te"))
        assertTrue(url.contains("code_challenge=chal"))
        assertTrue(url.contains("code_challenge_method=S256"))
        // Encoded, since it is a URL inside a URL.
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8081%2Fcallback"), url)
        assertTrue(url.contains("scope=read+write"), url)
    }

    @Test
    fun `an authorization url that already has a query gets an ampersand`() {
        val withQuery = auth.copy(authUrl = "https://example.com/authorize?tenant=acme")

        assertTrue(authorizationUrl(withQuery, "s", null).contains("?tenant=acme&response_type=code"))
    }

    @Test
    fun `pkce is left out when it is not wanted`() {
        assertFalse(authorizationUrl(auth, "s", challenge = null).contains("code_challenge"))
    }

    @Test
    fun `the redirect is the loopback address, not localhost`() {
        // RFC 8252 §8.3 prefers the literal address: `localhost` resolves
        // through a name service and does not always mean this machine.
        assertEquals("http://127.0.0.1:8081/callback", redirectUri(auth))
    }

    // --- token request bodies ----------------------------------------------

    @Test
    fun `the authorization-code body carries the code, the redirect and the verifier`() {
        val body = tokenRequestBody(auth, code = "abc", verifier = "v3rifier")

        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=abc"))
        assertTrue(body.contains("code_verifier=v3rifier"))
        assertTrue(body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8081%2Fcallback"), body)
    }

    @Test
    fun `client credentials asks for nothing but the grant`() {
        val body = tokenRequestBody(auth.copy(grantType = GRANT_CLIENT_CREDENTIALS))

        assertTrue(body.contains("grant_type=client_credentials"))
        assertFalse(body.contains("code="))
        assertFalse(body.contains("username="))
    }

    @Test
    fun `the password grant sends the resource owner's credentials`() {
        val body = tokenRequestBody(
            auth.copy(grantType = GRANT_PASSWORD, username = "ada", password = "p w"),
        )

        assertTrue(body.contains("username=ada"))
        assertTrue(body.contains("password=p+w"), body)
    }

    @Test
    fun `credentials stay out of the body unless asked for`() {
        assertFalse(tokenRequestBody(auth, code = "a").contains("client_secret"))
        // RFC 6749 §2.3.1 says clients SHOULD prefer the Basic header, so that
        // is the default and this is the opt-in.
        assertTrue(tokenRequestBody(auth.copy(credentialsInBody = true), code = "a").contains("client_secret=s3cret"))
    }

    @Test
    fun `a refresh overrides the grant entirely`() {
        val body = tokenRequestBody(auth.copy(grantType = GRANT_AUTH_CODE), refreshToken = "r3fresh")

        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=r3fresh"))
        assertFalse(body.contains("code="), "a refresh is not a code exchange")
    }

    // --- responses ----------------------------------------------------------

    @Test
    fun `a token response is read`() {
        val token = parseTokenResponse(
            """{"access_token":"at","token_type":"Bearer","refresh_token":"rt","expires_in":3600,"scope":"read"}""",
        ).getOrThrow()

        assertEquals("at", token.accessToken)
        assertEquals("rt", token.refreshToken)
        assertEquals("read", token.scope)
        assertEquals("Authorization" to "Bearer at", token.header())
        assertFalse(token.expired())
    }

    @Test
    fun `an error response keeps the provider's own words`() {
        val error = parseTokenResponse(
            """{"error":"invalid_grant","error_description":"code already used"}""",
        ).exceptionOrNull() as OAuthException

        assertEquals("invalid_grant", error.error.code)
        assertTrue(error.message!!.contains("code already used"))
    }

    @Test
    fun `a response with no token is a failure, not an empty token`() {
        assertTrue(parseTokenResponse("""{"ok":true}""").isFailure)
    }

    @Test
    fun `nested objects do not confuse the reader`() {
        // Some providers return an `id_token` claims object or an array of
        // scopes alongside the fields that matter.
        val token = parseTokenResponse(
            """{"extra":{"access_token":"wrong"},"scopes":["a","b"],"access_token":"right"}""",
        ).getOrThrow()

        assertEquals("right", token.accessToken)
    }

    @Test
    fun `a token with no expiry never asks to be refreshed`() {
        val token = OAuthToken(accessToken = "at")

        assertNull(token.expiresAt)
        assertFalse(token.expired())
    }

    @Test
    fun `a token about to expire counts as expired`() {
        // The slack is the point: one that dies mid-flight fails exactly like a
        // wrong one, and is far more confusing.
        val token = OAuthToken(accessToken = "at", expiresAt = Instant.now().plusSeconds(30))

        assertTrue(token.expired())
    }

    @Test
    fun `a device authorization response is read`() {
        val grant = parseDeviceCodeResponse(
            """{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"https://x/dev","interval":7}""",
        ).getOrThrow()

        assertEquals("dc", grant.deviceCode)
        assertEquals("WDJB-MJHT", grant.userCode)
        assertEquals(7, grant.intervalSeconds)
    }

    @Test
    fun `the device poll can read a pending error as status`() {
        assertEquals("authorization_pending", errorIn("""{"error":"authorization_pending"}"""))
        assertNull(errorIn("""{"access_token":"at"}"""))
    }

    // --- the token store ----------------------------------------------------

    @Test
    fun `the same client and scope share a token`() {
        val store = OAuthTokens()
        store.put(auth, OAuthToken("at"))

        // A different URL on the same client is the same authorisation.
        assertEquals("at", store.of(auth.copy(username = "someone"))?.accessToken)
    }

    @Test
    fun `a different scope is a different token`() {
        val store = OAuthTokens()
        store.put(auth, OAuthToken("at"))

        // A token issued for one scope is not a token for another, however much
        // the client id matches.
        assertNull(store.of(auth.copy(scope = "admin")))
    }

    @Test
    fun `clearing removes only the one`() {
        val store = OAuthTokens()
        val other = auth.copy(clientId = "client-2")
        store.put(auth, OAuthToken("a"))
        store.put(other, OAuthToken("b"))

        store.clear(auth)

        assertNull(store.of(auth))
        assertEquals("b", store.of(other)?.accessToken)
    }
}
