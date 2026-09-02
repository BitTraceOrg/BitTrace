package org.bittrace.components

import org.bittrace.ui.Typo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P
import org.bittrace.ui.RibbonLegend
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.topBorder
import kotlin.math.roundToInt

/**
 * One flow on the band, in milliseconds from the first captured request.
 *
 * Milliseconds rather than the fractions this used to hold: a fraction is only
 * meaningful against the span it was computed from, so every arriving flow
 * rescaled every existing bar and the picture crawled sideways under its own
 * data. Absolute offsets are computed once and stay put; the window decides
 * where they land on screen.
 */
private data class Bar(
    val id: String,
    val startMs: Double,
    val durMs: Double,
    val lane: Int,
    val err: Boolean,
    val https: Boolean,
    val color: Color,
)

/** The bars plus how long the whole capture runs, which the scroll map spans. */
private class Timeline(val bars: List<Bar>, val totalMs: Double)

private const val LANES = 5

/** The fixed width of the band's view onto the capture. */
private const val WINDOW_MS = 60_000.0

/** Gridline spacing — quarters of the window, so each one is a readable unit. */
private const val GRID_MS = 15_000.0

private fun computeTimeline(rows: List<TrafficRow>): Timeline {
    if (rows.isEmpty()) return Timeline(emptyList(), WINDOW_MS)
    val starts = rows.map { instantOf(it.request.startedDateTime)?.toEpochMilli() }
    val base = starts.filterNotNull().minOrNull() ?: return Timeline(emptyList(), WINDOW_MS)
    data class Raw(val row: TrafficRow, val off: Double, val dur: Double)
    val raw = rows.mapIndexed { i, r ->
        val off = starts[i]?.let { (it - base).toDouble() } ?: (i * 130.0)
        val dur = (r.response?.time ?: 30.0).coerceAtLeast(30.0)
        Raw(r, off, dur)
    }
    val laneEnds = DoubleArray(LANES) { Double.NEGATIVE_INFINITY }
    val bars = raw.mapIndexed { i, b ->
        var lane = 0
        while (lane < LANES && laneEnds[lane] > b.off) lane++
        if (lane == LANES) lane = i % LANES
        laneEnds[lane] = b.off + b.dur + 25
        Bar(
            id = b.row.id,
            startMs = b.off,
            durMs = b.dur,
            lane = lane,
            err = b.row.response?.error == true,
            https = b.row.request.tls.isNotBlank() && b.row.request.tls != "—",
            color = statusOf(b.row).second,
        )
    }
    // At least one window wide, so a capture shorter than a minute still has a
    // map to draw and the window still means the same span of time.
    val total = (raw.maxOf { it.off + it.dur }).coerceAtLeast(WINDOW_MS)
    return Timeline(bars, total)
}

/**
 * The Chrome-DevTools-style overview band (DESIGN.md §6.7), drawn with a Skia
 * [Canvas]: per-flow bars packed into lanes on a shared time axis, wait +
 * download split, red for errors. Click a bar to select the flow.
 *
 * The band shows a fixed minute rather than the whole capture. Fitting
 * everything meant that after an hour of traffic every flow was a sub-pixel
 * sliver and the band showed only that something had happened; at a fixed scale
 * a bar's width is a duration you can actually compare against the one beside
 * it. The scroll map underneath is what gives back the part the window hides.
 */
