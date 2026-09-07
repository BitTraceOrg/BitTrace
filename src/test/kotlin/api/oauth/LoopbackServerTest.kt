package org.bittrace.api.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of the OAuth flow made of sockets.
 *
 * Every case here is a browser doing something other than "send exactly one
 * well-formed request and wait" — which is most of what browsers do. A
 * preconnect that sends nothing at all is the one that broke the flow in
 * practice: the listener sat reading it forever while the real redirect waited
 * behind it in the backlog.
 */
class LoopbackServerTest {

    /** A port nobody else is on, taken by asking for one and letting it go. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun send(port: Int, request: String) {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            // Read the answer back, as a browser would, so the server's write
            // has somewhere to go.
            runCatching { socket.getInputStream().readBytes() }
        }
    }

    private fun callback(code: String, state: String) =
        "GET /callback?code=$code&state=$state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"

    @Test
    fun `a redirect carrying a code is read`() = runBlocking {
        val port = freePort()
        val waiting = async { awaitRedirect(port, timeoutSeconds = 10) }
        withContext(Dispatchers.IO) {
            delay(150)
            send(port, callback("abc123", "s1"))
        }

        val result = withTimeout(10_000) { waiting.await() }.getOrThrow()
        assertEquals("abc123", result.code)
        assertEquals("s1", result.state)
        assertNull(result.error)
    }

    @Test
    fun `a silent preconnect does not block the redirect behind it`() = runBlocking {
        // What a browser really does: open a socket ahead of time and hold it
        // idle, then send the request on a second one. Reading the first with
        // no timeout meant the second was never accepted.
        val port = freePort()
        val waiting = async { awaitRedirect(port, timeoutSeconds = 20) }

        val idle = withContext(Dispatchers.IO) {
            delay(150)
            val socket = Socket(InetAddress.getByName("127.0.0.1"), port)
            delay(100)
            send(port, callback("real-code", "s2"))
            socket
        }

        val result = withTimeout(20_000) { waiting.await() }.getOrThrow()
        assertEquals("real-code", result.code)
        idle.close()
    }

    @Test
    fun `a request carrying nothing is answered and ignored`() = runBlocking {
        val port = freePort()
        val waiting = async { awaitRedirect(port, timeoutSeconds = 20) }
        withContext(Dispatchers.IO) {
            delay(150)
            send(port, "GET /favicon.ico HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            send(port, callback("after-favicon", "s3"))
        }

        assertEquals("after-favicon", withTimeout(20_000) { waiting.await() }.getOrThrow().code)
    }

    @Test
    fun `a provider's refusal comes back as an error`() = runBlocking {
        val port = freePort()
        val waiting = async { awaitRedirect(port, timeoutSeconds = 10) }
        withContext(Dispatchers.IO) {
            delay(150)
            send(
                port,
                "GET /callback?error=access_denied&error_description=User%20said%20no HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n\r\n",
            )
        }

        val result = withTimeout(10_000) { waiting.await() }.getOrThrow()
        assertEquals("access_denied", result.error)
        assertEquals("User said no", result.errorDescription)
        assertNull(result.code)
    }

    @Test
    fun `the browser is answered with a page, not a dropped connection`() = runBlocking {
        val port = freePort()
        val waiting = async { awaitRedirect(port, timeoutSeconds = 10) }
        val answer = withContext(Dispatchers.IO) {
            delay(150)
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.getOutputStream().apply {
                    write(callback("abc", "s4").toByteArray(Charsets.UTF_8))
                    flush()
                }
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }
        waiting.await().getOrThrow()

        assertTrue(answer.startsWith("HTTP/1.1 200 OK"), "no response line in: ${answer.take(60)}")
        assertTrue(answer.contains("Authorised"), "no success page in the body")
    }

    @Test
    fun `nothing arriving times out rather than waiting forever`() = runBlocking {
        val port = freePort()
        val result = withTimeout(10_000) { awaitRedirect(port, timeoutSeconds = 1) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `the port is free again once the wait is over`() = runBlocking {
        // A socket left listening is what stops the *next* attempt from binding,
        // which is the failure that outlives the one that caused it.
        val port = freePort()
        awaitRedirect(port, timeoutSeconds = 1)
        coroutineScope {
            withContext(Dispatchers.IO) {
                ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).close()
            }
        }
    }
}
