package org.bittrace.api.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder

/** What came back on the redirect. Exactly one of [code] and [error] is set. */
class RedirectResult(val code: String?, val state: String?, val error: String?, val errorDescription: String?)

/**
 * A one-shot listener for the authorization redirect.
 *
 * A raw [ServerSocket] rather than `com.sun.net.httpserver`: this answers
 * exactly one request in its life, so a server framework is more moving parts
 * than the job has — and keeping `jdk.httpserver` out of the picture keeps it
 * out of the module set that packaging computes from bytecode.
 *
 * Bound to the loopback address explicitly. Binding to every interface would
 * put an authorization code on the local network, and the code is a credential
 * until it is exchanged.
 *
 * The timeout matters as much as the success path: an authorisation nobody
 * finishes is the normal way this ends — you change your mind, or the provider
 * refuses — and a socket left listening on a fixed port stops the *next*
 * attempt from binding.
 */
suspend fun awaitRedirect(port: Int, timeoutSeconds: Long): Result<RedirectResult> =
    withContext(Dispatchers.IO) {
        val outcome = runCatching {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                // A short accept timeout and a loop, rather than one long
                // blocking accept. `accept` cannot be interrupted, and a
                // cancellation handler that closes the socket cannot run while
                // the job is still inside it — a job is not *complete* until its
                // body returns, so the thing that would unblock the wait is
                // itself waiting on the wait. Polling gives cancellation a place
                // to take effect, once every POLL_MS.
                server.soTimeout = POLL_MS
                val deadline = System.nanoTime() + timeoutSeconds * NANOS_PER_SECOND

                while (isActive && System.nanoTime() < deadline) {
                    val socket = try {
                        server.accept()
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }

                    val result = socket.use { answer(it) }
                    // Browsers open speculative connections — a preconnect, a
                    // favicon fetch — and taking the first one blind would end
                    // the flow on a request that carries nothing. Only a
                    // redirect actually bearing a code or an error counts;
                    // anything else is answered and ignored.
                    if (result.code != null || result.error != null) return@use result
                }
                error("no redirect arrived within ${timeoutSeconds}s")
            }
        }
        // Cancellation is not a failure and must not come back as one: the
        // caller tells "stopped" from "went wrong" by which it gets.
        ensureActive()
        outcome
    }

/** Reads one request and answers it, whatever it turns out to be. */
private fun answer(socket: Socket): RedirectResult {
    val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
    val target = requestLine.split(' ').getOrNull(1).orEmpty()
    val params = queryOf(target)

    val result = RedirectResult(
        code = params["code"],
        state = params["state"],
        error = params["error"],
        errorDescription = params["error_description"],
    )
    socket.getOutputStream().apply {
        write(pageFor(result).toByteArray(Charsets.UTF_8))
        flush()
    }
    return result
}

/** The query of a request target like `/callback?code=…&state=…`. */
private fun queryOf(target: String): Map<String, String> {
    val query = runCatching { URI(target).rawQuery }.getOrNull() ?: target.substringAfter('?', "")
    return query.split('&')
        .filter { it.isNotBlank() }
        .associate { pair ->
            val name = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            decode(name) to decode(value)
        }
}

private fun decode(text: String): String =
    runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)

/**
 * What the browser shows once it has handed the code over.
 *
 * Deliberately plain and self-contained — no styling to load, nothing fetched.
 * It exists so the tab does not sit on a connection error after a successful
 * authorisation, which reads as a failure even when everything worked.
 */
private fun pageFor(result: RedirectResult): String {
    val message = when {
        result.error != null -> "Authorisation failed: ${result.error}"
        result.code != null -> "Authorised. You can close this tab and go back to BitTrace."
        else -> "That redirect carried no authorization code."
    }
    val body = """
        <!doctype html><html><head><meta charset="utf-8"><title>BitTrace</title></head>
        <body style="font:14px system-ui;padding:40px;color:#1a1e21">$message</body></html>
    """.trimIndent()

    return buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        append("Connection: close\r\n\r\n")
        append(body)
    }
}

/**
 * How often the wait looks up to see whether it has been cancelled.
 *
 * Short enough that Stop feels immediate, long enough that a five-minute wait is
 * not six hundred wakeups doing nothing.
 */
private const val POLL_MS = 500

private const val NANOS_PER_SECOND = 1_000_000_000L
