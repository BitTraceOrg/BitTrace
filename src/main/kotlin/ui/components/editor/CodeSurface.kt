package org.bittrace.ui.components.editor

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.TextInput

/**
 * The editor on screen: one focusable box that draws only what is visible.
 *
 * There is no layout per line and no layout per document. The box is the size
 * its parent gives it; inside, rows are `lineHeight` apart and columns
 * `charWidth` apart, so which lines and which part of each line are on screen
 * is arithmetic over the scroll offset. Each visible line is laid out in
 * chunks of [DRAW_CHUNK] chars — only the chunks the viewport crosses — through
 * the shared measurer, whose cache keeps a line that has not changed from
 * being shaped again. A million lines cost what fifty do, and a five-megabyte
 * line what its visible stretch does.
 *
 * Everything the picture depends on is read in the draw phase — the scroll
 * offsets, the state's redraw counter, the palette — so scrolling, typing and
 * a theme switch repaint without recomposing.
 *
 * @param gutter line numbers and fold markers; off for a view that claims
 *   nothing has been done to its text.
 * @param lineBackground a wash per document line, as the diff paints.
 * @param verticalScrollbar off for the left pane of a pair that scrolls as one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CodeSurface(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    gutter: Boolean = true,
    placeholder: String = "",
    lineBackground: ((Int) -> Color)? = null,
    verticalScrollbar: Boolean = true,
) {
    val density = LocalDensity.current
    val measurer = EditorEngine.measurer(LocalFontFamilyResolver.current, density)
    val textStyle = TextStyle(fontFamily = P.Mono, fontSize = Typo.label.fontSize)
    val metrics = remember(textStyle, density) { EditorEngine.metrics(measurer, textStyle, density) }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var caretOn by remember { mutableStateOf(true) }
    val background by rememberUpdatedState(lineBackground)
    val padTop = with(density) { 2.dp.toPx() }
    val textPad = with(density) { 6.dp.toPx() }

    val bottomPadding = with(density) { 60.dp.toPx() }.toDouble()
    SideEffect {
        state.bottomPadding = bottomPadding
        state.metrics = metrics
    }

    DisposableEffect(state) { onDispose { state.detach() } }
    LaunchedEffect(state) { HighlightScheduler.drive(state.highlighter, state.highlightSignal) }
    LaunchedEffect(focused) {
        caretOn = true
        while (focused) {
            delay(CARET_BLINK_MS)
            caretOn = !caretOn
        }
    }
    FindEffect(state)

    // Gutter width depends on how many digits the last line number has.
    fun gutterWidth(): Float {
        if (!gutter) return 0f
        val digits = maxOf(2, state.document.lineCount.toString().length)
        val fold = if (state.foldingEnabled) metrics.charWidth * 2f else metrics.charWidth
        return textPad + digits * metrics.charWidth + fold
    }

    fun offsetAt(pos: Offset): Int {
        val lh = metrics.lineHeight
        val rows = state.folds.rowCount(state.document.lineCount)
        val row = floor((pos.y - padTop + state.scroll.y) / lh).toInt().coerceIn(0, rows - 1)
        val line = state.folds.lineOfRow(row)
        val text = state.document.line(line)
        val col = ((pos.x - gutterWidth() - textPad + state.scroll.x) / metrics.charWidth).roundToInt()
        return state.document.lineStart(line) + Columns.offsetAt(text, col)
    }

    val styles = EditorEngine.styles(P.palette)

    Box(modifier.background(styles.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    state.setViewport(
                        (size.width - gutterWidth() - textPad).toDouble().coerceAtLeast(0.0),
                        (size.height - padTop).toDouble().coerceAtLeast(0.0),
                    )
                }
                .onKeyEvent { event ->
                    caretOn = true
                    handleKey(state, event)
                }
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .pointerInput(state) {
                    var dragging = false
                    var lastClick = 0L
                    var clicks = 0
                    var lastPos = Offset.Zero
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    val d = change.scrollDelta
                                    val step = metrics.lineHeight * WHEEL_LINES
                                    if (event.keyboardModifiers.isShiftPressed && d.x == 0f) {
                                        state.scroll.scrollBy(d.y * step.toDouble(), 0.0)
                                    } else {
                                        state.scroll.scrollBy(d.x * step.toDouble(), d.y * step.toDouble())
                                    }
                                    change.consume()
                                }

                                PointerEventType.Press -> {
                                    if (!event.buttons.isPrimaryPressed) continue
                                    focusRequester.requestFocus()
                                    caretOn = true
                                    val pos = change.position
                                    // In the gutter's fold column: fold or unfold, nothing else.
                                    if (gutter && state.foldingEnabled && pos.x < gutterWidth() &&
                                        pos.x > gutterWidth() - metrics.charWidth * 2f
                                    ) {
                                        val row = floor((pos.y - padTop + state.scroll.y) / metrics.lineHeight).toInt()
                                        if (row in 0 until state.folds.rowCount(state.document.lineCount)) {
                                            state.toggleFold(state.folds.lineOfRow(row))
                                        }
                                        change.consume()
                                        continue
                                    }
                                    val now = change.uptimeMillis
                                    val near = abs(pos.x - lastPos.x) < 4 && abs(pos.y - lastPos.y) < 4
                                    clicks = if (now - lastClick < MULTI_CLICK_MS && near) clicks + 1 else 1
                                    lastClick = now
                                    lastPos = pos
                                    val offset = offsetAt(pos)
                                    when {
                                        clicks == 2 -> state.selectWordAt(offset)
                                        clicks >= 3 -> state.selectLineAt(offset)
                                        else -> state.moveTo(offset, extend = event.keyboardModifiers.isShiftPressed)
                                    }
                                    dragging = clicks == 1
                                    change.consume()
                                }

                                PointerEventType.Move -> {
                                    if (dragging && event.buttons.isPrimaryPressed) {
                                        val pos = change.position
                                        // Past an edge, scroll toward it.
                                        val h = size.height.toFloat()
                                        if (pos.y < 0) state.scroll.scrollBy(0.0, pos.y.toDouble())
                                        if (pos.y > h) state.scroll.scrollBy(0.0, (pos.y - h).toDouble())
                                        state.moveTo(offsetAt(pos), extend = true)
                                        change.consume()
                                    }
                                }

                                PointerEventType.Release -> dragging = false
                            }
                        }
                    }
                }
                .drawBehind {
                    state.redraw.intValue
                    drawEditor(
                        state = state,
                        styles = EditorEngine.styles(P.palette),
                        measurer = measurer,
                        textStyle = textStyle,
                        metrics = metrics,
                        gutterWidth = gutterWidth(),
                        textPad = textPad,
                        padTop = padTop,
                        showGutter = gutter,
                        focused = focused,
                        caretOn = caretOn,
                        placeholder = placeholder,
                        lineBackground = background,
                    )
                },
        )

        if (verticalScrollbar) {
            VerticalScrollbar(
                adapter = state.scroll.vertical,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = scrollbarStyle(styles),
            )
        }
        HorizontalScrollbar(
            adapter = state.scroll.horizontal,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = with(density) { gutterWidth().toDp() }),
            style = scrollbarStyle(styles),
        )

        if (state.find.open) {
            FindBar(state, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 14.dp), onClose = {
                state.find.open = false
                state.find.clearResults()
                state.redraw.intValue++
                focusRequester.requestFocus()
            })
        }
    }
}

private fun scrollbarStyle(styles: EditorStyles) = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 6.dp,
    shape = RectangleShape,
    hoverDurationMillis = 200,
    unhoverColor = styles.scrollThumb,
    hoverColor = styles.scrollThumbHover,
)

/** Maps a key press onto a command; returns whether it was one. */
private fun handleKey(state: CodeEditorState, event: KeyEvent): Boolean {
    val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent
    val mod = if (EditorEngine.isMac) event.isMetaPressed else event.isCtrlPressed
    val shift = event.isShiftPressed
    // Word-wise movement is Alt on macOS and Ctrl elsewhere.
    val wordMod = if (EditorEngine.isMac) event.isAltPressed else event.isCtrlPressed

    if (awt != null && awt.id == java.awt.event.KeyEvent.KEY_TYPED) {
        val c = awt.keyChar
        // Ctrl+Alt is AltGr on Windows, which is how `{` is typed on half of Europe.
        val shortcut = event.isMetaPressed || (event.isCtrlPressed && !event.isAltPressed)
        if (shortcut || c.code < 0x20 || c.code == 0x7F || c == java.awt.event.KeyEvent.CHAR_UNDEFINED) return false
        if (state.readOnly) return false
        state.type(c.toString())
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false

    when (event.key) {
        // Cmd+Left/Right are Home and End on macOS.
        Key.DirectionLeft -> if (EditorEngine.isMac && event.isMetaPressed) state.moveLineStart(shift) else state.moveHorizontal(forward = false, word = wordMod, extend = shift)
        Key.DirectionRight -> if (EditorEngine.isMac && event.isMetaPressed) state.moveLineEnd(shift) else state.moveHorizontal(forward = true, word = wordMod, extend = shift)
        Key.DirectionUp -> if (EditorEngine.isMac && event.isMetaPressed) state.moveDocumentStart(shift) else state.moveVertical(-1, shift)
        Key.DirectionDown -> if (EditorEngine.isMac && event.isMetaPressed) state.moveDocumentEnd(shift) else state.moveVertical(1, shift)
        Key.MoveHome -> if (mod) state.moveDocumentStart(shift) else state.moveLineStart(shift)
        Key.MoveEnd -> if (mod) state.moveDocumentEnd(shift) else state.moveLineEnd(shift)
        Key.PageUp -> state.movePage(down = false, extend = shift)
        Key.PageDown -> state.movePage(down = true, extend = shift)
        Key.Backspace -> state.backspace(word = wordMod)
        Key.Delete -> state.deleteForward(word = wordMod)
        Key.Enter, Key.NumPadEnter -> if (state.readOnly) return false else state.newline()
        Key.Tab -> {
            if (state.readOnly) return false
            if (shift) state.outdent() else state.indent()
        }

        Key.Escape -> {
            if (!state.find.open) return false
            state.find.open = false
            state.find.clearResults()
            state.redraw.intValue++
        }

        Key.F3 -> state.findNext(forward = !shift)
        else -> {
            if (!mod) return false
            when (event.key) {
                Key.A -> state.selectAll()
                Key.C, Key.Insert -> state.copy()
                Key.X -> state.cut()
                Key.V -> state.pasteFromClipboard()
                Key.Z -> if (shift) state.redo() else state.undo()
                Key.Y -> if (!EditorEngine.isMac) state.redo() else return false
                Key.F -> {
                    val selected = state.selectedText()
                    if (selected.isNotEmpty() && '\n' !in selected) state.find.query = selected
                    state.find.open = true
                    state.find.focusRequests++
                }

                Key.G -> state.findNext(forward = !shift)
                Key.Minus, Key.NumPadSubtract -> state.foldAtCaret()
                Key.Equals, Key.Plus, Key.NumPadAdd -> state.unfoldAtCaret()
                else -> return false
            }
        }
    }
    return true
}

/** Runs the search whenever the query, the case flag or the text changes. */
@Composable
private fun FindEffect(state: CodeEditorState) {
    LaunchedEffect(state) {
        val find = state.find
        snapshotFlow { listOf(find.open, find.query, find.matchCase, state.textRevision) }.collectLatest {
            if (!find.open || find.query.isEmpty()) {
                if (find.matches.isNotEmpty()) find.clearResults()
                state.redraw.intValue++
                return@collectLatest
            }
            delay(FIND_DEBOUNCE_MS)
            val text = state.document.text()
            val query = find.query
            val matchCase = find.matchCase
            val found = withContext(Dispatchers.Default) { findAll(text, query, matchCase) }
            find.setResults(found, query.length, state.selection.start)
            if (find.current >= 0) {
                val at = find.matches[find.current]
                state.select(at, at + find.matchLength)
            }
            state.redraw.intValue++
        }
    }
}

@Composable
private fun FindBar(state: CodeEditorState, modifier: Modifier, onClose: () -> Unit) {
    val find = state.find
    val styles = EditorEngine.styles(P.palette)
    val field = remember { FocusRequester() }
    LaunchedEffect(find.focusRequests) { runCatching { field.requestFocus() } }
    Row(
        modifier
            .background(styles.panel)
            .border(1.dp, styles.panelBorder)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextInput(
            value = find.query,
            onValueChange = { find.query = it },
            modifier = Modifier.width(200.dp).focusRequester(field).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter, Key.F3 -> {
                        state.findNext(forward = !e.isShiftPressed)
                        true
                    }

                    Key.Escape -> {
                        onClose()
                        true
                    }

                    else -> false
                }
            },
            placeholder = "Find",
        )
        Spacer(Modifier.width(6.dp))
        FindButton("Aa", active = find.matchCase) { find.matchCase = !find.matchCase }
        FindButton("↑") { state.findNext(forward = false) }
        FindButton("↓") { state.findNext(forward = true) }
        Spacer(Modifier.width(6.dp))
        val count = when {
            find.query.isEmpty() -> ""
            find.matches.isEmpty() -> "no results"
            else -> "${find.current + 1} of ${find.matches.size}${if (find.capped) "+" else ""}"
        }
        // Read so the count follows new results.
        find.revision
        PzText(count, color = if (find.query.isNotEmpty() && find.matches.isEmpty()) P.err else P.dim, style = Typo.caption, family = P.Ui)
        Spacer(Modifier.width(6.dp))
        FindButton("×", onClick = onClose)
    }
}

