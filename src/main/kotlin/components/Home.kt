package org.bittrace.components

import androidx.compose.foundation.layout.height
import org.bittrace.data.ActivityStore
import java.time.LocalDate
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bittrace.data.SessionStore
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.Dot
import org.bittrace.ui.P
import org.bittrace.ui.PrimaryButton
import org.bittrace.ui.PzText
import org.bittrace.ui.Segment
import org.bittrace.ui.SegmentedToggle
import org.bittrace.ui.border1
import org.bittrace.ui.bottomBorder

/**
 * Nerdy welcome lines: the leading half is rendered in the body colour, the
 * trailing half in the accent.
 */
private val Greetings = listOf(
    "Welcome back, " to "traveler.",
    "Don't panic, " to "and always know where your towel is.",
    "May the packets be with you, " to "always.",
    "Live long and " to "inspect traffic.",
    "It's dangerous to go alone, " to "take this proxy.",
    "You are the one, " to "Neo of the network.",
    "Winter is coming, " to "so is the timeout.",
    "One does not simply " to "walk into production.",
    "Roads? Where we're going " to "we need no roads.",
    "So say we all, " to "and so say the sockets.",
)

/**
 * The Home dashboard: proxy status, session KPIs, and an activity heatmap —
 * rendered in the Precision palette. Counts come from the live [store].
 */
@Composable
fun HomeView(
    store: SessionStore,
    service: ProxyService,
    activity: ActivityStore,
    port: Int,
    onOpenCapture: () -> Unit,
) {
    val rows = store.rows
    val failed = rows.count { r -> r.response?.let { it.error || it.response.status >= 400 } == true }
    val succeeded = rows.count { r -> r.response?.let { !it.error && it.response.status < 400 } == true }
    val totalSize = rows.sumOf { (it.response?.response?.bodySize ?: 0L).coerceAtLeast(0L) }
    // Drives both the toggle and how many days the heatmap draws.
    var rangeDays by remember { mutableStateOf(30) }
    // Picked once per screen entry so the greeting stays put while you use it.
    val greeting = remember { Greetings.random() }

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
            PzText(if (service.isRunning) "Navigation online" else "Navigation offline", color = P.accent, style = Typo.caption, family = P.Ui)
        }
        Row {
            PzText(greeting.first, color = P.text, style = Typo.display, family = P.Ui, weight = FontWeight.Bold)
            PzText(greeting.second, color = P.accent, style = Typo.display, family = P.Ui, weight = FontWeight.Bold)
        }

        // ── proxy status strip ─────────────────────────────────────────
        Column(panel().padding(0.dp)) {
            Row(
                Modifier.fillMaxWidth().bottomBorder(P.line).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(if (service.isRunning) P.ok else P.err, 8)
                Spacer(Modifier.width(8.dp))
                PzText(if (service.isRunning) "Proxy running" else "Proxy stopped", color = P.text, style = Typo.body, family = P.Ui)
                Spacer(Modifier.weight(1f))
                Box(Modifier.background(P.bg).border1(P.line).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    PzText("localhost:$port", color = P.dim, style = Typo.label)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatCell("PID", service.pid ?: "—")
                StatCell("Uptime", uptime(service.uptimeSeconds))
                StatCell("TLS", "v1.3")
                StatCell("Root cert", "trusted", P.ok)
            }
        }

        // ── actions ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Open Capture Tab", onClick = onOpenCapture)
            Spacer(Modifier.width(16.dp))
            PzText("Troubleshooting", color = P.dim, style = Typo.body, family = P.Ui)
        }

        // ── overview heading + range toggle ──────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PzText("Overview", color = P.text, style = Typo.h2, family = P.Ui, weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SegmentedToggle(
                segments = listOf(Segment("30", "30d"), Segment("7", "7d")),
                selected = rangeDays.toString(),
            ) { rangeDays = it.toIntOrNull() ?: 30 }
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
            Heatmap(activity.recent(rangeDays))
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PzText("Last $rangeDays days", color = P.faint, style = Typo.micro)
                Spacer(Modifier.weight(1f))
                PzText("Less", color = P.faint, style = Typo.micro)
                Spacer(Modifier.width(6.dp))
                heatRamp().forEach { c -> Box(Modifier.size(10.dp).background(c)); Spacer(Modifier.width(3.dp)) }
                PzText("More", color = P.faint, style = Typo.micro)
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

/**
 * The activity strip, wrapped a week to a row.
 *
 * A month laid out as thirty cells in one line gives each day a sliver a few
 * pixels wide, where the colour is the only thing left and comparing two of them
 * is guesswork. Seven to a row puts the same day of the week in the same column,
 * so a Tuesday spike reads as a Tuesday spike — which is the shape anybody looks
 * at a month of traffic for.
 */
@Composable
private fun Heatmap(days: List<Pair<LocalDate, Int>>) {
    val heat = heatRamp()
    // Scaled against the busiest day on show, not an absolute, so a quiet week
    // still has shape instead of reading as uniformly empty.
    val busiest = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val weeks = days.chunked(DAYS_PER_ROW)
    // One row keeps the band it always had; five of them would be a wall, so the
    // cells lose height as they gain rows.
    val cellHeight = if (weeks.size > 1) 14.dp else 22.dp

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { (_, count) ->
                    val level = if (count == 0) 0 else (1 + (count.toFloat() / busiest * 3f).toInt()).coerceIn(1, 4)
                    Box(Modifier.weight(1f).height(cellHeight).background(heat[level]))
                }
                // A range that does not divide by seven leaves the last row
                // short. Padding it keeps the columns lined up, rather than
                // stretching two days across the width of seven.
                repeat(DAYS_PER_ROW - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A week to a row, which is what makes the columns mean something. */
private const val DAYS_PER_ROW = 7

@Composable
private fun StatCell(caption: String, value: String, valueColor: Color = P.text) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PzText(caption, color = P.faint, style = Typo.micro, family = P.Ui)
        PzText(value, color = valueColor, style = Typo.body)
    }
}

@Composable
private fun Kpi(modifier: Modifier, label: String, value: String, valueColor: Color) {
    Column(modifier.then(panel()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PzText(label, color = P.faint, style = Typo.micro, family = P.Ui)
        PzText(value, color = valueColor, style = Typo.display)
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
