package org.bittrace.ui.components.editor

import org.bittrace.plugin.format.TokenKind

/** Receives one classified run of a line. Offsets are into the line. */
fun interface TokenSink {
    fun token(start: Int, end: Int, kind: TokenKind)
}

/** Where a [Tokenizer] stopped, and in what state. Reused across calls to spare an allocation per line. */
class TokenRun {
    var pos: Int = 0
    var state: Int = 0
}

/**
 * A lexer that can be stopped and resumed.
 *
 * Everything the editor knows about colour comes through this. It is handed a
 * whole line, a place to start, and a state saying what construct that place
 * is inside — a block comment, a tag, a CSS declaration — and it classifies
 * forwards from there. The state is one `Int`, which is what lets the
 * highlighter keep one per line for a document of a million lines, and compare
 * two of them to know that an edit has stopped mattering.
 *
 * It runs until the first token boundary at or past `minEnd` (or the end of the
 * line), so a very long line can be lexed a chunk at a time: the state at each
 * stop is enough to carry on from it. A token is never split by a stop, so a
 * resumed run classifies exactly as an uninterrupted one would.
 *
 * Lexical and total, like the formatters' lexers this grew from: malformed
 * input yields fewer or plainer tokens, never an exception.
 */
interface Tokenizer {
    /** The state at the top of a document. */
    val initialState: Int get() = 0

    /**
     * Classifies `line` from [start] to the first token boundary at or past
     * [minEnd]. Reports tokens to [sink] when it is not null — a run that only
     * wants the state passes null — and leaves the stop and the state in [run].
     */
    fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun)

    /** The state carried over a line break: whatever only lasts to the end of a line ends here. */
    fun lineEnd(state: Int): Int = state
}

/** How a language's regions fold. */
enum class FoldStyle { NONE, BRACKETS, TAGS }

/**
 * A language the editor can show.
 *
 * Immutable and shared: one instance per language for the life of the process,
 * built by [EditorEngine]. Everything that varies per editor — the states, the
 * tokens on screen — lives in that editor's highlighter.
 */
class EditorLanguage(
    val id: String,
    val tokenizer: Tokenizer,
    /** Pairs of open/close characters, `"()[]{}"`; empty when brackets mean nothing here. */
    val brackets: String = "",
    val foldStyle: FoldStyle = FoldStyle.NONE,
) {
    fun isOpenBracket(c: Char): Boolean {
        val i = brackets.indexOf(c)
        return i >= 0 && i % 2 == 0
    }

    fun isCloseBracket(c: Char): Boolean {
        val i = brackets.indexOf(c)
        return i >= 0 && i % 2 == 1
    }

    /** The other half of the bracket [c]. */
    fun partner(c: Char): Char {
        val i = brackets.indexOf(c)
        return if (i % 2 == 0) brackets[i + 1] else brackets[i - 1]
    }

    override fun toString(): String = "EditorLanguage($id)"
}

// --- shared scanning, over a line ---

internal const val SCRIPT_PUNCT = "{}[]()<>,;:.=+-*/%!?&|^~"

/** Index just past the quoted run at [start] (its opening quote), stopping at the end of the line. */
internal fun quotedEnd(text: String, start: Int, quote: Char = text[start]): Int {
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            else -> i++
        }
    }
    return text.length
}

internal fun numberEnd(text: String, start: Int): Int {
    var i = if (text[start] == '-') start + 1 else start
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '+' ||
            (text[i] == '-' && text[i - 1].lowercaseChar() == 'e'))
    ) i++
    return i
}

internal fun wordEnd(text: String, start: Int, extra: String = "_$"): Int {
    var i = start
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] in extra)) i++
    return i
}

/** First index at or after [from] that is not whitespace, or -1. */
internal fun nextNonSpace(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        if (!text[i].isWhitespace()) return i
        i++
    }
    return -1
}

internal fun TokenSink?.emit(start: Int, end: Int, kind: TokenKind) {
    if (this != null && end > start && kind != TokenKind.PLAIN) token(start, end, kind)
}
