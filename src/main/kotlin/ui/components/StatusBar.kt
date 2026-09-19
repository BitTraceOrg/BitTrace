package org.bittrace.ui.components

import org.bittrace.ui.bytesStr
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

import org.bittrace.ui.leftBorder
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * What the bar reports about the panel being looked at.
 *
 * The bar used to be the capture panel's bar wherever you were, so the Forge
 * was shown a flow count and two filters it could not apply, none of which had
 * anything to do with the request on screen. Those readings are not
 * general — they are the inspector's — and the way to say so is to make the
 * panel hand over its own, rather than to pass every panel's numbers at once
 * and have the bar guess which are live.
 *
 * What is *not* here is what belongs to the app rather than to a panel: the
 * logs, the busy slot and the nerd stats are the same question wherever you
 * are, and they stay outside this on purpose.
 */
sealed interface StatusContext {

    /** Home and Settings, which have no readings of their own. */
    data object None : StatusContext

    /** The capture panel: what came through, and what is being shown of it. */
    class Inspector(
        val flows: Int,
        /** The overview band's query, spelled out, or null when nothing is filtered. */
        val query: String?,
        /** How many of [flows] the query keeps. */
        val queryMatches: Int,
        val ok: Int,
        val failed: Int,
        val okFilterOn: Boolean = false,
        val failedFilterOn: Boolean = false,
        val onFilterOk: () -> Unit = {},
        val onFilterFailed: () -> Unit = {},
    ) : StatusContext

    /**
     * The Forge: where the request on screen lives, and on which branch.
     *
     * Every field is null when there is nothing to say — an unsaved draft is in
     * no project, and a project that is not a repository has no branch. The part
     * is left out rather than blanked in that case: a gap where a name goes
     * reads as a name that failed to load.
     */
    class Forge(
        val project: String?,
        val collection: String?,
        /**
         * The request's file name without its extension, which is what the tree
         * labels its rows with. The saved name rather than the edited one, so
         * the trail keeps describing where the file is while a rename is still
         * unsaved.
         */
        val request: String?,
        val branch: BranchCell?,
    ) : StatusContext
}

/**
 * The branch at the end of the Forge trail, and what can be done to it.
 *
 * [tint] arrives decided rather than being worked out here: the same shade has
 * to appear on the tree's project mark and in the project tab, and one rule
 * living with the git panel beats three status bars agreeing by luck.
 *
 * [options] is empty when there is nothing to switch to — a detached HEAD, or a
 * repository whose first read has not landed — and the cell falls back to the
 * readout it has always been.
 */
class BranchCell(
    val label: String,
    val tint: Color,
    /** The branch to tick in the menu; null on a detached HEAD. */
    val current: String? = null,
    val options: List<String> = emptyList(),
    val onSelect: (String) -> Unit = {},
    val onNewBranch: () -> Unit = {},
)

/**
 * The status bar — the LOGS toggle, whatever the active panel reports, and the
 * readouts.
 *
 * Laid out in three parts that do not move relative to each other: the logs
 * toggle is always leftmost, the panel's own cells follow it, and the readouts
 * are always right. Switching panels changes the middle and nothing else, so
 * the two controls that are there wherever you are stay where they were.
 */
