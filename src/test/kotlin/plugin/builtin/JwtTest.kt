package org.bittrace.plugin.builtin

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtTest {

    private fun b64(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun token(payload: String, header: String = """{"alg":"HS256","typ":"JWT"}""", sig: String = "c2ln") =
        "${b64(header)}.${b64(payload)}.$sig"

    @Test
    fun `decodes header, payload and times`() {
        val jwt = assertNotNull(decodeJwt(token("""{"sub":"alice","exp":1700000000,"iat":1699990000}""")))
        assertEquals("HS256", jwt.alg)
        assertEquals("JWT", jwt.typ)
        assertEquals(Instant.ofEpochSecond(1700000000), jwt.expiresAt)
        assertEquals(Instant.ofEpochSecond(1699990000), jwt.issuedAt)
        assertTrue(jwt.expired(Instant.ofEpochSecond(1700000001)))
        assertFalse(jwt.expired(Instant.ofEpochSecond(1699999999)))
        assertContains(jwt.payloadJson, "\"sub\": \"alice\"")
        assertTrue(jwt.hasSignature)
    }

    @Test
    fun `unsigned token has no signature`() {
        val jwt = assertNotNull(decodeJwt(token("""{"a":1}""", header = """{"alg":"none"}""", sig = "")))
        assertFalse(jwt.hasSignature)
    }

    @Test
    fun `rejects what is not a token`() {
        assertNull(decodeJwt("a.b.c"))
        assertNull(decodeJwt("not a token"))
        assertNull(decodeJwt("${b64("[1,2]")}.${b64("{}")}.x")) // header not an object
        assertNull(decodeJwt("eyJ!!!.eyJ.x"))
    }

    @Test
    fun `finds tokens in a bearer header and a token response`() {
        val access = token("""{"scope":"read"}""")
        val id = token("""{"sub":"bob"}""")
        assertEquals(listOf(access), findJwts("Bearer $access").map { it.raw })
        val body = """{"access_token":"$access","id_token":"$id","token_type":"Bearer"}"""
        assertEquals(listOf(access, id), findJwts(body).map { it.raw })
        assertTrue(findJwts("com.example.Thing and eyJhbGciOi.nope").isEmpty())
    }

    @Test
    fun `formatter shows every token`() {
        val body = """{"access_token":"${token("""{"scope":"read"}""")}"}"""
        val out = JwtFormatter().format(body.toByteArray(), "application/json")
        assertContains(out, "token 1 of 1")
        assertContains(out, "signature not verified")
        assertContains(out, "\"scope\": \"read\"")
        assertEquals("No JSON Web Token found in this body.", JwtFormatter().format("{}".toByteArray(), "application/json"))
        assertTrue(JwtFormatter().handles("application/jwt"))
    }

    @Test
    fun `relative times read both ways`() {
        val now = Instant.ofEpochSecond(10_000)
        assertEquals("in 2m", relativeTo(Instant.ofEpochSecond(10_120), now))
        assertEquals("3h ago", relativeTo(Instant.ofEpochSecond(10_000 - 3 * 3600), now))
    }
}
