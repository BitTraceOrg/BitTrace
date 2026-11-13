package org.bittrace.ui.components.editor

import org.bittrace.plugin.format.TokenKind

/** The bracket at [at] and its partner at [other], or `other == -1` when it has none. */
class BracketMatch(val at: Int, val other: Int) {
    val matched: Boolean get() = other >= 0
}

/**
 * Bracket matching and fold ranges, read off the highlighter's tokens.
 *
 * Tokens rather than characters, because a `}` inside a string or a comment is
 * not a bracket: only single-character punctuation tokens count, so the scan
 * skips exactly what the colouring already says is not code.
 */
internal class Structure(private val doc: TextDocument, private val highlighter: Highlighter) {

    private val language: EditorLanguage get() = highlighter.language

    /** The bracket beside [caret] and its partner — the one before the caret first, as CodeMirror does. */
    fun matchBracket(caret: Int): BracketMatch? {
        if (language.brackets.isEmpty() || doc.length == 0) return null
        for (pos in intArrayOf(caret - 1, caret)) {
            if (pos < 0 || pos >= doc.length) continue
            val c = doc.charAt(pos)
            if (c !in language.brackets) continue
            val line = doc.lineOf(pos)
            val col = pos - doc.lineStart(line)
            highlighter.ensureExact(line)
            if (highlighter.kindAt(line, col) != TokenKind.PUNCTUATION) continue
            return if (language.isOpenBracket(c)) scanForward(pos, c) else scanBackward(pos, c)
        }
        return null
    }

    private fun scanForward(pos: Int, open: Char): BracketMatch {
        val close = language.partner(open)
        var depth = 0
        var budget = SCAN_CHARS
        var line = doc.lineOf(pos)
        var fromCol = pos - doc.lineStart(line) + 1
        while (line < doc.lineCount && budget > 0) {
            highlighter.ensureExact(line)
            val text = doc.line(line)
            val start = doc.lineStart(line)
            var found = -1
            highlighter.forEachToken(line, fromCol, text.length) { s, e, k ->
                if (found < 0 && k == TokenKind.PUNCTUATION && e - s == 1 && s >= fromCol) {
                    when (text[s]) {
                        open -> depth++
                        close -> if (depth == 0) found = start + s else depth--
                    }
                }
            }
            if (found >= 0) return BracketMatch(pos, found)
            budget -= text.length + 1
            line++
            fromCol = 0
        }
        return BracketMatch(pos, -1)
    }

    private fun scanBackward(pos: Int, close: Char): BracketMatch {
        val open = language.partner(close)
        var depth = 0
        var budget = SCAN_CHARS
        var line = doc.lineOf(pos)
        var toCol = pos - doc.lineStart(line)
        val brackets = IntList()
        while (line >= 0 && budget > 0) {
            val text = doc.line(line)
            brackets.clear()
            highlighter.forEachToken(line, 0, toCol) { s, e, k ->
                if (k == TokenKind.PUNCTUATION && e - s == 1 && s < toCol && (text[s] == open || text[s] == close)) {
                    brackets.add(s)
                }
            }
            for (i in brackets.size - 1 downTo 0) {
                val s = brackets[i]
                if (text[s] == close) {
                    depth++
                } else if (depth == 0) {
                    return BracketMatch(pos, doc.lineStart(line) + s)
                } else {
                    depth--
                }
            }
            budget -= text.length + 1
            line--
            if (line >= 0) toCol = doc.line(line).length
        }
        return BracketMatch(pos, -1)
    }

    /** Whether [line] opens something that closes on a later line — what the gutter marks as foldable. */
    fun canFold(line: Int): Boolean {
        val text = doc.line(line)
        if (text.length > MAX_FOLD_LINE) return false
        // Asked for every line on screen, so it reads tokens as drawn — exact
        // or guessed — rather than forcing the highlighter forward.
        return when (language.foldStyle) {
            FoldStyle.NONE -> false
            FoldStyle.BRACKETS -> unclosedOpeners(line) > 0
            FoldStyle.TAGS -> TagWalker().also { w -> walkTags(line, w) }.let { it.net > 0 || it.pending }
        }
    }

