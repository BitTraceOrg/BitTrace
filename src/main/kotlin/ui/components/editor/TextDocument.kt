package org.bittrace.ui.components.editor

/**
 * The editor's text, held as a list of lines.
 *
 * A list rather than one string because every question the view asks is about
 * lines — which ones are on screen, how long each is, where a click landed —
 * and because an edit then touches the one or two lines it changed rather than
 * copying the whole document. A body of ten megabytes is a few hundred thousand
 * short strings, and typing into it replaces one of them.
 *
 * Line breaks are normalised on the way in: `\r\n`, `\r` and `\n` all split a
 * line, and [text] joins with `\n`. That is what CodeMirror did before this, so
 * what an edited body sends has not changed.
 *
 * Offsets are UTF-16 indices into [text], line breaks counting one each.
 */
class TextDocument(initial: String = "") {

    private val lines = ArrayList<String>()

    /** `lineStarts[i]` is the offset of line `i`; valid below [startsValid]. */
    private var lineStarts = IntArray(16)
    private var startsValid = 0

    private var cachedText: String? = null

    /** Bumped on every change, so anything derived from the text can tell it is stale. */
    var version: Long = 0
        private set

    /** Total length in chars, line breaks included. */
    var length: Int = 0
        private set

    init {
        load(initial)
    }

    val lineCount: Int get() = lines.size

    fun line(index: Int): String = lines[index]

    /** Replaces the whole document. */
    fun setText(text: String) {
        lines.clear()
        load(text)
        version++
    }

    private fun load(text: String) {
        splitLinesInto(text, lines)
        length = lines.sumOf { it.length } + lines.size - 1
        startsValid = 0
        // A string with no carriage returns is already what `text()` would
        // build, so a viewer never pays to join the body it was handed.
        cachedText = if (text.indexOf('\r') < 0) text else null
    }

    /** The whole document, joined with `\n`. Cached until the next edit. */
    fun text(): String {
        cachedText?.let { return it }
        val sb = StringBuilder(length)
        for (i in lines.indices) {
            if (i > 0) sb.append('\n')
            sb.append(lines[i])
        }
        return sb.toString().also { cachedText = it }
    }

    fun lineStart(index: Int): Int {
        ensureStarts(index)
        return lineStarts[index]
    }

    fun lineEnd(index: Int): Int = lineStart(index) + lines[index].length

    /** The line holding [offset]; an offset on a line break belongs to the line it ends. */
    fun lineOf(offset: Int): Int {
        val o = offset.coerceIn(0, length)
        ensureStarts(lines.size - 1)
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= o) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun columnOf(offset: Int): Int = offset - lineStart(lineOf(offset))

    fun offsetOf(line: Int, column: Int): Int {
        val l = line.coerceIn(0, lines.size - 1)
        return lineStart(l) + column.coerceIn(0, lines[l].length)
    }

    fun charAt(offset: Int): Char {
        val l = lineOf(offset)
        val col = offset - lineStart(l)
        val s = lines[l]
        return if (col < s.length) s[col] else '\n'
    }

    fun substring(from: Int, to: Int): String {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(a, length)
        if (a == b) return ""
        cachedText?.let { return it.substring(a, b) }
        val first = lineOf(a)
        val last = lineOf(b)
        if (first == last) {
            val start = lineStart(first)
            return lines[first].substring(a - start, b - start)
        }
        val sb = StringBuilder(b - a)
        sb.append(lines[first], a - lineStart(first), lines[first].length)
        for (i in first + 1 until last) {
            sb.append('\n').append(lines[i])
        }
        sb.append('\n').append(lines[last], 0, b - lineStart(last))
        return sb.toString()
    }

    /**
     * Replaces `[from, to)` with [insert] and says what happened.
     *
     * Only the lines the range spans are rebuilt; the line-start table is
     * recomputed lazily from the first one that moved.
     */
    fun replace(from: Int, to: Int, insert: String): Change {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(a, length)
        val text = normalizeBreaks(insert)
        val removed = substring(a, b)
        val firstLine = lineOf(a)
        val lastLine = lineOf(b)
        val prefix = lines[firstLine].substring(0, a - lineStart(firstLine))
        val suffix = lines[lastLine].substring(b - lineStart(lastLine))

        val replacement = ArrayList<String>()
        splitLinesInto(prefix + text + suffix, replacement)
        val span = lines.subList(firstLine, lastLine + 1)
        span.clear()
        span.addAll(replacement)

        length += text.length - (b - a)
        startsValid = minOf(startsValid, firstLine + 1)
        cachedText = null
        version++
        return Change(
            from = a,
            removed = removed,
            inserted = text,
            firstLine = firstLine,
            oldLastLine = lastLine,
            newLastLine = firstLine + replacement.size - 1,
        )
    }

    private fun ensureStarts(upTo: Int) {
        if (upTo < startsValid) return
        if (lineStarts.size < lines.size) {
            lineStarts = lineStarts.copyOf(maxOf(lines.size, lineStarts.size * 2))
        }
        var i = startsValid
        if (i == 0) {
            lineStarts[0] = 0
            i = 1
        }
        while (i <= upTo) {
            lineStarts[i] = lineStarts[i - 1] + lines[i - 1].length + 1
            i++
        }
        startsValid = upTo + 1
    }
}

/**
 * One edit, as the document applied it.
 *
 * Lines are reported in the document's numbering before ([oldLastLine]) and
 * after ([newLastLine]) the change, so anything keyed by line — highlight
 * states, folds — can shift what follows by the difference.
 */
class Change(
    val from: Int,
    val removed: String,
    val inserted: String,
    val firstLine: Int,
    val oldLastLine: Int,
    val newLastLine: Int,
) {
    val lineDelta: Int get() = newLastLine - oldLastLine
}

internal fun normalizeBreaks(text: String): String =
    if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

/** Splits on `\r\n`, `\r` and `\n`; never yields fewer than one line. */
internal fun splitLinesInto(text: String, out: MutableList<String>) {
    var start = 0
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c == '\n' || c == '\r') {
            out.add(text.substring(start, i))
            if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
            start = i + 1
        }
        i++
    }
    out.add(text.substring(start))
}
