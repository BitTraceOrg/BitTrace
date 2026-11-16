package org.bittrace.ui.components.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor

/**
 * One editor: its text, caret, history, highlighting and folds.
 *
 * Built once per component and kept for that component's life. Everything that
 * used to need a rebuild is a property here instead — [language], [readOnly]
 * and the text itself all change in place, so switching a body from JSON to
 * XML keeps its undo history, and a theme switch does not touch this at all
 * (colours are looked up when drawing).
 *
 * The commands are plain methods with no UI in them, which is what the tests
 * drive; the surface maps keys and clicks onto them. Every command that would
 * change the text does nothing while [readOnly] is set.
 *
 * Redraws are signalled through [redraw], which only the draw phase reads, so
 * moving the caret or typing a letter repaints the editor without recomposing
 * anything.
 */
class CodeEditorState(
    text: String = "",
    language: EditorLanguage = EditorEngine.plain,
    readOnly: Boolean = false,
    /** Pass one instance to several editors to scroll them together. */
    val scroll: EditorScrollState = EditorScrollState(),
) {
    val document = TextDocument(text)
    val history = EditHistory()
    val highlighter = Highlighter(document, language)
    internal val structure = Structure(document, highlighter)
    val folds = FoldModel()
    val find = FindModel()

    var readOnly by mutableStateOf(readOnly)

    /** Off for views whose rows must stay aligned with something else — the diff. */
    var foldingEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) folds.clear()
        }

    var selection: Selection = Selection(0)
        private set

    /** The column vertical movement aims for, kept across short lines. */
    private var goalColumn = -1

    /** Read only while drawing; bumped for anything that changes the picture. */
    internal val redraw = mutableIntStateOf(0)

    /** Bumped when there is highlighting work; the scheduler watches it. */
    internal val highlightSignal = mutableIntStateOf(0)

    /** Bumped on every change to the text, from an edit or from outside. */
    var textRevision by mutableLongStateOf(0L)
        private set

    /** Told about every edit made here (not about [setText]). */
    var onTextChange: ((String) -> Unit)? = null

    /** The grid, once the surface has measured it. */
    internal var metrics: FontMetrics? = null
        set(value) {
            field = value
            updateExtents()
        }

    private var viewWidth = 0.0
    private var viewHeight = 0.0

    /** Space below the last line, so a short document still has room to click into. */
    internal var bottomPadding = 0.0

    internal var clock: () -> Long = System::currentTimeMillis

    private var longestLine = -1
    private var longestWidth = 0

    init {
        highlighter.onVisibleStatesChanged = { redraw.intValue++ }
        recomputeLongest()
    }

    var language: EditorLanguage
        get() = highlighter.language
        set(value) {
            if (value === highlighter.language) return
            highlighter.setLanguage(value)
            folds.clear()
            highlightSignal.intValue++
            redraw.intValue++
        }

    val text: String get() = document.text()

    // --- whole-document changes ---

    /**
     * Replaces the text from outside — a saved request loaded into the tab.
     * That is a different document, not an edit of this one, so the history
     * starts again; the caret stays where it was, as far as the text allows.
     */
    fun setText(text: String) {
        if (text == document.text()) return
        document.setText(text)
        history.clear()
        folds.clear()
        highlighter.reset()
        selection = Selection(selection.head.coerceAtMost(document.length))
        recomputeLongest()
        textRevision++
        highlightSignal.intValue++
        redraw.intValue++
        updateExtents()
    }

    // --- editing ---

    /** Types [text] over the selection. */
    fun type(text: String) {
        if (text.isEmpty()) return
        val kind = if (text.length <= 2 && '\n' !in text) EditKind.TYPE else EditKind.OTHER
        edit(selection.start, selection.end, text, kind)
    }

    fun paste(text: String) {
        history.seal()
        edit(selection.start, selection.end, text, EditKind.OTHER)
        history.seal()
    }

    /** A line break, indented like the line it breaks — one step deeper after an opening bracket. */
    fun newline() {
        if (readOnly) return
        val head = selection.start
        val line = document.lineOf(head)
        val lineText = document.line(line)
        val col = head - document.lineStart(line)
        val indent = lineText.substring(0, col).takeWhile { it == ' ' || it == '\t' }
        val before = if (head > 0) document.charAt(head - 1) else ' '
        val after = if (selection.end < document.length) document.charAt(selection.end) else ' '
        val language = language
        if (language.isOpenBracket(before)) {
            val inner = "\n" + indent + INDENT
            val insert = if (after == language.partner(before)) inner + "\n" + indent else inner
            edit(selection.start, selection.end, insert, EditKind.OTHER, caretAfter = selection.start + inner.length)
        } else {
            edit(selection.start, selection.end, "\n" + indent, EditKind.OTHER)
        }
    }

    fun backspace(word: Boolean = false) {
        if (readOnly) return
        if (!selection.isEmpty) {
            edit(selection.start, selection.end, "", EditKind.OTHER)
            return
        }
        val head = selection.head
        if (head == 0) return
        val from = if (word) wordBoundary(head, forward = false) else previousCharBoundary(head)
        edit(from, head, "", EditKind.DELETE_BACKWARD, caretAfter = from)
    }

    fun deleteForward(word: Boolean = false) {
        if (readOnly) return
        if (!selection.isEmpty) {
            edit(selection.start, selection.end, "", EditKind.OTHER)
            return
        }
        val head = selection.head
        if (head >= document.length) return
        val to = if (word) wordBoundary(head, forward = true) else nextCharBoundary(head)
        edit(head, to, "", EditKind.DELETE_FORWARD, caretAfter = head)
    }

    /** Tab: to the next indent stop at the caret, or every selected line one step deeper. */
    fun indent() {
        if (readOnly) return
        if (selection.isEmpty || document.lineOf(selection.start) == document.lineOf(selection.end) &&
            !spansWholeLine()
        ) {
            val line = document.lineOf(selection.start)
            val col = Columns.columnOf(document.line(line), selection.start - document.lineStart(line))
            val spaces = INDENT.length - (col % INDENT.length)
            edit(selection.start, selection.end, " ".repeat(spaces), EditKind.OTHER)
            return
        }
        transformLines { INDENT + it }
    }

    /** Shift+Tab: every selected line one step shallower. */
    fun outdent() {
        if (readOnly) return
        transformLines { line ->
            when {
                line.startsWith(INDENT) -> line.substring(INDENT.length)
                line.startsWith("\t") || line.startsWith(" ") -> line.substring(1)
                else -> line
            }
        }
    }

    private fun spansWholeLine(): Boolean {
        val l = document.lineOf(selection.start)
        return selection.start == document.lineStart(l) && selection.end == document.lineEnd(l) && selection.end > selection.start
    }

    /** Rewrites the selected lines as one edit, keeping the selection on the same text. */
    private fun transformLines(transform: (String) -> String) {
        val first = document.lineOf(selection.start)
        var last = document.lineOf(selection.end)
        // A selection ending at the very start of a line does not include it.
        if (last > first && selection.end == document.lineStart(last)) last--
        val from = document.lineStart(first)
        val to = document.lineEnd(last)
        val old = (first..last).map { document.line(it) }
        val new = old.map(transform)
        if (old == new) return

        fun map(offset: Int): Int {
            val l = document.lineOf(offset).coerceIn(first, last)
            var shift = 0
            for (i in first until l) shift += new[i - first].length - old[i - first].length
            val col = offset - document.lineStart(l)
            val delta = new[l - first].length - old[l - first].length
            val lineShift = if (delta >= 0) delta else maxOf(delta, -col)
            return offset + shift + lineShift
        }

        val anchor = map(selection.anchor)
        val head = map(selection.head)
        val before = selection
        val change = applyRaw(from, to, new.joinToString("\n"))
        selection = Selection(anchor.coerceIn(0, document.length), head.coerceIn(0, document.length))
        history.record(change, EditKind.OTHER, before, selection, clock())
        afterEdit()
    }

    fun undo(): Boolean {
        if (readOnly) return false
        val entry = history.undo() ?: return false
        applyRaw(entry.from, entry.from + entry.inserted.length, entry.removed)
        selection = entry.selectionBefore.clamped()
        afterEdit()
        return true
    }

    fun redo(): Boolean {
        if (readOnly) return false
        val entry = history.redo() ?: return false
        applyRaw(entry.from, entry.from + entry.removed.length, entry.inserted)
        selection = entry.selectionAfter.clamped()
        afterEdit()
        return true
    }

    private fun Selection.clamped() = Selection(anchor.coerceIn(0, document.length), head.coerceIn(0, document.length))

    private fun edit(from: Int, to: Int, insert: String, kind: EditKind, caretAfter: Int? = null) {
        if (readOnly) return
        if (from == to && insert.isEmpty()) return
        val before = selection
        val change = applyRaw(from, to, insert)
        val caret = caretAfter ?: (change.from + change.inserted.length)
        selection = Selection(caret.coerceIn(0, document.length))
        history.record(change, kind, before, selection, clock())
        afterEdit()
    }

    private fun applyRaw(from: Int, to: Int, insert: String): Change {
        val change = document.replace(from, to, insert)
        highlighter.onChange(change)
        folds.onChange(change)
        trackLongest(change)
        return change
    }

    private fun afterEdit() {
        goalColumn = -1
        textRevision++
        highlightSignal.intValue++
        updateExtents()
        reveal()
        onTextChange?.invoke(document.text())
    }

    // --- selection and movement ---

    fun select(anchor: Int, head: Int = anchor) {
        selection = Selection(anchor.coerceIn(0, document.length), head.coerceIn(0, document.length))
        goalColumn = -1
        history.seal()
        revealCaretLine()
        reveal()
    }

    fun selectAll() {
        selection = Selection(0, document.length)
        history.seal()
        redraw.intValue++
    }

    fun selectWordAt(offset: Int) {
        val o = offset.coerceIn(0, document.length)
        val line = document.lineOf(o)
        val text = document.line(line)
        val start = document.lineStart(line)
        var a = o - start
        var b = a
        val cls = if (a < text.length) charClass(text[a]) else if (a > 0) charClass(text[a - 1]) else 0
        while (a > 0 && charClass(text[a - 1]) == cls) a--
        while (b < text.length && charClass(text[b]) == cls) b++
        select(start + a, start + b)
    }

    fun selectLineAt(offset: Int) {
        val line = document.lineOf(offset)
        val end = if (line + 1 < document.lineCount) document.lineStart(line + 1) else document.length
        select(document.lineStart(line), end)
    }

    /** Places the caret at [offset], extending the selection when [extend] is set. */
    fun moveTo(offset: Int, extend: Boolean = false) {
        val o = offset.coerceIn(0, document.length)
        selection = if (extend) Selection(selection.anchor, o) else Selection(o)
        goalColumn = -1
        history.seal()
        revealCaretLine()
        reveal()
    }

    fun moveHorizontal(forward: Boolean, word: Boolean = false, extend: Boolean = false) {
        if (!extend && !selection.isEmpty && !word) {
            moveTo(if (forward) selection.end else selection.start)
            return
        }
        val head = selection.head
        var target = when {
            word -> wordBoundary(head, forward)
            forward -> nextCharBoundary(head)
            else -> previousCharBoundary(head)
        }
        // Stepping into a fold steps over it.
        val line = document.lineOf(target)
        if (folds.isHidden(line)) {
            target = if (forward) {
                val row = folds.rowOfLine(line)
                val next = folds.lineOfRow(row + 1).coerceAtMost(document.lineCount - 1)
                if (next > line) document.lineStart(next) else document.length
            } else {
                document.lineEnd(folds.lineOfRow(folds.rowOfLine(line)))
            }
        }
        moveTo(target, extend)
    }

    /** Up or down by [rows] visual rows, keeping the column it started in. */
    fun moveVertical(rows: Int, extend: Boolean = false) {
        val head = selection.head
        val line = document.lineOf(head)
        val goal = if (goalColumn >= 0) goalColumn else Columns.columnOf(document.line(line), head - document.lineStart(line))
        val row = folds.rowOfLine(line) + rows
        val rowCount = folds.rowCount(document.lineCount)
        val target = when {
            row < 0 -> 0
            row >= rowCount -> document.length
            else -> {
                val l = folds.lineOfRow(row)
                document.lineStart(l) + Columns.offsetAt(document.line(l), goal)
            }
        }
        selection = if (extend) Selection(selection.anchor, target) else Selection(target)
        goalColumn = goal
        history.seal()
        reveal()
    }

    fun movePage(down: Boolean, extend: Boolean = false) {
        val lh = metrics?.lineHeight?.toDouble() ?: return
        val rows = floor(viewHeight / lh).toInt().coerceAtLeast(1)
        scroll.scrollBy(0.0, if (down) rows * lh else -rows * lh)
        moveVertical(if (down) rows else -rows, extend)
    }

    /** Home: to the first non-blank, or to the line's start if already there. */
    fun moveLineStart(extend: Boolean = false) {
        val line = document.lineOf(selection.head)
        val text = document.line(line)
        val start = document.lineStart(line)
        val firstText = text.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) text.length else it }
        val target = if (selection.head - start == firstText) start else start + firstText
        moveTo(target, extend)
    }

    fun moveLineEnd(extend: Boolean = false) = moveTo(document.lineEnd(document.lineOf(selection.head)), extend)

    fun moveDocumentStart(extend: Boolean = false) = moveTo(0, extend)

    fun moveDocumentEnd(extend: Boolean = false) = moveTo(document.length, extend)

    fun selectedText(): String = document.substring(selection.start, selection.end)

    fun copy(): Boolean {
        if (selection.isEmpty) return false
        EditorEngine.setClipboardText(selectedText())
        return true
    }

    fun cut(): Boolean {
        if (readOnly || selection.isEmpty) return false
        EditorEngine.setClipboardText(selectedText())
        edit(selection.start, selection.end, "", EditKind.OTHER)
        return true
    }

    fun pasteFromClipboard(): Boolean {
        if (readOnly) return false
        val text = EditorEngine.clipboardText() ?: return false
        paste(text)
        return true
    }

    private fun previousCharBoundary(offset: Int): Int {
        if (offset <= 0) return 0
        var o = offset - 1
        if (o > 0 && document.charAt(o).isLowSurrogate() && document.charAt(o - 1).isHighSurrogate()) o--
        return o
    }

    private fun nextCharBoundary(offset: Int): Int {
        if (offset >= document.length) return document.length
        var o = offset + 1
        if (o < document.length && document.charAt(o - 1).isHighSurrogate() && document.charAt(o).isLowSurrogate()) o++
        return o
    }

    /**
     * The next word boundary: spaces are skipped, then a run of one class —
     * word chars, or punctuation. A line break is a boundary of its own.
     */
    internal fun wordBoundary(offset: Int, forward: Boolean): Int {
        val line = document.lineOf(offset)
        val text = document.line(line)
        val start = document.lineStart(line)
        var col = offset - start
        if (forward) {
            if (col >= text.length) return if (line + 1 < document.lineCount) document.lineStart(line + 1) else offset
            while (col < text.length && text[col].isWhitespace()) col++
            if (col < text.length) {
                val cls = charClass(text[col])
                while (col < text.length && charClass(text[col]) == cls) col++
            }
        } else {
            if (col == 0) return if (line > 0) document.lineEnd(line - 1) else 0
            while (col > 0 && text[col - 1].isWhitespace()) col--
            if (col > 0) {
                val cls = charClass(text[col - 1])
                while (col > 0 && charClass(text[col - 1]) == cls) col--
            }
        }
        return start + col
    }

    private fun charClass(c: Char): Int = when {
        c.isLetterOrDigit() || c == '_' || c == '$' -> 1
        c.isWhitespace() -> 0
        else -> 2
    }

    // --- folding ---

    fun canFold(line: Int): Boolean = foldingEnabled && line in 0 until document.lineCount && structure.canFold(line)

    fun toggleFold(line: Int): Boolean {
        if (!foldingEnabled) return false
        val changed = if (folds.isFolded(line)) {
            folds.unfold(line)
        } else {
            val range = structure.foldRange(line) ?: return false
            folds.fold(line, range.last)
            true
        }
        if (changed) afterFoldChange(line)
        return changed
    }

    /** Folds the innermost region around the caret. */
    fun foldAtCaret(): Boolean {
        if (!foldingEnabled) return false
        var line = document.lineOf(selection.head)
        var scanned = 0
        while (line >= 0 && scanned < 10_000) {
            if (!folds.isFolded(line)) {
                val range = structure.foldRange(line)
                if (range != null && document.lineOf(selection.head) <= range.last + 1) {
                    folds.fold(line, range.last)
                    afterFoldChange(line)
                    return true
                }
            }
            line--
            scanned++
        }
        return false
    }

    fun unfoldAtCaret(): Boolean {
        val line = document.lineOf(selection.head)
        val changed = folds.unfold(line) || folds.reveal(line)
        if (changed) afterFoldChange(line)
        return changed
    }

    private fun afterFoldChange(head: Int) {
        // A caret inside what was just hidden moves to the end of the head line.
        if (folds.isHidden(document.lineOf(selection.head))) selection = Selection(document.lineEnd(head))
        updateExtents()
        redraw.intValue++
    }

    private fun revealCaretLine() {
        if (folds.reveal(document.lineOf(selection.head))) updateExtents()
    }

    // --- find ---

    /** Selects the next (or previous) match and scrolls to it. */
    fun findNext(forward: Boolean = true) {
        val find = find
        if (find.matches.isEmpty()) return
        val at = if (find.current < 0) {
            find.current = find.firstAtOrAfter(selection.end).let { if (it >= find.matches.size) 0 else it }
            find.matches[find.current]
        } else {
            find.advance(forward)
        }
        select(at, at + find.matchLength)
    }

    // --- brackets ---

    private var bracketKey = Long.MIN_VALUE
    private var bracketHead = -1
    private var bracketCache: BracketMatch? = null

    /** The bracket pair at the caret, cached until the caret, the text or the language moves. */
    internal fun bracketMatch(): BracketMatch? {
        val key = document.version * 31 + highlighter.revision
        if (key != bracketKey || bracketHead != selection.head) {
            bracketKey = key
            bracketHead = selection.head
            bracketCache = if (selection.isEmpty) structure.matchBracket(selection.head) else null
        }
        return bracketCache
    }

    // --- geometry ---

    internal fun setViewport(width: Double, height: Double) {
        if (width == viewWidth && height == viewHeight) return
        viewWidth = width
        viewHeight = height
        updateExtents()
    }

    internal fun detach() {
        scroll.remove(this)
    }

    /** The widest line, in columns — what the horizontal scroll covers. */
    val longestColumns: Int get() = longestWidth

    internal fun updateExtents() {
        val m = metrics ?: return
        val rows = folds.rowCount(document.lineCount)
        scroll.report(
            owner = this,
            contentWidth = (longestWidth + 2) * m.charWidth.toDouble(),
            contentHeight = rows * m.lineHeight.toDouble() + bottomPadding,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
        redraw.intValue++
    }

    /** Scrolls the caret into view, with a little room either side horizontally. */
    fun reveal() {
        redraw.intValue++
        val m = metrics ?: return
        if (viewHeight <= 0.0) return
        val head = selection.head
        val line = document.lineOf(head)
        val row = folds.rowOfLine(line)
        val lh = m.lineHeight.toDouble()
        val top = row * lh
        var y = scroll.y
        if (top < y) y = top else if (top + lh > y + viewHeight) y = top + lh - viewHeight
        val col = Columns.columnOf(document.line(line), head - document.lineStart(line))
        val x0 = col * m.charWidth.toDouble()
        val margin = m.charWidth * 4.0
        var x = scroll.x
        if (x0 < x + margin) x = (x0 - margin).coerceAtLeast(0.0) else if (x0 > x + viewWidth - margin) x = x0 - viewWidth + margin
        scroll.scrollTo(x, y)
    }

    private fun recomputeLongest() {
        var best = 0
        var bestLine = 0
        for (i in 0 until document.lineCount) {
            val l = document.line(i)
            // A line cannot be wider than twice its length; skip the measure when it cannot win.
            if (l.length * 2 <= best) continue
            val w = Columns.measure(l)
            if (w > best) {
                best = w
                bestLine = i
            }
        }
        longestWidth = best
        longestLine = bestLine
    }

    private fun trackLongest(change: Change) {
        var best = -1
        var bestLine = -1
        for (i in change.firstLine..change.newLastLine) {
            val w = Columns.measure(document.line(i))
            if (w > best) {
                best = w
                bestLine = i
            }
        }
        if (longestLine in change.firstLine..change.oldLastLine) {
            // The longest line was edited; unless an edited line is still the
            // widest, something else may be now.
            if (best >= longestWidth) {
                longestWidth = best
                longestLine = bestLine
            } else {
                recomputeLongest()
            }
            return
        }
        if (longestLine > change.oldLastLine) longestLine += change.lineDelta
        if (best > longestWidth) {
            longestWidth = best
            longestLine = bestLine
        }
    }

    companion object {
        const val INDENT = "  "
    }
}
