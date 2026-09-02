package org.bittrace.plugin.builtin

/**
 * Dependency-free pretty printers shared by the bundled body formatters.
 *
 * These are deliberately lexical, not parsers: a body captured off the wire is
 * often truncated or malformed, so the goal is "readable, never throws" rather
 * than a faithful AST round-trip.
 */

// --- brace-driven code (JS / TS / GraphQL) ---

/** Style knobs for [indentCode]. */
internal class CodeStyle(
    /** Comment openers that run to the end of the line. */
    val lineComments: List<String> = listOf("//"),
    /** Whether C-style block comments are recognised. */
    val blockComments: Boolean = true,
    /** Characters that open a string literal. */
    val quotes: String = "\"'`",
    /** Break the line after `;`. */
    val breakOnSemicolon: Boolean = true,
    /** Break at every whitespace run inside `{}` — one field per line (GraphQL). */
    val breakInsideBraces: Boolean = false,
)

/**
 * Re-indents [src] from its bracket structure: `{`/`[` open a level, `}`/`]`
 * close one, and `;`/`,` end a line. Strings and comments are copied through
 * untouched, and unbalanced brackets degrade to a flat-ish listing instead of
 * failing.
 */
internal fun indentCode(src: String, style: CodeStyle): String {
    val out = StringBuilder()
    // Open brackets plus the output length right after each was written, so an
    // empty block can be collapsed back onto one line.
    val open = ArrayDeque<Pair<Char, Int>>()
    var indent = 0
    var pendingBreak = false
    var pendingSpace = false

    fun flush() {
        if (out.isEmpty()) {
            pendingBreak = false; pendingSpace = false; return
        }
        if (pendingBreak) out.append('\n').append("  ".repeat(indent)) else if (pendingSpace) out.append(' ')
        pendingBreak = false; pendingSpace = false
    }

    fun emit(text: String) {
        flush(); out.append(text)
    }

    /** Emits a token that hangs off the previous one (`)`, `;`, `,`). */
    fun emitAttached(text: String) {
        pendingBreak = false; pendingSpace = false; out.append(text)
    }

    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '\r' -> i++

            c.isWhitespace() -> {
                if (style.breakInsideBraces && open.lastOrNull()?.first == '{') pendingBreak = true
                else if (out.isNotEmpty()) pendingSpace = true
                i++
            }

            style.lineComments.any { src.startsWith(it, i) } -> {
                val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                emit(src.substring(i, end).trimEnd())
                pendingBreak = true
                i = end
            }

            style.blockComments && src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                emit(src.substring(i, end))
                pendingBreak = true
                i = end
            }

            c in style.quotes -> {
                val end = scanString(src, i)
                emit(src.substring(i, end))
                i = end
            }

            c == '{' || c == '[' -> {
                emit(c.toString())
                open.addLast(c to out.length)
                indent++
                pendingBreak = true
                i++
            }

            // Argument lists stay on one line — breaking them hurts more than it helps.
            c == '(' -> {
                emit("(")
                open.addLast('(' to out.length)
                i++
            }

            c == '}' || c == ']' || c == ')' -> {
                val opened = open.removeLastOrNull()
                if (c != ')') {
                    indent = (indent - 1).coerceAtLeast(0)
                    pendingBreak = true
                }
                if (opened != null && opened.second == out.length) {
                    // Nothing was written since the opener: keep `{}` / `()` tight.
                    pendingBreak = false; pendingSpace = false
                }
                if (c == ')') emitAttached(")") else emit(c.toString())
                if (c != ')') pendingBreak = true
                i++
            }

            c == ';' -> {
                emitAttached(";")
                if (style.breakOnSemicolon) pendingBreak = true
                i++
            }

            c == ',' -> {
                emitAttached(",")
                if (open.lastOrNull()?.first == '(') pendingSpace = true else pendingBreak = true
                i++
            }

            else -> {
                emit(c.toString())
                i++
            }
        }
    }
    return out.toString().trim()
}

/** Returns the index just past the string literal starting at [start]. */
private fun scanString(src: String, start: Int): Int {
    val quote = src[start]
    var i = start + 1
    while (i < src.length) {
        when {
            src[i] == '\\' -> i += 2
            src[i] == quote -> return i + 1
            // Unterminated quote: don't swallow the rest of the body.
            src[i] == '\n' && quote != '`' -> return i
            else -> i++
        }
    }
    return src.length
}

