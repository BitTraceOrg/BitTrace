package org.bittrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.bittrace.components.instantOf
import org.bittrace.components.statusOf
import org.bittrace.data.TrafficRow

/** Fraction-based bar geometry (independent of pixel width). */
private data class Bar(
    val id: String,
    val xFrac: Float,
    val wFrac: Float,
    val lane: Int,
    val err: Boolean,
    val https: Boolean,
    val color: androidx.compose.ui.graphics.Color,
)

private const val LANES = 5

private fun computeBars(rows: List<TrafficRow>): List<Bar> {
    if (rows.isEmpty()) return emptyList()
    val starts = rows.map { instantOf(it.request.startedDateTime)?.toEpochMilli() }
    val base = starts.filterNotNull().minOrNull() ?: return emptyList()
    data class Raw(val row: TrafficRow, val off: Double, val dur: Double)
    val raw = rows.mapIndexed { i, r ->
        val off = starts[i]?.let { (it - base).toDouble() } ?: (i * 130.0)
        val dur = (r.response?.time ?: 30.0).coerceAtLeast(30.0)
        Raw(r, off, dur)
    }
    val span = (raw.maxOf { it.off + it.dur } * 1.04).coerceAtLeast(1.0)
    val laneEnds = DoubleArray(LANES) { Double.NEGATIVE_INFINITY }
    return raw.mapIndexed { i, b ->
        var lane = 0
        while (lane < LANES && laneEnds[lane] > b.off) lane++
        if (lane == LANES) lane = i % LANES
        laneEnds[lane] = b.off + b.dur + 25
        Bar(
            id = b.row.id,
            xFrac = (b.off / span).toFloat(),
            wFrac = (b.dur / span).toFloat(),
            lane = lane,
            err = b.row.response?.error == true,
            https = b.row.request.tls.isNotBlank() && b.row.request.tls != "—",
            color = statusOf(b.row).second,
        )
    }
}

/**
 * The Chrome-DevTools-style overview band (DESIGN.md §6.7), drawn with a Skia
 * [Canvas]: per-flow bars packed into lanes on a shared time axis, wait + download
 * split, red for errors. Click a bar to select the flow.
 */
@Composable
fun Waterfall(rows: List<TrafficRow>, selectedId: String?, onSelect: (String) -> Unit) {
    val bars = remember(rows.size, rows.lastOrNull()?.response, P.palette) { computeBars(rows) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val laneH = 8f
    val gap = 2f
    val top = 12f

    Box(Modifier.fillMaxWidth().height(66.dp).background(P.bg).bottomBorder(P.line)) {
        Canvas(
            Modifier.fillMaxWidth().height(66.dp)
                .onSizeChanged { size = it }
                .pointerInput(bars, size) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat()
                        val lane = ((pos.y - top) / (laneH + gap)).toInt()
                        bars.firstOrNull {
                            it.lane == lane && pos.x >= it.xFrac * w &&
                                pos.x <= it.xFrac * w + (it.wFrac * w).coerceAtLeast(3f)
                        }?.let { onSelect(it.id) }
                    }
                },
        ) {
            val w = this.size.width
            // Faint gridlines at 25% steps.
            for (k in 1..4) {
                val x = w * k / 5f
                drawLine(P.line2, Offset(x, 0f), Offset(x, this.size.height), 1f)
            }
            for (b in bars) {
                val x = b.xFrac * w
                val bw = (b.wFrac * w).coerceAtLeast(3f)
                val y = top + b.lane * (laneH + gap)
                if (b.err) {
                    drawRect(P.err, Offset(x, y), Size(bw, laneH))
                } else {
                    val waitFrac = if (b.https) 0.62f else 0.70f
                    drawRect(P.waitBar, Offset(x, y), Size(bw * waitFrac, laneH))
                    drawRect(b.color, Offset(x + bw * waitFrac, y), Size(bw * (1 - waitFrac), laneH))
                }
                if (b.id == selectedId) {
                    drawRect(
                        P.accent, Offset(x - 1, y - 1), Size(bw + 2, laneH + 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1f),
                    )
                }
            }
            // Playhead at the right edge.
            drawRect(P.accent, Offset(w - 2, 0f), Size(2f, this.size.height))
        }
        // Legend overlay (top-right).
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            legendItem("wait", P.waitBar)
            legendItem("download", P.ok)
            legendItem("failed", P.err)
        }
    }
}

@Composable
private fun legendItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).drawBehind { drawRect(color) })
        PzText(" $label", color = P.faint, size = 10)
    }
}