@Composable
private fun FindButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) P.accentFill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        PzText(label, color = if (active) P.accent else P.dim, style = Typo.caption, family = P.Ui)
    }
}

/** Everything visible, in one pass: washes, selection, text, gutter, caret. */
private fun DrawScope.drawEditor(
    state: CodeEditorState,
    styles: EditorStyles,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    metrics: FontMetrics,
    gutterWidth: Float,
    textPad: Float,
    padTop: Float,
    showGutter: Boolean,
    focused: Boolean,
    caretOn: Boolean,
    placeholder: String,
    lineBackground: ((Int) -> Color)?,
) {
    val doc = state.document
    val folds = state.folds
    val lh = metrics.lineHeight
    val cw = metrics.charWidth
    val sx = state.scroll.x.toFloat()
    val sy = state.scroll.y.toFloat()
    val textLeft = gutterWidth + textPad
    val rowCount = folds.rowCount(doc.lineCount)
    val firstRow = floor(sy / lh).toInt().coerceIn(0, maxOf(0, rowCount - 1))
    val lastRow = minOf(rowCount - 1, ceil((sy + size.height) / lh).toInt())
    val selection = state.selection
    val caretLine = doc.lineOf(selection.head)
    val baseStyle = textStyle.copy(color = styles.text)

    state.highlighter.visibleFirst = folds.lineOfRow(firstRow)
    state.highlighter.visibleLast = folds.lineOfRow(maxOf(firstRow, lastRow))

    fun rowTop(row: Int) = padTop + row * lh - sy
    fun colX(line: Int, offsetInLine: Int) = textLeft + Columns.columnOf(doc.line(line), offsetInLine) * cw - sx

    drawRect(styles.background)

    clipRect(left = gutterWidth) {
        // Line washes and the active line.
        for (row in firstRow..lastRow) {
            val line = folds.lineOfRow(row)
            val wash = lineBackground?.invoke(line)
            if (wash != null && wash != Color.Transparent) {
                drawRect(wash, Offset(gutterWidth, rowTop(row)), Size(size.width - gutterWidth, lh))
            }
            if (line == caretLine && selection.isEmpty && focused) {
                drawRect(styles.activeLine, Offset(gutterWidth, rowTop(row)), Size(size.width - gutterWidth, lh))
            }
        }

        // Search matches.
        val find = state.find
        find.revision
        if (find.matches.isNotEmpty()) {
            val firstOffset = doc.lineStart(folds.lineOfRow(firstRow))
            val lastOffset = doc.lineEnd(folds.lineOfRow(maxOf(firstRow, lastRow)))
            var i = find.firstAtOrAfter(firstOffset - find.matchLength)
            while (i < find.matches.size && find.matches[i] <= lastOffset) {
                val start = find.matches[i]
                drawRange(doc, folds, start, start + find.matchLength, if (i == find.current) styles.searchCurrent else styles.searchMatch, ::colX, ::rowTop, lh, cw)
                i++
            }
        }

        // Selection.
        if (!selection.isEmpty) drawRange(doc, folds, selection.start, selection.end, styles.selection, ::colX, ::rowTop, lh, cw)

        // Bracket pair at the caret.
        if (focused) {
            state.bracketMatch()?.let { m ->
                val color = if (m.matched) styles.bracketMatch else styles.bracketMismatch
                drawRange(doc, folds, m.at, m.at + 1, color, ::colX, ::rowTop, lh, cw)
                if (m.matched) drawRange(doc, folds, m.other, m.other + 1, color, ::colX, ::rowTop, lh, cw)
            }
        }

        // Text.
        val viewCols = ceil((size.width - textLeft) / cw).toInt() + 2
        val firstCol = floor(sx / cw).toInt()
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        val map = IntArray(DRAW_CHUNK + 1)
        for (row in firstRow..lastRow) {
            val line = folds.lineOfRow(row)
            val text = doc.line(line)
            val y = rowTop(row) + metrics.textTop
            if (text.isNotEmpty()) {
                val from = Columns.offsetAt(text, firstCol - 1)
                val to = Columns.offsetAt(text, firstCol + viewCols)
                var cs = from - from % DRAW_CHUNK
                while (cs < to) {
                    var start = cs
                    if (start > 0 && start < text.length && text[start].isLowSurrogate()) start++
                    var end = minOf(cs + DRAW_CHUNK, text.length)
                    if (end < text.length && text[end].isLowSurrogate()) end++
                    if (end > start) {
                        val layout = layoutChunk(state, measurer, baseStyle, styles, line, text, start, end, spans, map)
                        drawText(layout, topLeft = Offset(colX(line, start), y))
                    }
                    cs += DRAW_CHUNK
                }
            }
            if (folds.isFolded(line)) {
                val x = colX(line, text.length) + cw
                val label = measurer.measure(AnnotatedString(" … "), baseStyle.copy(color = styles.foldPlaceholderText), softWrap = false, maxLines = 1)
                drawRect(styles.foldPlaceholderBackground, Offset(x, rowTop(row) + 2f), Size(label.size.width.toFloat(), lh - 4f))
                drawText(label, topLeft = Offset(x, y))
            }
        }

        if (doc.length == 0 && placeholder.isNotEmpty()) {
            val layout = measurer.measure(AnnotatedString(placeholder), baseStyle.copy(color = styles.placeholder), softWrap = false, maxLines = 1)
            drawText(layout, topLeft = Offset(textLeft - sx, rowTop(0) + metrics.textTop))
        }

        // Caret.
        if (focused && caretOn && !folds.isHidden(caretLine)) {
            val row = folds.rowOfLine(caretLine)
            val x = colX(caretLine, selection.head - doc.lineStart(caretLine))
            drawRect(styles.caret, Offset(x, rowTop(row) + 1f), Size(maxOf(1f, density), lh - 2f))
        }
    }

    if (showGutter) drawGutter(state, styles, measurer, baseStyle, metrics, gutterWidth, firstRow, lastRow, caretLine, ::rowTop)
}