    /**
     * The lines a fold at [line] would hide, `line + 1 .. last`, or null.
     *
     * The closing line stays visible, so a folded object reads `{ …` over its
     * `}` and the shape of the document is still there to see.
     */
    fun foldRange(line: Int): IntRange? {
        highlighter.ensureExact(line)
        if (!canFold(line)) return null
        val closer = when (language.foldStyle) {
            FoldStyle.NONE -> -1
            FoldStyle.BRACKETS -> bracketCloserLine(line)
            FoldStyle.TAGS -> tagCloserLine(line)
        }
        if (closer < 0 || closer - 1 < line + 1) return null
        return (line + 1)..(closer - 1)
    }

    private fun unclosedOpeners(line: Int): Int {
        val text = doc.line(line)
        var open = 0
        highlighter.forEachToken(line, 0, text.length) { s, e, k ->
            if (k == TokenKind.PUNCTUATION && e - s == 1) {
                val c = text[s]
                if (language.isOpenBracket(c)) open++ else if (language.isCloseBracket(c) && open > 0) open--
            }
        }
        return open
    }

    private fun bracketCloserLine(line: Int): Int {
        // Depth counted across every bracket kind. The fold belongs to the head
        // line's innermost opener, so it closes when the depth falls below what
        // the head line left open.
        val baseline = unclosedOpeners(line)
        var depth = baseline
        var l = line + 1
        var scanned = 0
        while (l < doc.lineCount && scanned < SCAN_LINES) {
            highlighter.ensureExact(l)
            val text = doc.line(l)
            var closed = false
            highlighter.forEachToken(l, 0, text.length) { s, e, k ->
                if (!closed && k == TokenKind.PUNCTUATION && e - s == 1) {
                    val c = text[s]
                    if (language.isOpenBracket(c)) {
                        depth++
                    } else if (language.isCloseBracket(c)) {
                        depth--
                        if (depth < baseline) closed = true
                    }
                }
            }
            if (closed) return l
            l++
            scanned++
        }
        return -1
    }

    private fun tagCloserLine(line: Int): Int {
        val walker = TagWalker()
        walkTags(line, walker)
        // A tag still open at the end of the head line (attributes continue
        // below) is the innermost opener; its `>` arrives later and counts then.
        var depth = walker.net
        val target = walker.net + (if (walker.pending) 1 else 0) - 1
        var stop = false
        walker.onOpen = { depth++ }
        walker.onClose = {
            depth--
            if (depth <= target) stop = true
        }
        var l = line + 1
        var scanned = 0
        while (l < doc.lineCount && scanned < SCAN_LINES) {
            highlighter.ensureExact(l)
            walkTags(l, walker) { stop }
            if (stop) return l
            l++
            scanned++
        }
        return -1
    }

    private fun walkTags(line: Int, walker: TagWalker, stopped: () -> Boolean = { false }) {
        val text = doc.line(line)
        walker.text = text
        highlighter.forEachToken(line, 0, text.length) { s, e, k -> if (!stopped()) walker.token(s, e, k) }
    }

    /**
     * Opening and closing tags out of a token stream. An opening tag counts
     * once its `>` arrives, and not at all if it turns out to be `/>` or a
     * void HTML element. [pending] carries an unfinished tag across lines.
     */
    private inner class TagWalker {
        var text: String = ""
        var net = 0
        var pending = false
        var onOpen: () -> Unit = {}
        var onClose: () -> Unit = {}
        private var lastSlash = false

        fun token(s: Int, e: Int, k: TokenKind) {
            when (k) {
                TokenKind.TAG -> {
                    val closing = s >= 2 && text[s - 1] == '/' && text[s - 2] == '<'
                    if (closing) {
                        net--
                        pending = false
                        onClose()
                    } else {
                        val name = text.substring(s, e).lowercase()
                        pending = !(language.id == "html" && name in VOID_ELEMENTS)
                    }
                    lastSlash = false
                }

                TokenKind.PUNCTUATION -> {
                    when (text[s]) {
                        '/' -> lastSlash = true
                        '>' -> {
                            if (pending && !lastSlash) {
                                net++
                                onOpen()
                            }
                            pending = false
                            lastSlash = false
                        }

                        else -> lastSlash = false
                    }
                }

                else -> lastSlash = false
            }
        }
    }

    private companion object {
        const val SCAN_CHARS = 200_000
        const val SCAN_LINES = 100_000
        const val MAX_FOLD_LINE = 16_384
        val VOID_ELEMENTS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
        )
    }
}

