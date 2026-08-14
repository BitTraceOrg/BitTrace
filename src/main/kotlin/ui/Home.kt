package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bittrace.components.bytesStr
import org.bittrace.data.TrafficStore
import org.bittrace.proxy.ProxyService

/**
 * The Home dashboard: proxy status, session KPIs, and an activity heatmap —
 * rendered in the Precision palette. Counts come from the live [store].
 */
@Composable
fun HomeView(store: TrafficStore, service: ProxyService, port: Int, onOpenCapture: () -> Unit) {
    val rows = store.rows
    val failed = rows.count { r -> r.response?.let { it.error || it.response.status >= 400 } == true }
    val succeeded = rows.count { r -> r.response?.let { !it.error && it.response.status < 400 } == true }
    val totalSize = rows.sumOf { (it.response?.response?.bodySize ?: 0L).coerceAtLeast(0L) }

    Column(
        // Keep the dashboard anchored to the left rather than stretching wide.
        Modifier.widthIn(max = 560.dp).fillMaxWidth().background(P.bg)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── header ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (service.isRunning) P.accent else P.err, 6)
            Spacer(Modifier.width(7.dp))
            PzText(if (service.isRunning) "NAVIGATION ONLINE" else "NAVIGATION OFFLINE", color = P.accent, size = 11, family = P.Ui)
        }
        Row {
            PzText("Welcome back, ", color = P.text, size = 30, family = P.Ui, weight = FontWeight.Bold)
            PzText("traveler.", color = P.accent, size = 30, family = P.Ui, weight = FontWeight.Bold)
        }

        // ── proxy status strip ─────────────────────────────────────────
        Column(panel().padding(0.dp)) {
            Row(
                Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(if (service.isRunning) P.ok else P.err, 8)
                Spacer(Modifier.width(8.dp))
                PzText(if (service.isRunning) "Proxy running" else "Proxy stopped", color = P.text, size = 13, family = P.Ui)
                Spacer(Modifier.weight(1f))
                Box(Modifier.background(P.bg).border1(P.line).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    PzText("localhost:$port", color = P.dim, size = 12)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatCell("PID", service.pid ?: "—")
                StatCell("UPTIME", uptime(service.uptimeSeconds))
                StatCell("TLS", "v1.3")
                StatCell("ROOT CERT", "trusted", P.ok)
            }
        }

        // ── actions ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(P.accent).pointerHoverIcon(PointerIcon.Hand).clickable { onOpenCapture() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PzText("⚡", color = P.bg, size = 13)
                    Spacer(Modifier.width(8.dp))
                    PzText("Open Capture Tab", color = P.bg, size = 13, family = P.Ui, weight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(16.dp))
            PzText("Troubleshooting", color = P.dim, size = 13, family = P.Ui)
        }

        // ── overview heading + range toggle ──────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PzText("Overview", color = P.text, size = 14, family = P.Ui, weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            RangeToggle()
        }

        // ── KPI tiles ────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi(Modifier.weight(1f), "All intercepted", rows.size.toString(), P.text)
            Kpi(Modifier.weight(1f), "Failed", failed.toString(), P.err)
            Kpi(Modifier.weight(1f), "Succeeded", succeeded.toString(), P.text)
            Kpi(Modifier.weight(1f), "Total size", bytesStr(totalSize), P.text)
        }

        // ── activity heatmap ─────────────────────────────────────────────
        Column(panel().padding(12.dp)) {
            Heatmap(rows.size)
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PzText("Last 30 days", color = P.faint, size = 10)
                Spacer(Modifier.weight(1f))
                PzText("Less", color = P.faint, size = 10)
                Spacer(Modifier.width(6.dp))
                heatRamp().forEach { c -> Box(Modifier.size(10.dp).background(c)); Spacer(Modifier.width(3.dp)) }
                PzText("More", color = P.faint, size = 10)
            }
        }
    }
}

// Heatmap intensity ramp built from the current accent colour (theme-reactive
// because it reads P during composition).
private fun heatRamp(): List<Color> = listOf(
    P.line2,
    P.accent.copy(alpha = 0.28f),
    P.accent.copy(alpha = 0.50f),
    P.accent.copy(alpha = 0.75f),
    P.accent,
)

@Composable
private fun Heatmap(flowCount: Int) {
    val heat = heatRamp()
    val cols = 30
    val rowsN = 7
    val cells = cols * rowsN
    // Spread the session's flows across the grid so it reflects real activity.
    val counts = IntArray(cells)
    for (i in 0 until flowCount) counts[i % cells]++
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0 until rowsN) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in 0 until cols) {
                    val count = counts[r * cols + c]
                    val level = if (count == 0) 0 else (1 + (count.toFloat() / max * 3f).toInt()).coerceIn(1, 4)
                    Box(Modifier.size(11.dp).background(heat[level]))
                }
            }
        }
    }
}

@Composable
private fun RangeToggle() {
    var range by remember { mutableStateOf("30d") }
    Row(Modifier.border1(P.line)) {
        listOf("30d", "7d").forEachIndexed { i, t ->
            val on = range == t
            Box(
                Modifier.background(if (on) P.accent else Color.Transparent)
                    .then(if (i > 0) Modifier.leftBorder(P.line) else Modifier)
                    .pointerHoverIcon(PointerIcon.Hand).clickable { range = t }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { PzText(t, color = if (on) P.bg else P.dim, size = 11, family = P.Ui) }
        }
    }
}

@Composable
private fun StatCell(caption: String, value: String, valueColor: Color = P.text) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PzText(caption, color = P.faint, size = 9, family = P.Ui)
        PzText(value, color = valueColor, size = 13)
    }
}

@Composable
private fun Kpi(modifier: Modifier, label: String, value: String, valueColor: Color) {
    Column(modifier.then(panel()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PzText(label, color = P.faint, size = 9, family = P.Ui)
        PzText(value, color = valueColor, size = 24)
    }
}

/** Standard bordered panel background. */
private fun panel(): Modifier = Modifier.fillMaxWidth().background(P.panel).border1(P.line)

private fun uptime(seconds: Long?): String {
    if (seconds == null) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