@Composable
fun StatusBar(
    context: StatusContext,
    /**
     * What is running, or null when nothing is.
     *
     * One slot for the whole app rather than a spinner per feature. Work that
     * takes long enough to notice is rare enough that two pieces of it at once
     * is not worth a second row, and a status bar with a bar that comes and goes
     * in one known place is easier to read than one that sprouts indicators in
     * different corners. App-wide, so it shows on every panel — the git write it
     * is reporting was very often started from the panel you just left.
     */
    busy: String?,
    /**
     * What the nerd panel reports, read when it opens rather than every frame —
     * one of the numbers walks every captured row to produce its estimate.
     */
    nerdStats: () -> NerdStats,
    logsOpen: Boolean,
    warn: Int,
    error: Int,
    onToggleLogs: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(P.chrome).topBorder(P.line),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LOGS toggle — opens the floating log panel; shows warn/error counts.
        // Every panel writes to the same log, so it is on every panel.
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

        when (context) {
            is StatusContext.Inspector -> InspectorCells(context)
            is StatusContext.Forge -> ForgeCells(context)
            StatusContext.None -> Unit
        }

        // Between the panel's cells and the readouts: it appears and disappears,
        // so it sits where nothing else has to move aside for it.
        if (busy != null) {
            StatusCell(border = true) {
                IndeterminateHorizontalProgressBar(Modifier.width(64.dp))
                Spacer(Modifier.width(8.dp))
                PzText(busy, color = P.dim, style = Typo.label, family = P.Ui, maxLines = 1)
            }
        }

        Spacer(Modifier.weight(1f))
        NerdStatsCell(nerdStats)
    }
}

/** The capture panel's cells: how many flows, what is filtering them, and the outcomes. */
@Composable
private fun InspectorCells(context: StatusContext.Inspector) {
    StatusCell(border = true) {
        // "312 flows of 4,102" while a query runs. A bare count next to a
        // filtered grid is the reading that gets someone stuck: the number
        // is right and the grid looks broken, with nothing on screen saying
        // the two disagree because something is being filtered.
        PzText(
            if (context.query == null) {
                "${context.flows} flows"
            } else {
                "${context.queryMatches} flows of ${context.flows}"
            },
            color = if (context.query == null) P.dim else P.accent,
            style = Typo.label,
        )
    }
    // The query itself, and the only place it survives the band collapsing.
    if (context.query != null) {
        StatusCell(border = true) {
            PzText("⌕", color = P.accent, style = Typo.label, family = P.Ui)
            Spacer(Modifier.width(6.dp))
            PzText(context.query, color = P.dim, style = Typo.label, maxLines = 1)
        }
    }
    // The counts double as filters, and they are toggles: clicking one shows
    // only those flows, clicking it again clears it. The cell's fill is what
    // says which state it is in — a close icon spelled the same thing out a
    // second time, and a swatch beside the count said in a box what the
    // count can say in its own colour.
    StatusCell(border = true, active = context.okFilterOn, onClick = context.onFilterOk) {
        PzText("${context.ok} ok", color = P.ok, style = Typo.label)
    }
    StatusCell(border = true, active = context.failedFilterOn, onClick = context.onFilterFailed) {
        PzText("${context.failed} failed", color = P.err, style = Typo.label)
    }
}

/**
 * The Forge's cell: where the open request lives, then which branch it is on.
 *
 * One cell, not two. The trail and the branch answer one question between them
 * — which file am I looking at, and in what state is the repository holding it
 * — and a hairline between them made that read as two unrelated readouts. The
 * branch joins the trail behind a slash instead, which is how a branch is
 * written beside a path everywhere else.
 *
 * The branch is a control here. It was a readout while the tree's project rows
 * carried a picker of their own; with that gone, this is the one branch name on
 * screen wherever you are in the Forge, and the cheapest place to change it
 * from. The switch is the same guarded one the project tab runs, so unsaved
 * tabs still stop it.
 */
