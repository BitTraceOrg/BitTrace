package org.bittrace.ui.components.editor

import org.bittrace.plugin.format.TokenKind

/**
 * Syntax state for one document, kept up to date incrementally.
 *
 * What it stores is small: the tokenizer's state at the start of every line,
 * an `Int` each, known exactly up to [frontier]. Tokens themselves are not
 * kept for the document — only for what has recently been drawn, in an LRU —
 * because re-lexing a visible line from its start state is cheap and holding
 * tokens for a ten-megabyte body is not.
 *
 * An edit moves the frontier back to the first line it touched. Lines past the
 * edit keep their old states as *candidates*: when re-lexing reaches one and
 * produces the state it already had, nothing further down can have changed, so
 * the frontier jumps straight past them. Typing a letter into the middle of a
 * large document therefore re-lexes one line, and opening a block comment re-lexes until
 * the comment closes.
 *
 * The frontier advances in [step], which the shared [HighlightScheduler] calls
 * between frames under a time budget, so a large body highlights without
 * holding up the UI. A line past the frontier is drawn from a guessed start
 * state — the language's initial one, which is right for almost every line of
 * almost every body — and redrawn if the real one turns out to differ.
 *
 * Lines longer than [CHUNK] are lexed a chunk at a time, and the state at each
 * chunk start is remembered, so drawing the middle of a five-megabyte line of
 * minified JSON lexes the chunks on screen rather than everything before them.
 */
class Highlighter(private val doc: TextDocument, language: EditorLanguage) {

    var language: EditorLanguage = language
        private set

    private var states = IntArray(64)

    /** Lines `0..frontier` have exact start states in [states]. */
    var frontier: Int = 0
        private set

    // Pre-edit states that are probably still right; see the class doc.
    private var candFrom = 1
    private var candTo = 0

    private val run = TokenRun()
    private val tokenCache = Lru<TokenKey, IntArray>(TOKEN_CACHE)
    private val chunkCache = Lru<TokenKey, ChunkTable>(CHUNK_TABLE_CACHE)

    /** Visible line range, so the frontier crossing it can ask for a redraw. */
    var visibleFirst = 0
    var visibleLast = -1

    /** Called when exact states arrive for lines currently on screen. */
    var onVisibleStatesChanged: () -> Unit = {}

    /** Bumped when the language changes, so cached derivations elsewhere know. */
    var revision: Int = 0
        private set

    init {
        reset()
    }

    val isComplete: Boolean get() = frontier >= doc.lineCount - 1

    fun setLanguage(language: EditorLanguage) {
        if (language === this.language) return
        this.language = language
        reset()
    }

    /** Forgets every state; used when the whole document or the language changes. */
    fun reset() {
        ensureCapacity(doc.lineCount + 1)
        states[0] = language.tokenizer.initialState
        frontier = 0
        candFrom = 1
        candTo = 0
        tokenCache.clear()
        chunkCache.clear()
        revision++
    }

    /** The state line [line] starts in: exact up to the frontier, a guess past it. */
    fun startState(line: Int): Int =
        if (line <= frontier) states[line] else language.tokenizer.initialState

    fun isExact(line: Int): Boolean = line <= frontier

    fun onChange(change: Change) {
        val first = change.firstLine
        val oldLast = change.oldLastLine
        val newLast = change.newLastLine
        val delta = change.lineDelta
        val oldFrontier = frontier
        val keepTo = maxOf(oldFrontier, candTo)

        ensureCapacity(doc.lineCount + 1)
        // Move the states of the lines after the edit to their new numbers.
        if (delta != 0 && keepTo > oldLast) {
            val count = keepTo - oldLast
            System.arraycopy(states, oldLast + 1, states, newLast + 1, minOf(count, states.size - (newLast + 1)))
        }

        if (oldFrontier > first) {
            frontier = first
            if (oldFrontier > oldLast) {
                candFrom = newLast + 1
                candTo = oldFrontier + delta
            } else {
                candFrom = 1
                candTo = 0
            }
        } else if (candTo >= candFrom) {
            when {
                candFrom > oldLast -> {
                    candFrom += delta
                    candTo += delta
                }

                candTo > oldLast -> {
                    candFrom = newLast + 1
                    candTo += delta
                }

                candTo >= first -> candTo = first
            }
        }
        candTo = minOf(candTo, doc.lineCount - 1)
        if (frontier > doc.lineCount - 1) frontier = doc.lineCount - 1
    }

