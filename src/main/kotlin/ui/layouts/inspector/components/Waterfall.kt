package org.bittrace.ui.layouts.inspector.components

import org.bittrace.ui.instantOf
import org.bittrace.ui.statusOf
import org.bittrace.ui.Typo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P
import org.bittrace.ui.components.RibbonLegend

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

/**
 * The fixed width of the band's view onto the capture.
 *
 * Public because the brush strip below the lanes has to be able to select
 * exactly this much: the two are one window seen at two scales, and a strip
 * whose default selection did not match what the lanes were showing would be
 * two windows disagreeing in the same 100 pixels.
 */
const val WINDOW_MS = 60_000.0

private fun computeTimeline(rows: List<TrafficRow>, origin: Long?): Timeline {
    if (rows.isEmpty()) return Timeline(emptyList(), WINDOW_MS)
    val starts = rows.map { instantOf(it.request.startedDateTime)?.toEpochMilli() }
    // The capture's own origin when the caller knows it, not the earliest row
    // here. Filtering hands this function a subset, and re-basing on the subset
    // would slide the whole axis every time the query changed — so an offset
    // would mean something different from one frame to the next, and a brushed
    // window computed against the capture could not be drawn on it.
    val base = origin ?: starts.filterNotNull().minOrNull() ?: return Timeline(emptyList(), WINDOW_MS)
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
 * The Chrome-DevTools-style overview band, drawn with a Skia
 * [Canvas]: per-flow bars packed into lanes on a shared time axis, wait +
 * download split, red for errors. Click a bar to select the flow.
 *
 * The band shows a window onto the capture rather than the whole of it. Fitting
 * everything meant that after an hour of traffic every flow was a sub-pixel
 * sliver and the band showed only that something had happened; over a window a
 * bar's width is a duration you can actually compare against the one beside it.
 *
 * Which window is not this composable's decision — it is [brushed], the strip
 * below. The lanes are the brush seen close up, so there is no scroll state
 * here and nothing to keep in sync: a minute by default because that is what
 * the strip selects by default, and as wide as you drag it after that.
 */
@Composable
fun Waterfall(
    rows: List<TrafficRow>,
    selectedId: String?,
    /** Milliseconds are measured from here, so filtering never re-bases the axis. */
    origin: Long?,
    /** The brushed time window, which the band scrolls to and draws. */
    brushed: ClosedFloatingPointRange<Double>?,
    onSelect: (String) -> Unit,
) {
    val timeline = remember(rows.size, rows.lastOrNull()?.response, origin, P.palette) {
        computeTimeline(rows, origin)
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val laneH = 8f
    val gap = 2f
    val top = 12f

    // The window is the brush, in full. Nothing is remembered and nothing is
    // clamped to a minute: a fixed width here would have quietly contradicted a
    // wider selection, showing its first minute while the strip drew the whole
    // thing highlighted.
    val windowStart = brushed?.start ?: 0.0
    val windowMs = brushed?.let { (it.endInclusive - it.start).coerceAtLeast(1.0) } ?: WINDOW_MS

    // Quarters of whatever the window is, so the gridlines stay four readable
    // units instead of becoming an hour's worth of hairlines at 15s apiece.
    val gridMs = windowMs / 4

    Box(Modifier.fillMaxWidth().height(BAND_HEIGHT).background(P.bg)) {
            Canvas(
                Modifier.fillMaxWidth().height(BAND_HEIGHT)
                    .onSizeChanged { size = it }
                    .pointerInput(timeline, size, windowStart, windowMs) {
                        detectTapGestures { pos ->
                            val w = size.width.toFloat()
                            val lane = ((pos.y - top) / (laneH + gap)).toInt()
                            timeline.bars.firstOrNull { bar ->
                                val x = ((bar.startMs - windowStart) / windowMs * w).toFloat()
                                val bw = (bar.durMs / windowMs * w).toFloat().coerceAtLeast(3f)
                                bar.lane == lane && pos.x >= x && pos.x <= x + bw
                            }?.let { onSelect(it.id) }
                        }
                    },
            ) {
                val w = this.size.width
                fun xOf(ms: Double) = ((ms - windowStart) / windowMs * w).toFloat()

                // Gridlines on absolute time, so they scroll with the flows
                // rather than sliding under them as the window moves.
                var tick = kotlin.math.ceil(windowStart / gridMs) * gridMs
                while (tick < windowStart + windowMs) {
                    val x = xOf(tick)
                    drawLine(P.line2, Offset(x, 0f), Offset(x, this.size.height), 1f)
                    tick += gridMs
                }

                for ((id, startMs, durMs, lane, err, https, color) in timeline.bars) {
                    // Off-window flows are skipped rather than drawn off-canvas:
                    // at an hour of capture that is most of them.
                    if (startMs + durMs < windowStart || startMs > windowStart + windowMs) continue
                    val x = xOf(startMs)
                    val bw = (durMs / windowMs * w).toFloat().coerceAtLeast(3f)
                    val y = top + lane * (laneH + gap)
                    if (err) {
                        drawRect(P.err, Offset(x, y), Size(bw, laneH))
                    } else {
                        val waitFrac = if (https) 0.62f else 0.70f
                        drawRect(P.waitBar, Offset(x, y), Size(bw * waitFrac, laneH))
                        drawRect(color, Offset(x + bw * waitFrac, y), Size(bw * (1 - waitFrac), laneH))
                    }
                    if (id == selectedId) {
                        drawRect(P.accent, Offset(x - 1, y - 1), Size(bw + 2, laneH + 2), style = Stroke(1f))
                    }
                }
                // The playhead is "now", so it only belongs on a window that
                // reaches the end of the capture.
                if (windowStart + windowMs >= timeline.totalMs - 1) {
                    drawRect(P.accent, Offset(w - 2, 0f), Size(2f, this.size.height))
                }
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
}

/** The band itself — five lanes and the legend above them. */
private val BAND_HEIGHT = 66.dp

