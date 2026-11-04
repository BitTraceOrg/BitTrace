package org.bittrace.rules

import org.bittrace.data.NameValuePair

/**
 * Headers as the text you would type them: one `Name: value` per line.
 *
 * The rules and breakpoint editors take headers this way rather than through a
 * table, because what you are doing there is editing a message on its way past,
 * and the message *is* this text — pasting a block of headers out of a browser's
 * network panel should just work. Duplicate names are kept, in order, since
 * HTTP allows them and `Set-Cookie` depends on it.
 */
object HeaderText {

    fun format(headers: List<NameValuePair>): String = headers.joinToString("\n") { "${it.name}: ${it.value}" }

    fun formatLines(headers: List<HeaderLine>): String = headers.joinToString("\n") { "${it.name}: ${it.value}" }

    /** Lines without a colon, or with nothing before it, are dropped rather than guessed at. */
    fun parse(text: String): List<NameValuePair> = text.lineSequence()
        .map { it.trimEnd('\r') }
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = line.substring(0, colon).trim()
            if (name.isEmpty()) null else NameValuePair(name, line.substring(colon + 1).trim())
        }
        .toList()

    fun parseLines(text: String): List<HeaderLine> = parse(text).map { HeaderLine(it.name, it.value) }
}