@Composable
private fun ForgeCells(context: StatusContext.Forge) {
    if (context.project == null && context.branch == null) return
    StatusCell(border = true) {
        // Tracks whether anything has been drawn yet, so the first part never
        // gets a separator in front of it. Cheaper to read than working the
        // same thing out from three nullable fields at each step.
        var first = true

        /** A chevron between trail steps, skipped before the first one. */
        @Composable
        fun step() {
            if (!first) {
                Spacer(Modifier.width(7.dp))
                // A chevron rather than a "›": it is the glyph the rest of the
                // bar already separates with, it centres the way the icons
                // beside it do instead of on a text baseline, and it cannot
                // come out as a missing-glyph box the way a character borrowed
                // from another family can.
                Icon(key = AllIconsKeys.General.ChevronRight, contentDescription = null, tint = P.faint)
                Spacer(Modifier.width(7.dp))
            }
            first = false
        }

        // The same glyph the tree marks its rows with, tinted the same two
        // ways: a project is accent, a collection is warn. The tree draws both
        // from one key and lets colour carry the difference, and the trail has
        // to agree with it — this is the same project, and a second icon for it
        // would read as a second kind of thing.
        if (context.project != null) {
            step()
            Icon(
                key = AllIconsKeys.Toolwindows.ToolWindowProject,
                contentDescription = "Project",
                tint = P.accent,
            )
            Spacer(Modifier.width(7.dp))
            PzText(context.project, color = P.dim, style = Typo.label, maxLines = 1)
        }
        if (context.collection != null) {
            step()
            Icon(
                key = AllIconsKeys.Toolwindows.ToolWindowProject,
                contentDescription = "Collection",
                tint = P.warn,
            )
            Spacer(Modifier.width(7.dp))
            PzText(context.collection, color = P.dim, style = Typo.label, maxLines = 1)
        }
        // `fileTypes/http`, which is what an IDE marks a saved HTTP request
        // with — and that is what these files are. A third folder tinted a
        // third colour would have said the trail was three of the same kind of
        // thing; this one says the last step is a different kind, which is the
        // part worth seeing at a glance.
        if (context.request != null) {
            step()
            Icon(key = AllIconsKeys.FileTypes.Http, contentDescription = "Request", tint = P.key)
            Spacer(Modifier.width(7.dp))
            PzText(context.request, color = P.text, style = Typo.label, maxLines = 1)
        }
        context.branch?.let { branch ->
            if (!first) {
                // Mono, like the names on either side, rather than the UI face:
                // a slash borrowed from another family sits on that family's
                // metrics and rides high between them.
                Spacer(Modifier.width(7.dp))
                PzText("/", color = P.faint, style = Typo.label)
                Spacer(Modifier.width(7.dp))
            }
            BranchTrailStep(branch)
        }
    }
}

/**
 * The branch step: its glyph, its name, and the menu behind them.
 *
 * A popup rather than a combo box. The status bar is one row of small text and
 * a bordered picker in it would be the tallest thing on the bar — where a name
 * that happens to open a menu costs no height at all, which is the same trade
 * the tree's old branch chip made for the same reason.
 */
@Composable
private fun BranchTrailStep(branch: BranchCell) {
    var open by remember { mutableStateOf(false) }
    val pickable = branch.options.isNotEmpty()

    Box {
        Row(
            if (pickable) {
                Modifier.clickable { open = true }.pointerHoverIcon(PointerIcon.Hand)
            } else {
                Modifier
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = AllIconsKeys.Vcs.Branch,
                contentDescription = "Current branch",
                tint = branch.tint,
            )
            Spacer(Modifier.width(7.dp))
            PzText(branch.label, color = P.dim, style = Typo.label, maxLines = 1)
            if (pickable) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    key = AllIconsKeys.General.ChevronDown,
                    contentDescription = null,
                    tint = P.faint,
                )
            }
        }

        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                branch.options.forEach { option ->
                    selectableItem(
                        selected = option == branch.current,
                        onClick = { open = false; branch.onSelect(option) },
                    ) {
                        PzText(option, color = P.text, style = Typo.label, family = P.Ui)
                    }
                }
                // Below the rule for the reason the panel's own menu puts it
                // there: everything above switches to a branch that exists.
                separator()
                selectableItem(selected = false, onClick = { open = false; branch.onNewBranch() }) {
                    PzText("New branch\u2026", color = P.text, style = Typo.label, family = P.Ui)
                }
            }
        }
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
        // A status widget that is on is filled, not recoloured.
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
