package org.bittrace.plugin.builtin

import org.bittrace.ui.components.editor.GRAPHQL_KEYWORDS
import org.bittrace.plugin.format.Span
import org.bittrace.plugin.format.TokenKind

/**
 * Highlighting lexers for the bundled formatters. They run over *formatted*
 * output (the result of the pretty printers in `Pretty.kt`), which keeps
 * layout and colouring independent — and, like the printers, they are lexical
 * and total: malformed input yields fewer spans, never an exception.
 */

private class Spans {
    private val list = mutableListOf<Span>()

    fun add(start: Int, end: Int, kind: TokenKind) {
        if (end > start && kind != TokenKind.PLAIN) list += Span(start, end, kind)
    }

    fun build(): List<Span> = list
}

// --- JSON ---

internal fun highlightJson(text: String): List<Span> {
    val spans = Spans()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> {
                val end = stringEnd(text, i)
                // A string followed by ':' is an object key.
                val next = text.indexOfFirst(end) { !it.isWhitespace() }
                spans.add(i, end, if (next >= 0 && text[next] == ':') TokenKind.PROPERTY else TokenKind.STRING)
                i = end
            }

            c.isDigit() || (c == '-' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                val end = numberEnd(text, i)
                spans.add(i, end, TokenKind.NUMBER)
                i = end
            }

            c.isLetter() -> {
                val end = wordEnd(text, i)
                val word = text.substring(i, end)
                if (word == "true" || word == "false" || word == "null") spans.add(i, end, TokenKind.LITERAL)
                i = end
            }

            c in "{}[],:" -> {
                spans.add(i, i + 1, TokenKind.PUNCTUATION)
                i++
            }

            else -> i++
        }
    }
    return spans.build()
}

// --- JavaScript / TypeScript ---

private val JS_KEYWORDS = setOf(
    "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "export", "extends", "finally", "for", "from", "function",
    "get", "if", "import", "in", "instanceof", "let", "new", "of", "return", "set", "static",
    "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield",
)

private val TS_KEYWORDS = JS_KEYWORDS + setOf(
    "abstract", "any", "asserts", "boolean", "declare", "enum", "implements", "infer", "interface",
    "is", "keyof", "namespace", "never", "number", "override", "private", "protected", "public",
    "readonly", "satisfies", "string", "symbol", "type", "unknown", "unique",
)

private val JS_LITERALS = setOf("true", "false", "null", "undefined", "NaN", "Infinity")

internal fun jsKeywords(typescript: Boolean) = if (typescript) TS_KEYWORDS else JS_KEYWORDS

internal fun highlightScript(text: String, keywords: Set<String>): List<Span> {
    val spans = Spans()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("//", i) -> {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                spans.add(i, end, TokenKind.COMMENT)
                i = end
            }

            text.startsWith("/*", i) -> {
                val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                spans.add(i, end, TokenKind.COMMENT)
                i = end
            }

            c == '"' || c == '\'' || c == '`' -> {
                val end = stringEnd(text, i)
                spans.add(i, end, TokenKind.STRING)
                i = end
            }

            c.isDigit() -> {
                val end = numberEnd(text, i)
                spans.add(i, end, TokenKind.NUMBER)
                i = end
            }

            c.isLetter() || c == '_' || c == '$' -> {
                val end = wordEnd(text, i)
                val word = text.substring(i, end)
                val member = i > 0 && text[i - 1] == '.'
                val kind = when {
                    word in JS_LITERALS -> TokenKind.LITERAL
                    !member && word in keywords -> TokenKind.KEYWORD
                    member -> TokenKind.PROPERTY
                    // `Foo` in `new Foo()` / `x: Foo` — a useful signal in TS especially.
                    word.first().isUpperCase() -> TokenKind.TYPE
                    else -> TokenKind.PLAIN
                }
                spans.add(i, end, kind)
                i = end
            }

            c in PUNCT -> {
                spans.add(i, i + 1, TokenKind.PUNCTUATION)
                i++
            }

            else -> i++
        }
    }
    return spans.build()
}

// --- GraphQL ---

private val GRAPHQL_KEYWORDS = setOf(
    "query", "mutation", "subscription", "fragment", "on", "schema", "type", "input", "enum",
    "interface", "union", "scalar", "implements", "extend", "directive", "repeatable",
)