/**
 * Folded regions, and the mapping between document lines and visual rows.
 *
 * A fold is a head line, which stays visible, and the run of lines under it
 * that is hidden. Folds never overlap: folding a region that contains folds
 * absorbs them. Rows are found by binary search over the folds, with a
 * running count of what each one hides, so a million-line document with a
 * handful of folds maps a row in a few comparisons.
 */
class FoldModel {
    private var heads = IntArray(0)
    private var lasts = IntArray(0)

    /** `hiddenBefore[i]` lines are hidden by folds `0 until i`. */
    private var hiddenBefore = IntArray(1)

    var revision: Int = 0
        private set

    val count: Int get() = heads.size
    val totalHidden: Int get() = hiddenBefore[heads.size]

    fun rowCount(lineCount: Int): Int = lineCount - totalHidden

    fun isFolded(head: Int): Boolean = heads.binarySearch(head) >= 0

    fun isHidden(line: Int): Boolean {
        val i = foldBefore(line)
        return i >= 0 && line <= lasts[i] && line > heads[i]
    }

    fun foldRanges(): List<IntRange> = heads.indices.map { (heads[it] + 1)..lasts[it] }

    /** The document line shown on visual row [row]. */
    fun lineOfRow(row: Int): Int {
        // The last fold whose head is at or before this row.
        var lo = 0
        var hi = heads.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val headRow = heads[mid] - hiddenBefore[mid]
            if (headRow <= row) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return row
        val headRow = heads[found] - hiddenBefore[found]
        return if (row == headRow) heads[found] else row + hiddenBefore[found + 1]
    }

    /** The visual row showing [line]; a hidden line maps to its fold's head. */
    fun rowOfLine(line: Int): Int {
        val i = foldBefore(line)
        if (i < 0) return line
        if (line <= lasts[i]) return heads[i] - hiddenBefore[i]
        return line - hiddenBefore[i + 1]
    }

    /** The last fold whose head is at or before [line], or -1. */
    private fun foldBefore(line: Int): Int {
        var lo = 0
        var hi = heads.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (heads[mid] <= line) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** Hides `head + 1 .. last`. */
    fun fold(head: Int, last: Int) {
        if (last <= head) return
        val keepH = IntList()
        val keepL = IntList()
        var inserted = false
        for (i in heads.indices) {
            // Absorbed: inside the new fold.
            if (heads[i] > head && lasts[i] <= last) continue
            if (heads[i] == head) continue
            if (!inserted && heads[i] > head) {
                keepH.add(head)
                keepL.add(last)
                inserted = true
            }
            keepH.add(heads[i])
            keepL.add(lasts[i])
        }
        if (!inserted) {
            keepH.add(head)
            keepL.add(last)
        }
        set(keepH.toArray(), keepL.toArray())
    }

    fun unfold(head: Int): Boolean {
        val i = heads.binarySearch(head)
        if (i < 0) return false
        set(heads.removeAt(i), lasts.removeAt(i))
        return true
    }

    /** Unfolds whatever hides [line]; returns whether anything changed. */
    fun reveal(line: Int): Boolean {
        var changed = false
        while (isHidden(line)) {
            val i = foldBefore(line)
            unfold(heads[i])
            changed = true
        }
        return changed
    }

    fun clear() {
        if (heads.isEmpty()) return
        set(IntArray(0), IntArray(0))
    }

    /** Drops folds the edit touched and moves those after it. */
    fun onChange(change: Change) {
        if (heads.isEmpty()) return
        val first = change.firstLine
        val oldLast = change.oldLastLine
        val delta = change.lineDelta
        val keepH = IntList()
        val keepL = IntList()
        for (i in heads.indices) {
            val h = heads[i]
            val l = lasts[i]
            when {
                l < first -> {
                    keepH.add(h)
                    keepL.add(l)
                }

                h > oldLast -> {
                    keepH.add(h + delta)
                    keepL.add(l + delta)
                }
                // Otherwise the edit touched it: the region may no longer be one.
            }
        }
        set(keepH.toArray(), keepL.toArray())
    }

    private fun set(h: IntArray, l: IntArray) {
        heads = h
        lasts = l
        hiddenBefore = IntArray(h.size + 1)
        for (i in h.indices) hiddenBefore[i + 1] = hiddenBefore[i] + (l[i] - h[i])
        revision++
    }

    private fun IntArray.removeAt(i: Int): IntArray = IntArray(size - 1) { if (it < i) this[it] else this[it + 1] }
}