/** A highlighted range, one rect per visible row it crosses. */
private fun DrawScope.drawRange(
    doc: TextDocument,
    folds: FoldModel,
    from: Int,
    to: Int,
    color: Color,
    colX: (Int, Int) -> Float,
    rowTop: (Int) -> Float,
    lh: Float,
    cw: Float,
) {
    val first = doc.lineOf(from)
    val last = doc.lineOf(to)
    val visibleTop = -lh
    for (line in first..last) {
        if (folds.isHidden(line)) continue
        val row = folds.rowOfLine(line)
        val top = rowTop(row)
        if (top < visibleTop) continue
        if (top > size.height) break
        val start = doc.lineStart(line)
        val a = if (line == first) from - start else 0
        val b = if (line == last) to - start else doc.line(line).length
        val x0 = colX(line, a)
        // A selected line break shows as a char's width past the end of the line.
        val x1 = colX(line, b) + if (line != last) cw else 0f
        if (x1 > x0) drawRect(color, Offset(x0, top), Size(x1 - x0, lh))
    }
}

/** One chunk of one line, styled from its tokens, laid out through the shared measurer. */
private fun layoutChunk(
    state: CodeEditorState,
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    styles: EditorStyles,
    line: Int,
    text: String,
    start: Int,
    end: Int,
    spans: ArrayList<AnnotatedString.Range<SpanStyle>>,
    map: IntArray,
): TextLayoutResult {
    val needMap = end - start + 1 <= map.size
    val display = Columns.display(text, start, end, if (needMap) map else null)
    spans.clear()
    state.highlighter.forEachToken(line, start, end) { s, e, k ->
        val style = styles.tokens[k.ordinal] ?: return@forEachToken
        val a = maxOf(s, start) - start
        val b = minOf(e, end) - start
        if (b <= a) return@forEachToken
        val da = if (needMap) map[a] else a
        val db = if (needMap) map[b] else b
        if (db > da) spans.add(AnnotatedString.Range(style, da, db))
    }
    val annotated = AnnotatedString(display, spanStyles = spans.toList())
    return measurer.measure(annotated, baseStyle, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
}

private fun DrawScope.drawGutter(
    state: CodeEditorState,
    styles: EditorStyles,
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    metrics: FontMetrics,
    gutterWidth: Float,
    firstRow: Int,
    lastRow: Int,
    caretLine: Int,
    rowTop: (Int) -> Float,
) {
    val doc = state.document
    val folds = state.folds
    val cw = metrics.charWidth
    val lh = metrics.lineHeight
    drawRect(styles.background, Offset.Zero, Size(gutterWidth, size.height))
    drawLine(styles.gutterBorder, Offset(gutterWidth - 0.5f, 0f), Offset(gutterWidth - 0.5f, size.height), strokeWidth = 1f)
    val foldArea = if (state.foldingEnabled) cw * 2f else cw
    val numberRight = gutterWidth - foldArea
    for (row in firstRow..lastRow) {
        val line = folds.lineOfRow(row)
        val label = (line + 1).toString()
        val color = if (line == caretLine) styles.gutterActiveText else styles.gutterText
        val layout = measurer.measure(AnnotatedString(label), baseStyle.copy(color = color), softWrap = false, maxLines = 1)
        val top = rowTop(row)
        drawText(layout, topLeft = Offset(numberRight - layout.size.width, top + metrics.textTop))

        if (state.foldingEnabled) {
            val folded = folds.isFolded(line)
            if (folded || state.canFold(line)) {
                val cx = gutterWidth - foldArea / 2f
                val cy = top + lh / 2f
                val r = cw * 0.35f
                val path = Path()
                if (folded) {
                    // ▸
                    path.moveTo(cx - r * 0.6f, cy - r)
                    path.lineTo(cx + r * 0.8f, cy)
                    path.lineTo(cx - r * 0.6f, cy + r)
                } else {
                    // ▾
                    path.moveTo(cx - r, cy - r * 0.6f)
                    path.lineTo(cx + r, cy - r * 0.6f)
                    path.lineTo(cx, cy + r * 0.8f)
                }
                path.close()
                drawPath(path, if (folded) styles.gutterActiveText else styles.foldMarker)
            }
        }
    }
}

/** Chars laid out per draw call; a line is drawn as the chunks the viewport crosses. */
private const val DRAW_CHUNK = 256
private const val CARET_BLINK_MS = 530L
private const val MULTI_CLICK_MS = 400L
private const val WHEEL_LINES = 3f
private const val FIND_DEBOUNCE_MS = 120L