internal fun highlightGraphQl(text: String): List<Span> {
    val spans = Spans()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '#' -> {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                spans.add(i, end, TokenKind.COMMENT)
                i = end
            }

            c == '"' -> {
                val end = stringEnd(text, i)
                spans.add(i, end, TokenKind.STRING)
                i = end
            }

            c == '$' || c == '@' -> {
                val end = wordEnd(text, i + 1)
                spans.add(i, end, if (c == '$') TokenKind.VARIABLE else TokenKind.META)
                i = end
            }

            c.isDigit() || (c == '-' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                val end = numberEnd(text, i)
                spans.add(i, end, TokenKind.NUMBER)
                i = end
            }

            c.isLetter() || c == '_' -> {
                val end = wordEnd(text, i)
                val word = text.substring(i, end)
                val next = text.indexOfFirst(end) { !it.isWhitespace() }
                val kind = when {
                    word == "true" || word == "false" || word == "null" -> TokenKind.LITERAL
                    word in GRAPHQL_KEYWORDS -> TokenKind.KEYWORD
                    // `name:` is an argument name (or a field alias).
                    next >= 0 && text[next] == ':' -> TokenKind.ATTRIBUTE
                    else -> TokenKind.PROPERTY
                }
                spans.add(i, end, kind)
                i = end
            }

            c in PUNCT -> {
                spans.add(i, i + 1, TokenKind.PUNCTUATION)
                i++
            }

            else -> i++
        }
    }
    return spans.build()
}

// --- XML / HTML ---

internal fun highlightMarkup(text: String): List<Span> {
    val spans = Spans()
    var i = 0
    while (i < text.length) {
        if (text[i] != '<') {
            i++
            continue
        }
        // Comments, CDATA, processing instructions and doctypes colour as one unit.
        val fixed = when {
            text.startsWith("<!--", i) -> "-->" to TokenKind.COMMENT
            text.startsWith("<![CDATA[", i) -> "]]>" to TokenKind.META
            text.startsWith("<?", i) -> "?>" to TokenKind.META
            text.startsWith("<!", i) -> ">" to TokenKind.META
            else -> null
        }
        if (fixed != null) {
            val (terminator, kind) = fixed
            val end = text.indexOf(terminator, i).let { if (it < 0) text.length else it + terminator.length }
            spans.add(i, end, kind)
            i = end
            continue
        }

        val tagEnd = text.indexOf('>', i).let { if (it < 0) text.length else it + 1 }
        var j = i + 1
        spans.add(i, j, TokenKind.PUNCTUATION)
        if (j < tagEnd && text[j] == '/') {
            spans.add(j, j + 1, TokenKind.PUNCTUATION)
            j++
        }
        val nameEnd = wordEnd(text, j, extra = ":-")
        spans.add(j, nameEnd, TokenKind.TAG)
        j = nameEnd

        // Attributes: name [= value].
        while (j < tagEnd) {
            val c = text[j]
            when {
                c.isWhitespace() -> j++
                c == '"' || c == '\'' -> {
                    val end = minOf(stringEnd(text, j), tagEnd)
                    spans.add(j, end, TokenKind.STRING)
                    j = end
                }
                c == '=' || c == '/' || c == '>' -> {
                    spans.add(j, j + 1, TokenKind.PUNCTUATION)
                    j++
                }
                else -> {
                    val end = minOf(wordEnd(text, j, extra = ":-_"), tagEnd)
                    spans.add(j, end, TokenKind.ATTRIBUTE)
                    j = if (end > j) end else j + 1
                }
            }
        }
        i = tagEnd
    }
    return spans.build()
}

// --- hex dump ---

/** Colours the offset column and the ASCII gutter of a [HexFormatter] dump. */
internal fun highlightHex(text: String): List<Span> {
    val spans = Spans()
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        spans.add(lineStart, minOf(lineStart + 8, lineEnd), TokenKind.META)
        val gutter = text.indexOf('|', lineStart)
        if (gutter in lineStart until lineEnd) spans.add(gutter, lineEnd, TokenKind.STRING)
        lineStart = lineEnd + 1
    }
    return spans.build()
}

// --- shared scanning ---

private const val PUNCT = "{}[]()<>,;:.=+-*/%!?&|^~"

/** Index just past the string literal at [start] (which is its opening quote). */
private fun stringEnd(text: String, start: Int): Int {
    val quote = text[start]
    var i = start + 1
    while (i < text.length) {
        when {
            text[i] == '\\' -> i += 2
            text[i] == quote -> return i + 1
            text[i] == '\n' && quote != '`' -> return i
            else -> i++
        }
    }
    return text.length
}

private fun numberEnd(text: String, start: Int): Int {
    var i = if (text[start] == '-') start + 1 else start
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '+' ||
            (text[i] == '-' && text[i - 1].lowercaseChar() == 'e'))
    ) i++
    return i
}

private fun wordEnd(text: String, start: Int, extra: String = "_$"): Int {
    var i = start
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] in extra)) i++
    return i
}

/** First index at or after [from] whose char satisfies [predicate], or -1. */
private fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return -1
}