// --- markup (XML / HTML) ---

private enum class Kind { OPEN, CLOSE, STANDALONE, TEXT }

private class Token(val kind: Kind, val text: String, val name: String = "")

/**
 * Re-indents markup one node per line. [voidTags] never open a level (HTML's
 * `<br>`, `<img>`, …) and the content of [rawTextTags] (`<script>`, `<style>`)
 * is passed through as text rather than parsed as markup.
 */
internal fun formatMarkup(
    src: String,
    voidTags: Set<String> = emptySet(),
    rawTextTags: Set<String> = emptySet(),
): String {
    val tokens = tokenizeMarkup(src, voidTags, rawTextTags)
    val out = StringBuilder()
    var indent = 0
    var i = 0

    fun line(text: String) {
        if (out.isNotEmpty()) out.append('\n')
        out.append("  ".repeat(indent)).append(text)
    }

    while (i < tokens.size) {
        val t = tokens[i]
        when (t.kind) {
            Kind.CLOSE -> {
                indent = (indent - 1).coerceAtLeast(0)
                line(t.text)
                i++
            }

            Kind.OPEN -> {
                // `<td>value</td>` reads better on one line than on three.
                val text = tokens.getOrNull(i + 1)
                val close = tokens.getOrNull(i + 2)
                val inline = text?.kind == Kind.TEXT && close?.kind == Kind.CLOSE &&
                    close.name == t.name && text.text.length <= 80
                if (inline) {
                    line(t.text + text.text + close.text)
                    i += 3
                } else {
                    line(t.text)
                    indent++
                    i++
                }
            }

            else -> {
                line(t.text)
                i++
            }
        }
    }
    return out.toString()
}

private fun tokenizeMarkup(src: String, voidTags: Set<String>, rawTextTags: Set<String>): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0

    fun addText(raw: String) {
        val text = raw.replace(WHITESPACE, " ").trim()
        if (text.isNotEmpty()) tokens += Token(Kind.TEXT, text)
    }

    while (i < src.length) {
        if (src[i] != '<') {
            val end = src.indexOf('<', i).let { if (it < 0) src.length else it }
            addText(src.substring(i, end))
            i = end
            continue
        }
        // Constructs that end on a fixed delimiter and never nest.
        val fixed = when {
            src.startsWith("<!--", i) -> "-->"
            src.startsWith("<![CDATA[", i) -> "]]>"
            src.startsWith("<?", i) -> "?>"
            else -> null
        }
        if (fixed != null) {
            val end = src.indexOf(fixed, i).let { if (it < 0) src.length else it + fixed.length }
            tokens += Token(Kind.STANDALONE, src.substring(i, end))
            i = end
            continue
        }

        val end = scanTagEnd(src, i)
        val text = src.substring(i, end)
        i = end

        when {
            // Doctype and other declarations.
            text.startsWith("<!") -> tokens += Token(Kind.STANDALONE, text)

            text.startsWith("</") -> tokens += Token(Kind.CLOSE, text, tagName(text))

            else -> {
                val name = tagName(text)
                if (text.endsWith("/>") || name in voidTags) {
                    tokens += Token(Kind.STANDALONE, text, name)
                } else {
                    tokens += Token(Kind.OPEN, text, name)
                    if (name in rawTextTags) {
                        val closeAt = src.indexOf("</$name", i, ignoreCase = true).let { if (it < 0) src.length else it }
                        addText(src.substring(i, closeAt))
                        i = closeAt
                    }
                }
            }
        }
    }
    return tokens
}

/** Returns the index just past the `>` of the tag starting at [start], ignoring
 *  `>` inside quoted attribute values. */
private fun scanTagEnd(src: String, start: Int): Int {
    var i = start + 1
    var quote = ' '
    while (i < src.length) {
        val c = src[i]
        when {
            quote != ' ' -> if (c == quote) quote = ' '
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return i + 1
        }
        i++
    }
    return src.length
}

private fun tagName(tag: String): String =
    tag.trimStart('<', '/').takeWhile { !it.isWhitespace() && it != '>' && it != '/' }.lowercase()

private val WHITESPACE = Regex("\\s+")

// --- shared bits ---

/** Decodes [bytes] as UTF-8, or null when they look binary (contain NULs). */
internal fun textOrNull(bytes: ByteArray): String? {
    if (bytes.any { it.toInt() == 0 }) return null
    return String(bytes, Charsets.UTF_8)
}
