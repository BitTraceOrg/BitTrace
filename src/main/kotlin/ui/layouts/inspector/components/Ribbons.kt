package org.bittrace.ui.layouts.inspector.components

import androidx.compose.runtime.Composable
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P
import org.bittrace.ui.bytesStr
import org.bittrace.ui.components.Ribbon
import org.bittrace.ui.components.RibbonSlice

/**
 * A flow's timing phases as one bar — where the round trip's time went.
 *
 * Shared by the inspector's response overview and the capture widget, so a
 * flow's phases read the same in both. Draws nothing until the response has
 * reported timings.
 */
@Composable
fun PhaseRibbon(row: TrafficRow) {
    val phases = phasesOf(row)
    if (phases.isEmpty()) return
    val total = (row.response?.time ?: phases.sumOf { it.ms }).toLong()

    Ribbon(
        phases.map { RibbonSlice(it.ms.toFloat(), it.color, "${it.name} ${it.ms.toLong()}ms") },
        total = "total $total ms"
    )
}

/**
 * The same bar over a different quantity: the transfer split into header versus
 * body bytes. Shown by both the request and the response overview, and by the
 * capture widget.
 */
@Composable
fun SizeRibbon(headerBytes: Long, bodyBytes: Long) {
    val h = headerBytes.coerceAtLeast(0)
    val b = bodyBytes.coerceAtLeast(0)
    Ribbon(
        listOf(
            RibbonSlice(h.toFloat(), P.info, "headers ${bytesStr(headerBytes)}"),
            RibbonSlice(b.toFloat(), P.ok, "body ${bytesStr(bodyBytes)}"),
        ),
        total = "total ${bytesStr(h + b)}",
    )
}
