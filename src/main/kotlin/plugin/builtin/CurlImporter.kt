package org.bittrace.plugin.builtin

import java.util.Base64
import org.bittrace.plugin.importer.ImportedHeader
import org.bittrace.plugin.importer.ImportedRequest
import org.bittrace.plugin.importer.RequestImporter

/**
 * Imports a `curl` command.
 *
 * This is the format every browser's network panel and most API docs hand you,
 * so it is the one worth shipping built in. The hard part is not curl's flags
 * but the shell around them: quoting, escapes and `\` line continuations all
 * have to be unwound before any flag can be read.
 *
 * Flags that describe *how* curl behaves rather than what the request is —
 * `--compressed`, `-k`, `-L`, `-s` and friends — are skipped silently. Flags
 * that would change the request but cannot be represented are reported as
 * warnings rather than dropped quietly.
 */
class CurlImporter : RequestImporter {

    override val id = "bittrace.curl"
    override val name = "cURL"

    override fun canImport(text: String): Boolean =
        text.trimStart().removePrefix("$").trimStart().startsWith("curl", ignoreCase = true)

    override fun import(text: String): ImportedRequest? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null

        var method: String? = null
        var url = ""
        val headers = mutableListOf<ImportedHeader>()
        val data = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        var index = if (tokens.first().equals("curl", ignoreCase = true)) 1 else 0
        while (index < tokens.size) {
            val token = tokens[index]
            // `-XPOST` and `-H'x: y'` are as common as the spaced forms.
            val (flag, inlineValue) = splitInline(token)

            fun next(): String? = inlineValue ?: tokens.getOrNull(++index)

            when {
                flag == "-X" || flag == "--request" -> method = next()?.uppercase()

                flag == "-H" || flag == "--header" -> next()?.let { header ->
                    val name = header.substringBefore(':').trim()
                    val value = header.substringAfter(':', "").trim()
                    if (name.isNotEmpty()) headers += ImportedHeader(name, value)
                }

                flag in DATA_FLAGS -> next()?.let { data += it }

                flag == "--url" -> next()?.let { url = it }

                flag == "-u" || flag == "--user" -> next()?.let {
                    val encoded = Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
                    headers += ImportedHeader("Authorization", "Basic $encoded")
                }

                flag == "-b" || flag == "--cookie" -> next()?.let { headers += ImportedHeader("Cookie", it) }
                flag == "-A" || flag == "--user-agent" -> next()?.let { headers += ImportedHeader("User-Agent", it) }
                flag == "-e" || flag == "--referer" -> next()?.let { headers += ImportedHeader("Referer", it) }

                flag == "-F" || flag == "--form" -> {
                    next()
                    warnings += "multipart form parts (-F) are not imported"
                }

                flag == "-o" || flag == "--output" || flag == "--data-urlencode" -> {
                    // --data-urlencode needs curl's own encoding rules applied
                    // to a name=value pair; taking it verbatim would be wrong.
                    val value = next()
                    if (flag == "--data-urlencode" && value != null) {
                        warnings += "--data-urlencode was imported without re-encoding"
                        data += value
                    }
                }

                flag in IGNORED_FLAGS -> Unit

                flag.startsWith("-") -> warnings += "ignored $flag"

                else -> if (url.isEmpty()) url = token
            }
            index++
        }

        if (url.isEmpty()) return null

        val body = data.joinToString("&")
        val contentType = headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value
            // curl's own default for -d, which is what the server saw.
            ?: if (body.isNotEmpty()) "application/x-www-form-urlencoded" else ""

        return ImportedRequest(
            // -d implies POST unless the command says otherwise, exactly as curl does.
            method = method ?: if (body.isNotEmpty()) "POST" else "GET",
            url = url,
            headers = headers,
            body = body,
            contentType = contentType,
            warnings = warnings,
        )
    }

    /** Separates `-XPOST` into `-X` and `POST`; leaves spaced forms alone. */
    private fun splitInline(token: String): Pair<String, String?> {
        if (!token.startsWith("-") || token.startsWith("--") || token.length <= 2) return token to null
        val flag = token.take(2)
        return if (flag in INLINE_CAPABLE) flag to token.drop(2) else token to null
    }

    /**
     * Splits a shell command into words.
     *
     * Single quotes are literal, double quotes honour backslash escapes, and a
     * backslash before a newline continues the line — which is how every
     * "copy as cURL" snippet is formatted.
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote = ' '
        var started = false
        var i = 0

        fun flush() {
            if (started) tokens += current.toString()
            current.setLength(0)
            started = false
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quote != ' ' -> when (c) {
                    quote -> quote = ' '
                    '\\' if quote == '"' && i + 1 < text.length -> { current.append(text[i + 1]); i++ }
                    else -> current.append(c)
                }

                c == '\'' || c == '"' -> { quote = c; started = true }

                // A continuation: swallow the backslash and the newline with it.
                c == '\\' && i + 1 < text.length && (text[i + 1] == '\n' || text[i + 1] == '\r') -> {
                    i++
                    while (i + 1 < text.length && (text[i + 1] == '\n' || text[i + 1] == '\r')) i++
                }

                c == '\\' && i + 1 < text.length -> { current.append(text[i + 1]); i++ }

                c.isWhitespace() -> flush()

                else -> { current.append(c); started = true }
            }
            i++
        }
        flush()
        return tokens
    }

    private companion object {
        val DATA_FLAGS = setOf("-d", "--data", "--data-raw", "--data-ascii", "--data-binary")

        /** Short flags curl lets you attach a value to directly. */
        val INLINE_CAPABLE = setOf("-X", "-H", "-d", "-u", "-b", "-A", "-e", "-o")

        /** Transport and output behaviour — nothing about the request itself. */
        val IGNORED_FLAGS = setOf(
            "--compressed", "-k", "--insecure", "-L", "--location", "-s", "--silent",
            "-i", "--include", "-v", "--verbose", "-f", "--fail", "-g", "--globoff",
            "--http1.0", "--http1.1", "--http2", "-#", "--progress-bar", "-S", "--show-error",
        )
    }
}