@Composable
fun Waterfall(rows: List<TrafficRow>, selectedId: String?, onSelect: (String) -> Unit) {
    val timeline = remember(rows.size, rows.lastOrNull()?.response, P.palette) { computeTimeline(rows) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var mapWidth by remember { mutableStateOf(0) }
    val laneH = 8f
    val gap = 2f
    val top = 12f

    // Where the window sits when it is not pinned to the live edge, and whether
    // it is. Capture is live, so following is the default — but a window that
    // jumped back to "now" while being read would make the map useless, so any
    // drag pins it, and dragging back to the end resumes following.
    var pinnedStart by remember { mutableStateOf(0.0) }
    var following by remember { mutableStateOf(true) }

    val maxStart = (timeline.totalMs - WINDOW_MS).coerceAtLeast(0.0)
    val windowStart = if (following) maxStart else pinnedStart.coerceIn(0.0, maxStart)

    /** Moves the window so [fraction] of the whole timeline is at its centre. */
    fun scrollTo(fraction: Float) {
        val centre = fraction.coerceIn(0f, 1f) * timeline.totalMs
        val start = (centre - WINDOW_MS / 2).coerceIn(0.0, maxStart)
        pinnedStart = start
        // Landing on the live edge is how you get back to following, so the
        // band does not need a separate control that says "resume".
        following = start >= maxStart - 1.0
    }

    Column(Modifier.fillMaxWidth().background(P.bg).bottomBorder(P.line)) {
        Box(Modifier.fillMaxWidth().height(BAND_HEIGHT)) {
            Canvas(
                Modifier.fillMaxWidth().height(BAND_HEIGHT)
                    .onSizeChanged { size = it }
                    .pointerInput(timeline, size, windowStart) {
                        detectTapGestures { pos ->
                            val w = size.width.toFloat()
                            val lane = ((pos.y - top) / (laneH + gap)).toInt()
                            timeline.bars.firstOrNull { bar ->
                                val x = ((bar.startMs - windowStart) / WINDOW_MS * w).toFloat()
                                val bw = (bar.durMs / WINDOW_MS * w).toFloat().coerceAtLeast(3f)
                                bar.lane == lane && pos.x >= x && pos.x <= x + bw
                            }?.let { onSelect(it.id) }
                        }
                    },
            ) {
                val w = this.size.width
                fun xOf(ms: Double) = ((ms - windowStart) / WINDOW_MS * w).toFloat()

                // Gridlines on absolute time, so they scroll with the flows
                // rather than sliding under them as the window moves.
                var tick = kotlin.math.ceil(windowStart / GRID_MS) * GRID_MS
                while (tick < windowStart + WINDOW_MS) {
                    val x = xOf(tick)
                    drawLine(P.line2, Offset(x, 0f), Offset(x, this.size.height), 1f)
                    tick += GRID_MS
                }

                for (b in timeline.bars) {
                    // Off-window flows are skipped rather than drawn off-canvas:
                    // at an hour of capture that is most of them.
                    if (b.startMs + b.durMs < windowStart || b.startMs > windowStart + WINDOW_MS) continue
                    val x = xOf(b.startMs)
                    val bw = (b.durMs / WINDOW_MS * w).toFloat().coerceAtLeast(3f)
                    val y = top + b.lane * (laneH + gap)
                    if (b.err) {
                        drawRect(P.err, Offset(x, y), Size(bw, laneH))
                    } else {
                        val waitFrac = if (b.https) 0.62f else 0.70f
                        drawRect(P.waitBar, Offset(x, y), Size(bw * waitFrac, laneH))
                        drawRect(b.color, Offset(x + bw * waitFrac, y), Size(bw * (1 - waitFrac), laneH))
                    }
                    if (b.id == selectedId) {
                        drawRect(P.accent, Offset(x - 1, y - 1), Size(bw + 2, laneH + 2), style = Stroke(1f))
                    }
                }
                // The playhead is "now", so it only belongs on a window that
                // reaches the end of the capture.
                if (following) drawRect(P.accent, Offset(w - 2, 0f), Size(2f, this.size.height))
            }
            // Legend overlay (top-right).
            Row(
                Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same key a [Ribbon] draws, at the band's smaller step: this
                // is one bar chart's legend whether the bar is laid out or painted.
                RibbonLegend(P.waitBar, "wait", Typo.micro)
                RibbonLegend(P.ok, "download", Typo.micro)
                RibbonLegend(P.err, "failed", Typo.micro)
            }
        }

        ScrollMap(
            timeline = timeline,
            windowStart = windowStart,
            width = mapWidth,
            onWidth = { mapWidth = it },
            onScrollTo = ::scrollTo,
        )
    }
}

/**
 * The whole capture as a density strip, with the band's window drawn on it.
 *
 * Density rather than one mark per flow: a mark each would be thousands of draw
 * calls for a picture only a few hundred pixels wide, and the marks would land
 * on top of each other anyway. Bucketing by pixel column costs one pass over the
 * flows and says the thing the strip is for — where the traffic actually was.
 */
@Composable
private fun ScrollMap(
    timeline: Timeline,
    windowStart: Double,
    width: Int,
    onWidth: (Int) -> Unit,
    onScrollTo: (Float) -> Unit,
) {
    // Recomputed only when the flows or the width change — not as the window
    // moves, which is the thing that changes most often.
    val density = remember(timeline, width) { densityOf(timeline, width) }

    Canvas(
        Modifier.fillMaxWidth().height(MAP_HEIGHT).background(P.chrome).topBorder(P.line)
            .pointerHoverIcon(PointerIcon.Hand)
            .onSizeChanged { onWidth(it.width) }
            .pointerInput(width) {
                detectTapGestures { pos -> onScrollTo(pos.x / size.width.toFloat()) }
            }
            .pointerInput(width) {
                // Grabbing anywhere and dragging moves the window to the pointer,
                // which is how a scrollbar's track behaves — and means you never
                // have to hit the window rectangle itself to move it.
                detectHorizontalDragGestures { change, _ ->
                    onScrollTo(change.position.x / size.width.toFloat())
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val peak = density.maxOrNull() ?: 0

        if (peak > 0) {
            density.forEachIndexed { column, count ->
                if (count == 0) return@forEachIndexed
                // A single flow still gets a visible mark: the strip is a map of
                // where traffic is, and one request in a quiet minute is exactly
                // what you are looking for when you scroll back.
                val barH = (h * (count.toFloat() / peak)).coerceAtLeast(2f)
                drawRect(P.faint, Offset(column.toFloat(), h - barH), Size(1f, barH))
            }
        }

        // The window, as a filled pane with an edge — the same accent wash a
        // selected status cell uses, so "this is the part you are looking at"
        // reads the same way twice in the app.
        val x = (windowStart / timeline.totalMs * w).toFloat()
        val ww = (WINDOW_MS / timeline.totalMs * w).toFloat().coerceAtLeast(6f)
        drawRect(P.accentFill, Offset(x, 0f), Size(ww, h))
        drawRect(P.accent, Offset(x, 0f), Size(ww, h), style = Stroke(1f))
    }
}

/** Flows per pixel column across the whole timeline. */
private fun densityOf(timeline: Timeline, width: Int): IntArray {
    if (width <= 0) return IntArray(0)
    val counts = IntArray(width)
    timeline.bars.forEach { bar ->
        val column = (bar.startMs / timeline.totalMs * width).roundToInt().coerceIn(0, width - 1)
        counts[column]++
    }
    return counts
}

/** The band itself — five lanes and the legend above them. */
private val BAND_HEIGHT = 66.dp

/** The scroll map. Tall enough to grab, short enough not to be a second chart. */
private val MAP_HEIGHT = 16.dp
