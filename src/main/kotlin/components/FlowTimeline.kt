package org.bittrace.components

import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import org.bittrace.data.TrafficRow
import org.bittrace.ui.P
import org.bittrace.ui.PzText

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

/**
 * The detailed-mode second line: the same timing breakdown the inspector shows,
 * squeezed into one table row.
 *
 * Space is the constraint here, so the legend is dropped — the bar itself is the
 * data, phases keep the inspector's colours, and only the slice wide enough to
 * hold it gets an inline label. The total sits at the right, where the SIZE/TIME
 * columns already put numbers.
 */
@Composable
fun FlowTimeline(row: TrafficRow) {
    val phases = phasesOf(row)
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phases.isEmpty()) {
            PzText(
                if (row.response == null) "awaiting response" else "no timing data",
                color = P.faint, style = Typo.caption, softWrap = false,
            )
            return@Row
        }

        val total = phases.sumOf { it.ms }
        Row(Modifier.weight(1f).fillMaxHeight()) {
            phases.forEach { phase ->
                val share = (phase.ms / total).toFloat()
                Box(
                    Modifier.weight(share).fillMaxHeight().background(phase.color),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Only label a slice with room for it; narrow ones stay bare
                    // rather than clipping a half-word.
                    if (share > 0.14f) {
                        PzText(
                            phase.name, color = P.bg, style = Typo.micro, family = P.Ui,
                            softWrap = false, modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        PzText("${total.toLong()} ms", color = P.faint, style = Typo.caption, softWrap = false)
    }
}