    /**
     * Advances the frontier until [deadlineNanos] (a `System.nanoTime` value).
     * Returns whether there is still work to do.
     */
    fun step(deadlineNanos: Long, untilLine: Int = Int.MAX_VALUE): Boolean {
        val before = frontier
        val tokenizer = language.tokenizer
        val lastLine = minOf(doc.lineCount - 1, untilLine)
        var n = 0
        while (frontier < lastLine) {
            val lineIndex = frontier
            val text = doc.line(lineIndex)
            val end: Int
            if (text.length <= CHUNK) {
                tokenizer.tokenize(text, 0, text.length, states[lineIndex], null, run)
                end = tokenizer.lineEnd(run.state)
            } else {
                val table = chunkTable(text, states[lineIndex])
                while (!table.complete) {
                    table.extend(tokenizer, text, run)
                    if (System.nanoTime() > deadlineNanos) break
                }
                if (!table.complete) break
                end = tokenizer.lineEnd(table.endState)
            }
            val next = lineIndex + 1
            if (next in candFrom..candTo && states[next] == end) {
                frontier = candTo
                candFrom = 1
                candTo = 0
            } else {
                states[next] = end
                frontier = next
                if (next >= candFrom) candFrom = next + 1
            }
            if ((++n and 63) == 0 && System.nanoTime() > deadlineNanos) break
        }
        if (frontier != before && before <= visibleLast && frontier > visibleFirst) onVisibleStatesChanged()
        return frontier < doc.lineCount - 1
    }

    /**
     * Brings the exact states up to [line], however long that takes — for the
     * structural questions (where does this bracket close, what does this fold
     * cover) that a guessed state would answer wrongly.
     */
    fun ensureExact(line: Int) {
        if (line > frontier) step(Long.MAX_VALUE, untilLine = line)
    }

    /**
     * Reports the tokens of [line] that overlap `[from, to)`.
     *
     * Tokens can start before [from] — a string that began off screen — and
     * the caller clips. Offsets are within the line.
     */
    fun forEachToken(line: Int, from: Int, to: Int, sink: TokenSink) {
        val text = doc.line(line)
        if (text.isEmpty()) return
        val tokenizer = language.tokenizer
        val start = startState(line)
        if (text.length <= CHUNK) {
            emitOverlapping(tokensOf(text, 0, text.length, start), from, to, sink)
            return
        }
        val table = chunkTable(text, start)
        while (!table.complete && table.scannedTo < to) table.extend(tokenizer, text, run)
        var c = table.chunkAt(from)
        while (c < table.count && table.starts[c] < to) {
            val cs = table.starts[c]
            val ce = if (c + 1 < table.count) table.starts[c + 1] else table.scannedTo
            if (ce <= cs) break
            emitOverlapping(tokensOf(text, cs, ce, table.states[c]), from, to, sink)
            c++
        }
    }

    /** The kind of the token covering column [col] of [line], or PLAIN. */
    fun kindAt(line: Int, col: Int): TokenKind {
        var kind = TokenKind.PLAIN
        forEachToken(line, col, col + 1) { s, e, k -> if (col in s until e) kind = k }
        return kind
    }

    private fun tokensOf(text: String, start: Int, end: Int, state: Int): IntArray {
        val key = TokenKey(text, start, state)
        tokenCache[key]?.let { return it }
        val out = IntList()
        language.tokenizer.tokenize(text, start, end, state, { s, e, k ->
            out.add(s)
            out.add(e)
            out.add(k.ordinal)
        }, run)
        return out.toArray().also { tokenCache[key] = it }
    }

    private fun emitOverlapping(tokens: IntArray, from: Int, to: Int, sink: TokenSink) {
        var i = 0
        while (i < tokens.size) {
            val s = tokens[i]
            val e = tokens[i + 1]
            if (s >= to) return
            if (e > from) sink.token(s, e, KINDS[tokens[i + 2]])
            i += 3
        }
    }

    private fun chunkTable(text: String, state: Int): ChunkTable {
        val key = TokenKey(text, 0, state)
        return chunkCache[key] ?: ChunkTable(state).also { chunkCache[key] = it }
    }

    private fun ensureCapacity(size: Int) {
        if (states.size < size) states = states.copyOf(maxOf(size, states.size * 2))
    }

    /** Where each chunk of one long line starts, and in what state. */
    private class ChunkTable(startState: Int) {
        var starts = IntArray(8)
        var states = IntArray(8)
        var count = 1
        var scannedTo = 0
        var complete = false
        var endState = startState

        init {
            states[0] = startState
        }

        fun extend(tokenizer: Tokenizer, text: String, run: TokenRun) {
            if (complete) return
            tokenizer.tokenize(text, scannedTo, scannedTo + CHUNK, endState, null, run)
            scannedTo = run.pos
            endState = run.state
            if (scannedTo >= text.length) {
                complete = true
                return
            }
            if (count == starts.size) {
                starts = starts.copyOf(count * 2)
                states = states.copyOf(count * 2)
            }
            starts[count] = scannedTo
            states[count] = endState
            count++
        }

        /** The chunk holding offset [pos]. */
        fun chunkAt(pos: Int): Int {
            var lo = 0
            var hi = count - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    private data class TokenKey(val text: String, val start: Int, val state: Int)

    companion object {
        /** Lines longer than this are lexed, and cached, a chunk at a time. */
        const val CHUNK = 2048
        private const val TOKEN_CACHE = 4096
        private const val CHUNK_TABLE_CACHE = 64
        private val KINDS = TokenKind.entries.toTypedArray()
    }
}

/** A growable `IntArray`. */
internal class IntList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    fun clear() {
        size = 0
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/** A small access-ordered LRU. */
internal class Lru<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    fun clear() = map.clear()
}
