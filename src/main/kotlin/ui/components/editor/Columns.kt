package org.bittrace.ui.components.editor

/**
 * Display columns: where a char sits on the editor's monospace grid.
 *
 * The grid is what lets the view lay out only what is on screen. With every
 * char a known number of cells wide, the x of any offset is arithmetic, so a
 * five-megabyte line can be drawn from the middle without measuring anything
 * before it. Most lines are ASCII with no tabs, where a column *is* an offset;
 * the rest get a column table, cached per line.
 *
 * Tabs advance to the next multiple of [TAB_SIZE]. East Asian wide chars and
 * emoji take two cells, combining marks none.
 */
object Columns {
    const val TAB_SIZE = 4

    /** Past this length a line is treated as one cell per char, whatever it holds. */
    private const val MAPPED_LIMIT = 65_536

    private val cache = Lru<String, IntArray>(512)

    /** Cells taken by [c] (a high surrogate stands for its whole pair). */
    fun cellWidth(c: Char, next: Char = ' '): Int {
        val code = c.code
        if (code < 0x300) return 1
        if (c.isLowSurrogate()) return 0
        if (c.isHighSurrogate()) {
            val cp = Character.toCodePoint(c, next)
            return if (isWideCodePoint(cp)) 2 else 1
        }
        val type = Character.getType(c).toByte()
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || code == 0x200B || code == 0x200D) return 0
        return if (isWideCodePoint(code)) 2 else 1
    }

    private fun isWideCodePoint(cp: Int): Boolean =
        (cp in 0x1100..0x115F) || (cp in 0x2E80..0x303E) || (cp in 0x3041..0x33FF) ||
            (cp in 0x3400..0x4DBF) || (cp in 0x4E00..0x9FFF) || (cp in 0xA000..0xA4CF) ||
            (cp in 0xAC00..0xD7A3) || (cp in 0xF900..0xFAFF) || (cp in 0xFE30..0xFE4F) ||
            (cp in 0xFF00..0xFF60) || (cp in 0xFFE0..0xFFE6) || (cp in 0x1F300..0x1F64F) ||
            (cp in 0x1F900..0x1F9FF) || (cp in 0x20000..0x3FFFD)

    private fun isSimple(line: String): Boolean {
        if (line.length > MAPPED_LIMIT) return true
        for (c in line) if (c == '\t' || c.code >= 0x300) return false
        return true
    }

    /** `table[i]` is the column of offset `i`, for `i in 0..length`; null when column == offset. */
    private fun table(line: String): IntArray? {
        if (isSimple(line)) return null
        cache[line]?.let { return it }
        val cols = IntArray(line.length + 1)
        var col = 0
        for (i in line.indices) {
            cols[i] = col
            val c = line[i]
            col += if (c == '\t') TAB_SIZE - (col % TAB_SIZE) else cellWidth(c, line.getOrElse(i + 1) { ' ' })
        }
        cols[line.length] = col
        cache[line] = cols
        return cols
    }

    fun columnOf(line: String, offset: Int): Int {
        val o = offset.coerceIn(0, line.length)
        val t = table(line) ?: return o
        return t[o]
    }

    fun width(line: String): Int = columnOf(line, line.length)

    /** [width] without caching a table — for scanning a whole document once. */
    fun measure(line: String): Int {
        if (isSimple(line)) return line.length
        var col = 0
        for (i in line.indices) {
            val c = line[i]
            col += if (c == '\t') TAB_SIZE - (col % TAB_SIZE) else cellWidth(c, line.getOrElse(i + 1) { ' ' })
        }
        return col
    }

    /** The offset nearest column [col], never inside a surrogate pair. */
    fun offsetAt(line: String, col: Int): Int {
        val t = table(line)
        if (t == null) return col.coerceIn(0, line.length)
        if (col <= 0) return 0
        if (col >= t[line.length]) return line.length
        var lo = 0
        var hi = line.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (t[mid] <= col) lo = mid else hi = mid - 1
        }
        // Nearer to the next boundary than this one?
        if (lo < line.length && col - t[lo] > (t[lo + 1] - t[lo]) / 2) lo++
        while (lo < line.length && line[lo].isLowSurrogate()) lo++
        return lo
    }

    /**
     * The text of `line[from, to)` as it is drawn — tabs expanded to spaces —
     * and, in [map], the index in that text of each offset `from..to`.
     */
    fun display(line: String, from: Int, to: Int, map: IntArray?): String {
        val t = table(line)
        if (t == null || line.indexOf('\t', from).let { it < 0 || it >= to }) {
            if (map != null) for (i in 0..(to - from)) map[i] = i
            return line.substring(from, to)
        }
        val sb = StringBuilder(to - from + 16)
        for (i in from until to) {
            map?.set(i - from, sb.length)
            val c = line[i]
            if (c == '\t') {
                repeat(t[i + 1] - t[i]) { sb.append(' ') }
            } else {
                sb.append(c)
            }
        }
        map?.set(to - from, sb.length)
        return sb.toString()
    }
}

/** The editor's grid, in pixels: one column's width, one row's height, and where text sits in a row. */
class FontMetrics(val charWidth: Float, val lineHeight: Float, val textTop: Float)
