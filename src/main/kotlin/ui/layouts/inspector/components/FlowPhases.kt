package org.bittrace.ui.layouts.inspector.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P

/** One timing phase of a flow: label, duration in ms, and its ribbon colour. */
data class Phase(val name: String, val ms: Double, val color: Color)

/**
 * The flow's non-empty timing phases in wire order. Shared by the inspector's
 * full ribbon and the table's compact one so both colour a phase identically.
 */
fun phasesOf(row: TrafficRow): List<Phase> {
    val t = row.response?.timings ?: return emptyList()
    return listOf(
        Phase("blocked", t.blocked, P.faint),
        // dns and send have no token of their own; lifting the neighbouring
        // token towards the text colour keeps all seven phases apart while
        // still following the theme, which two literals never did.
        Phase("dns", t.dns, lerp(P.accent, P.text, 0.45f)),
        Phase("connect", t.connect, P.info),
        Phase("tls", t.ssl, P.accent),
        Phase("send", t.send, lerp(P.ok, P.text, 0.45f)),
        Phase("wait", t.wait, P.warn),
        Phase("receive", t.receive, P.ok),
    ).filter { it.ms > 0 }
}
