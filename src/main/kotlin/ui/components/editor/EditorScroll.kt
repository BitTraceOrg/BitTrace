package org.bittrace.ui.components.editor

import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * Where an editor is scrolled to, in pixels — and possibly more than one editor.
 *
 * Doubles rather than the `Int`s of a Compose `ScrollState`, because an editor
 * here is never laid out at its content's size: it draws the rows on screen and
 * nothing else, so the content's height is arithmetic and can be as tall as a
 * million lines makes it. (`Constraints` cannot represent a height past about
 * a quarter of a million pixels, which is where laying the whole document out
 * used to stop working.)
 *
 * Shareable: the diff hands one instance to both panes, which is all it takes
 * for them to scroll as one. Each surface reports its own content and viewport
 * size, and the state scrolls over the largest of them.
 */
class EditorScrollState {
    var x by mutableDoubleStateOf(0.0)
        private set
    var y by mutableDoubleStateOf(0.0)
        private set

    private val extents = mutableStateMapOf<Any, Extent>()

    private class Extent(val contentWidth: Double, val contentHeight: Double, val viewWidth: Double, val viewHeight: Double)

    val contentWidth: Double get() = extents.values.maxOfOrNull { it.contentWidth } ?: 0.0
    val contentHeight: Double get() = extents.values.maxOfOrNull { it.contentHeight } ?: 0.0
    val viewWidth: Double get() = extents.values.minOfOrNull { it.viewWidth } ?: 0.0
    val viewHeight: Double get() = extents.values.minOfOrNull { it.viewHeight } ?: 0.0

    val maxX: Double get() = (contentWidth - viewWidth).coerceAtLeast(0.0)
    val maxY: Double get() = (contentHeight - viewHeight).coerceAtLeast(0.0)

    internal fun report(owner: Any, contentWidth: Double, contentHeight: Double, viewWidth: Double, viewHeight: Double) {
        val old = extents[owner]
        if (old != null && old.contentWidth == contentWidth && old.contentHeight == contentHeight &&
            old.viewWidth == viewWidth && old.viewHeight == viewHeight
        ) return
        extents[owner] = Extent(contentWidth, contentHeight, viewWidth, viewHeight)
        clamp()
    }

    internal fun remove(owner: Any) {
        extents.remove(owner)
    }

    fun scrollTo(x: Double = this.x, y: Double = this.y) {
        this.x = x.coerceIn(0.0, maxX)
        this.y = y.coerceIn(0.0, maxY)
    }

    fun scrollBy(dx: Double, dy: Double) = scrollTo(x + dx, y + dy)

    private fun clamp() {
        if (x > maxX) x = maxX
        if (y > maxY) y = maxY
    }

    /** For a scrollbar: Double-based, so it is right at any document size. */
    val vertical: ScrollbarAdapter = object : ScrollbarAdapter {
        override val scrollOffset: Double get() = y
        override val contentSize: Double get() = contentHeight
        override val viewportSize: Double get() = viewHeight
        override suspend fun scrollTo(scrollOffset: Double) = this@EditorScrollState.scrollTo(y = scrollOffset)
    }

    val horizontal: ScrollbarAdapter = object : ScrollbarAdapter {
        override val scrollOffset: Double get() = x
        override val contentSize: Double get() = contentWidth
        override val viewportSize: Double get() = viewWidth
        override suspend fun scrollTo(scrollOffset: Double) = this@EditorScrollState.scrollTo(x = scrollOffset)
    }
}
