package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared text atom. */
@Composable
fun PzText(
    text: String,
    color: Color = P.text,
    size: Int = 13,
    family: FontFamily = P.Mono,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) = BasicText(
    text,
    modifier = modifier,
    style = TextStyle(color = color, fontSize = size.sp, fontFamily = family, fontWeight = weight),
    maxLines = maxLines,
    overflow = overflow,
    softWrap = softWrap,
)

/** A small filled square (status dots, swatches, the brand glyph). */
@Composable
fun Dot(color: Color, s: Int = 5) = Box(Modifier.size(s.dp).background(color))

// ---------------------------------------------------------------------------
// Left rail (§6.2) — Home / Traffic, Settings pinned to the bottom.
// ---------------------------------------------------------------------------

@Composable
fun Rail(nav: String, onNav: (String) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(44.dp).background(P.chrome).rightBorder(P.line),
    ) {
        RailItem("home", "⌂", nav, onNav)
        RailItem("traffic", "◎", nav, onNav)
        Spacer(Modifier.weight(1f))
        RailItem("settings", "⚙", nav, onNav, top = true)
    }
}

@Composable
private fun RailItem(key: String, glyph: String, nav: String, onNav: (String) -> Unit, top: Boolean = false) {
    val on = nav == key
    Box(
        Modifier
            .height(44.dp)
            .fillMaxWidth()
            .then(if (top) Modifier.topBorder(P.line2) else Modifier.bottomBorder(P.line2))
            .background(if (on) P.sel else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onNav(key) },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight().background(P.accent))
        PzText(glyph, color = if (on) P.accent else P.faint, size = 17)
    }
}

// ---------------------------------------------------------------------------
// FLOWS panel header (§6.3) — title, count, predicate chips.
// ---------------------------------------------------------------------------

@Composable
fun FlowsHeader(visible: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(P.head).bottomBorder(P.line)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PzText("FLOWS", color = P.dim, size = 11, family = P.Ui)
        Spacer(Modifier.weight(1f))
        PzText("$visible/$total", color = P.faint, size = 12)
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("ALL", "ERR", "4XX", "TLS").forEachIndexed { i, t -> Chip(t, active = i == 0) }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean) {
    Box(
        Modifier
            .height(17.dp)
            .background(if (active) P.accentFill else Color.Transparent)
            .border1(if (active) P.accent else P.line)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { PzText(text, color = if (active) P.accent else P.dim, size = 11) }
}

// ---------------------------------------------------------------------------
// Status bar (§6.12) — LOGS, live stats, UTF-8, proxy target.
// ---------------------------------------------------------------------------

@Composable
fun StatusBar(
    flows: Int,
    ok: Int,
    failed: Int,
    port: Int,
    logsOpen: Boolean,
    warn: Int,
    error: Int,
    onToggleLogs: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(22.dp).background(P.chrome).topBorder(P.line),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LOGS toggle — opens the floating log panel; shows warn/error counts.
        Row(
            Modifier.fillMaxHeight().rightBorder(P.line2)
                .background(if (logsOpen) P.accentFill else Color.Transparent)
                .pointerHoverIcon(PointerIcon.Hand).clickable { onToggleLogs() }
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (logsOpen) P.accent else P.dim
            PzText(if (logsOpen) "▾" else "▸", color = tint, size = 12)
            Spacer(Modifier.width(7.dp))
            PzText("LOGS", color = tint, size = 12, family = P.Ui)
            if (warn > 0) { Spacer(Modifier.width(6.dp)); PzText("$warn", color = P.warn, size = 12) }
            if (error > 0) { Spacer(Modifier.width(6.dp)); PzText("$error", color = P.err, size = 12) }
        }
        StatusCell(border = true) { PzText("$flows flows", color = P.dim, size = 12) }
        StatusCell(border = true) { Dot(P.ok); Spacer(Modifier.width(6.dp)); PzText("$ok ok", color = P.dim, size = 12) }
        StatusCell(border = true) { Dot(P.err); Spacer(Modifier.width(6.dp)); PzText("$failed failed", color = P.dim, size = 12) }
        Spacer(Modifier.weight(1f))
        StatusCell(left = true) { PzText("UTF-8", color = P.dim, size = 12) }
        StatusCell(left = true) { PzText("127.0.0.1:$port", color = P.dim, size = 12) }
    }
}

@Composable
private fun StatusCell(border: Boolean = false, left: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxHeight()
            .then(if (left) Modifier.leftBorder(P.line2) else if (border) Modifier.rightBorder(P.line2) else Modifier)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
