package org.bittrace.ui.layouts.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Cursor
import java.awt.MouseInfo
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import kotlin.math.ceil
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficRow
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.P
import org.bittrace.ui.border1
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.topBorder
import org.bittrace.ui.components.AddressBar
import org.bittrace.ui.components.DataGrid
import org.bittrace.ui.components.EmptyState
import org.bittrace.ui.components.GridStyle
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.TabContentSwitcher
import org.bittrace.ui.components.TabLabel
import org.bittrace.ui.layouts.inspector.SideMetaGrid
import org.bittrace.proxy.BodySide
import org.bittrace.ui.layouts.inspector.components.defaultColumns
import org.bittrace.ui.layouts.inspector.components.PhaseRibbon
import org.bittrace.ui.layouts.inspector.components.SizeRibbon

/**
 * Widget mode: the last two captured flows in a small frameless window that
 * floats over everything else.
 *
 * It reads the very [store] the main window does — the same instance, not a
 * copy or a mirror of it — so a flow shows up here the moment it lands in the
 * grid and a response filling in repaints both. Nothing is held here beyond
 * which row is open.
 *
 * Built from the app's own parts rather than a look of its own: the rows are
 * the traffic grid with its own columns, the tabs are the inspector's strip,
 * and the cells and bars are the inspector's overview. A flow reads the same
 * here as it does in the main window.
 *
 * A utility window rather than a frame: that is what keeps it out of the
 * taskbar, where a widget has no business being a second BitTrace. Its height
 * follows its content, so opening a row grows the window downward with the
 * details as they expand, and closing it shrinks back to the two rows.
 */
@Composable
fun CaptureWidget(
    store: SessionStore,
    service: ProxyService,
    port: Int,
    /** Puts the widget away; the main window was never gone. */
    onDock: () -> Unit,
    /** Brings the main window forward on this flow in the traffic grid. */
    onInspect: (String) -> Unit,
) {
    // What the user has stretched it to. Width only: the grid is two rows, so
    // the height is its own plus whatever the open flow's details add.
    var width by remember { mutableStateOf(WIDGET_WIDTH) }

    // The overload that builds the window itself, because the type has to be
    // set before AWT creates the peer, which the stock one does too early.
    SwingWindow(
        create = {
            ComposeWindow().apply {
                // All of these before the peer exists: an AWT window cannot
                // change its type or its decorations once it has been shown.
                // Resizing is ours — an undecorated window has no frame to
                // grab — so the platform's is left off.
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                // See-through, so the corners outside the rounded shape below
                // show the desktop rather than a square of window background.
                isTransparent = true
                isAlwaysOnTop = true
                isResizable = false
                title = "BitTrace widget"
                setSize(WIDGET_WIDTH, COLLAPSED_GUESS)
                // Top-right of the work area, clear of the taskbar and the edge.
                val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                setLocation(area.x + area.width - WIDGET_WIDTH - SCREEN_MARGIN, area.y + SCREEN_MARGIN)
            }
        },
        dispose = { it.dispose() },
    ) {
        Box(
            Modifier.fillMaxSize()
                .clip(WindowShape)
                .background(P.panel, WindowShape)
                .border1(P.line, shape = WindowShape),
        ) {
            // Laid out at its natural height whatever the window's is, so the
            // measurement below is what the content wants rather than what it
            // was given — and the window is sized to that.
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { size ->
                        // Window units are dp-scaled on the JetBrains Runtime, as
                        // `WindowState` assumes too.
                        val height = ceil(size.height / window.graphicsConfiguration.defaultTransform.scaleY).toInt()
                        if (height > 0 && height != window.height) window.setSize(window.width, height)
                    },
            ) {
                WindowDraggableArea(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).background(P.chrome).bottomBorder(P.line),
                        contentAlignment = Alignment.Center,
                    ) {
                        AddressBar(
                            host = "127.0.0.1",
                            port = port,
                            running = service.isRunning,
                            docked = false,
                            onToggleWidget = onDock,
                        )
                    }
                }
                WidgetBody(store, onInspect)
            }

            // The edge the user stretches it by, over everything else.
            Box(
                Modifier.align(Alignment.CenterEnd).width(EDGE).fillMaxHeight()
                    .windowResize(Cursor.E_RESIZE_CURSOR) { dx, _ ->
                        width = (width + dx).coerceAtLeast(MIN_WIDTH)
                        window.setSize(width, window.height)
                    },
            )
        }
    }
}

/**
 * Drags the window's edge with the pointer.
 *
 * Measured in screen coordinates rather than the gesture's own: the handle sits
 * on the edge it is moving, so in its own coordinates the pointer barely moves
 * at all and the resize would crawl. [onDrag] gets the step in window units,
 * which on the JetBrains Runtime are the same dp the layout uses.
 */
private fun Modifier.windowResize(cursor: Int, onDrag: (dx: Int, dy: Int) -> Unit): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown().consume()
            var last = MouseInfo.getPointerInfo().location
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                if (!change.pressed) break
                change.consume()
                val now = MouseInfo.getPointerInfo().location
                if (now != last) onDrag(now.x - last.x, now.y - last.y)
                last = now
            }
        }
    }

