package org.bittrace.components

import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.leftBorder
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The status bar — LOGS toggle, live counts and how much has been captured.
 *
 * The ok/failed counts double as filters, which is why they are cells with a
 * click rather than plain readouts.
 */
@Composable
fun StatusBar(
    flows: Int,
    ok: Int,
    failed: Int,
    /** Bytes seen across every captured flow, headers and bodies both. */
    captured: Long,
    /**
     * What the nerd panel reports, read when it opens rather than every frame —
     * one of the numbers walks every captured row to produce its estimate.
     */
    nerdStats: () -> NerdStats,
    logsOpen: Boolean,
    warn: Int,
    error: Int,
    okFilterOn: Boolean = false,
    failedFilterOn: Boolean = false,
    onFilterOk: () -> Unit = {},
    onFilterFailed: () -> Unit = {},
    onToggleLogs: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(P.chrome).topBorder(P.line),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LOGS toggle — opens the floating log panel; shows warn/error counts.
        val tint = if (logsOpen) P.accent else P.dim
        StatusCell(border = true, wash = logsOpen, onClick = onToggleLogs) {
            Icon(
                key = if (logsOpen) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                contentDescription = if (logsOpen) "Hide the proxy log" else "Show the proxy log",
                tint = tint,
            )
            Spacer(Modifier.width(7.dp))
            PzText("Logs", color = tint, style = Typo.label, family = P.Ui)
            if (warn > 0) { Spacer(Modifier.width(6.dp)); PzText("$warn", color = P.warn, style = Typo.label) }
            if (error > 0) { Spacer(Modifier.width(6.dp)); PzText("$error", color = P.err, style = Typo.label) }
        }
        StatusCell(border = true) { PzText("$flows flows", color = P.dim, style = Typo.label) }
        // The counts double as filters, and they are toggles: clicking one shows
        // only those flows, clicking it again clears it. The cell's fill is what
        // says which state it is in — a close icon spelled the same thing out a
        // second time, and a swatch beside the count said in a box what the
        // count can say in its own colour.
        StatusCell(border = true, active = okFilterOn, onClick = onFilterOk) {
            PzText("$ok ok", color = P.ok, style = Typo.label)
        }
        StatusCell(border = true, active = failedFilterOn, onClick = onFilterFailed) {
            PzText("$failed failed", color = P.err, style = Typo.label)
        }
        Spacer(Modifier.weight(1f))
        // How much has come through, on the right where the readouts live. The
        // proxy target used to sit here and the title bar already carries it,
        // so the bar said the same thing twice and this said nothing.
        StatusCell(left = true) {
            PzText("${bytesStr(captured)} captured", color = P.dim, style = Typo.label)
        }
        NerdStatsCell(nerdStats)
    }
}

/** What the nerd panel shows about the traffic the app is holding. */
class NerdStats(val flows: Int, val trafficBytes: Long, val bodyBytes: Long)

/**
 * A readout for when you want to know what the app is costing you.
 *
 * Everything in it is sampled only while the tooltip is open. That matters for
 * the frame counter in particular: measuring frames means asking for one, over
 * and over, which keeps the window rendering continuously — fine for the second
 * you are looking at the number, not something to leave running behind a status
 * bar nobody is reading.
 */
@Composable
private fun NerdStatsCell(stats: () -> NerdStats) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(Modifier.fillMaxHeight().hoverable(interaction)) {
        StatusCell(left = true, active = hovered) {
            PzText("Nerd stats", color = if (hovered) P.text else P.dim, style = Typo.label, family = P.Ui)
        }
        if (hovered) {
            // A plain popup rather than Jewel's Tooltip, which is still an
            // experimental API — and this panel wants to sit above the bar and
            // hold rows of its own anyway, which is more than a tooltip is for.
            Popup(popupPositionProvider = AbovePopup, onDismissRequest = {}) {
                Box(Modifier.background(P.panel).border(1.dp, P.line).padding(10.dp)) {
                    NerdStatsPanel(stats)
                }
            }
        }
    }
}

/**
 * Places the panel directly above its cell.
 *
 * The status bar is the bottom edge of the window, so anything anchored below it
 * would be off-screen. Clamped horizontally so a cell near the right edge does
 * not push the panel out of the window.
 */
private object AbovePopup : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun NerdStatsPanel(stats: () -> NerdStats) {
    var heapUsed by remember { mutableStateOf(0L) }
    var heapMax by remember { mutableStateOf(0L) }
    var fps by remember { mutableStateOf(0) }
    val traffic = remember { stats() }

    LaunchedEffect(Unit) {
        var frames = 0
        var since = 0L
        while (true) {
            withFrameNanos { now ->
                if (since == 0L) since = now
                frames++
                if (now - since >= 1_000_000_000L) {
                    fps = frames
                    frames = 0
                    since = now
                    // Sampled on the same tick as the frame count, so the two
                    // numbers always describe the same moment.
                    val runtime = Runtime.getRuntime()
                    heapUsed = runtime.totalMemory() - runtime.freeMemory()
                    heapMax = runtime.maxMemory()
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Stat("Heap", if (heapUsed == 0L) "sampling…" else "${bytesStr(heapUsed)} of ${bytesStr(heapMax)}")
        Stat("Frames", if (fps == 0) "sampling…" else "$fps fps")
        // "~" because it is an estimate over the object graph, not a measurement.
        Stat("Traffic", "~${bytesStr(traffic.trafficBytes)} · ${traffic.flows} flows")
        Stat("Bodies", bytesStr(traffic.bodyBytes))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PzText(label, color = P.faint, style = Typo.caption, family = P.Ui, modifier = Modifier.width(56.dp))
        PzText(value, color = P.text, style = Typo.caption)
    }
}

/**
 * One cell of the bar. Not a Jewel button: these are full-height segments of a
 * 22dp strip divided by hairlines, and a button's own padding, corner and
 * background would break that line up.
 */
@Composable
private fun StatusCell(
    border: Boolean = false,
    left: Boolean = false,
    active: Boolean = false,
    wash: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val background = when {
        // DESIGN.MD §9.12: a status widget that is on is filled, not recoloured.
        // It used to be a solid accent with the label inverted, which cannot
        // work now the label carries its own semantic colour.
        active -> P.pressed
        wash -> P.accentFill
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxHeight()
            .then(if (left) Modifier.leftBorder(P.line2) else if (border) Modifier.rightBorder(P.line2) else Modifier)
            .background(background)
            .then(
                if (onClick == null) Modifier
                else Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onClick() },
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