@Composable
private fun WidgetBody(store: SessionStore, onInspect: (String) -> Unit) {
    val rows = store.rows
    var openId by remember { mutableStateOf<String?>(null) }
    // Kept through the collapse, so the details have something to draw while
    // they shrink away rather than vanishing on the first frame of it.
    var shownId by remember { mutableStateOf<String?>(null) }
    // The traffic grid's own columns, narrowed to what fits the width.
    // Fresh instances, so the widths set here are the widget's alone. The
    // table's weights assume a grid several columns wide; at this width they
    // cut `200` and `CONNECT` short, so these are sized to fit them instead.
    val columns = remember {
        mutableStateListOf(
            *defaultColumns()
                .filter { it.key in WIDGET_COLUMNS }
                .onEach { col -> WIDGET_COLUMNS[col.key]?.let { col.weight = it } }
                .toTypedArray(),
        )
    }

    // The newest two, in the order the traffic grid shows them. Two references
    // to the store's own rows, not copies of them — and not a `subList` view,
    // which throws once the store has changed underneath it, as it does every
    // time a flow lands between frames.
    val newest = rows.takeLast(VISIBLE_ROWS)
    val style = remember { GridStyle() }
    DataGrid<TrafficRow, Nothing>(
        columns = columns,
        rows = newest,
        key = { it.id },
        // Two rows tall whether or not anything has been captured yet.
        modifier = Modifier.fillMaxWidth().height(style.headerHeight + style.rowHeight * VISIBLE_ROWS),
        selectedKey = openId,
        onSelect = { row ->
            if (openId == row.id) {
                openId = null
            } else {
                openId = row.id
                shownId = row.id
            }
        },
        style = style,
        empty = { EmptyState("Waiting for traffic…", centred = true) },
    )

    AnimatedVisibility(
        visible = openId != null,
        enter = expandVertically(tween(EXPAND_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(EXPAND_MS)),
        exit = shrinkVertically(tween(EXPAND_MS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(EXPAND_MS)),
    ) {
        // Looked up by id rather than held: the row keeps filling in while it
        // is open, and a flow evicted from the store simply closes.
        val row = shownId?.let(store::get)
        if (row == null) {
            Spacer(Modifier.height(0.dp))
        } else {
            FlowDetails(row, onInspect)
        }
    }
}

// --- details ---------------------------------------------------------------

@Composable
private fun FlowDetails(row: TrafficRow, onInspect: (String) -> Unit) {
    var side by remember { mutableStateOf(REQUEST) }
    Column(Modifier.fillMaxWidth().topBorder(P.line)) {
        // Names only: the sizes and time are in the cells right under them.
        TabContentSwitcher(
            tabs = listOf(TabLabel(REQUEST), TabLabel(RESPONSE)),
            selected = side,
            onSelect = { side = it },
        ) {
            if (side == REQUEST) RequestSide(row) else ResponseSide(row)
        }
        Row(
            Modifier.fillMaxWidth().background(P.bg).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Not focusable: a click would otherwise leave it focused, and the
            // theme draws a focused button with a text-coloured border. Its
            // whole job is to hand over to the main window, so there is no
            // reason for focus to stay here.
            PrimaryButton(
                "Inspect in Traffic",
                slim = true,
                modifier = Modifier.focusProperties { canFocus = false },
            ) { onInspect(row.id) }
        }
    }
}

@Composable
private fun RequestSide(row: TrafficRow) {
    // The inspector's own cells for this half, not a list of the widget's.
    SideMetaGrid(row, BodySide.REQUEST)
    // No time ribbon on this side: the phases are the round trip's, and they
    // belong with the response that completes it.
    SizeRibbon(row.request.request.headersSize, row.requestBodySize)
}

@Composable
private fun ResponseSide(row: TrafficRow) {
    SideMetaGrid(row, BodySide.RESPONSE)
    SizeRibbon(row.response?.response?.headersSize ?: 0, row.responseBodySize ?: 0)
    PhaseRibbon(row)
}

private const val REQUEST = "Request"
private const val RESPONSE = "Response"

/**
 * The traffic grid's columns the widget shows, by key, with their weights here.
 * The weights sum to [WIDGET_WIDTH], so at the default size each is its width
 * in dp; the grid keeps its own column order.
 */
private val WIDGET_COLUMNS = mapOf("st" to 64f, "method" to 84f, "url" to 312f)

/** The widget's corners — the radius Windows 11 gives its own windows. */
private val WindowShape = RoundedCornerShape(8.dp)

/** In window units, which are dp on the JetBrains Runtime. */
private const val WIDGET_WIDTH = 460
private const val MIN_WIDTH = 280
private const val SCREEN_MARGIN = 24

/** How far in from the edge a resize grab reaches. */
private val EDGE = 5.dp

/** The first frame's height, before the content has measured itself. */
private const val COLLAPSED_GUESS = 130

private const val VISIBLE_ROWS = 2
private const val EXPAND_MS = 220
