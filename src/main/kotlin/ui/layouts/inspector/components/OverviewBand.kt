package org.bittrace.ui.layouts.inspector.components

import org.bittrace.ui.statusOf
import org.bittrace.ui.ChipShape
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.bittrace.ui.components.CheckBoxRow
import org.jetbrains.jewel.ui.component.IconActionButton
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.Segment
import org.bittrace.ui.components.SegmentedToggle
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder
import java.awt.Cursor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * The overview band, in its two modes.
 *
 * The governing idea, and the reason this is one component rather than a
 * timeline and a search panel: **searching does not replace the timeline, it
 * turns the timeline into an input.** The band grows, the lanes fade out, and
 * the same waterfall comes back compressed at the bottom as something you brush
 * a time window on. Swapping it for a search box would throw away the one
 * picture that tells you *when* to look.
 *
 * The prototype's literal hexes are mapped onto [P]'s roles rather than
 * hardcoded — `#454d53` becomes `faint`, `#14181c` becomes `rowHover`, and so
 * on. Hardcoding them would make this the one surface an external theme plugin
 * could not recolour, which is a rule this app has held everywhere else.
 */
@Composable
fun OverviewBand(
    rows: List<TrafficRow>,
    shown: List<TrafficRow>,
    query: FlowQuery,
    origin: Long?,
    searching: Boolean,
    selectedId: String?,
    onQuery: (FlowQuery) -> Unit,
    onSearching: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    // The one animation the band is allowed. It exists so the user sees the
    // timeline *become* the search surface; without it the band would appear to
    // be replaced by a different control.
    val height by animateDpAsState(
        targetValue = if (searching) SEARCH_HEIGHT else IDLE_HEIGHT,
        animationSpec = tween(TRANSITION_MS, easing = BAND_EASING),
        label = "band",
    )
    val laneAlpha by animateFloatAsState(
        targetValue = if (searching) 0f else 1f,
        animationSpec = tween(FADE_MS),
        label = "lanes",
    )
    val searchAlpha by animateFloatAsState(
        targetValue = if (searching) 1f else 0f,
        animationSpec = tween(FADE_MS, delayMillis = if (searching) FADE_DELAY_MS else 0),
        label = "search",
    )

    // How long the capture runs, which the strip's axis spans.
    val span = remember(rows.size, origin) {
        rows.mapNotNull { offsetOf(it, origin)?.plus(it.response?.time ?: 0.0) }.maxOrNull() ?: WINDOW_MS
    }
    // Whether the window came from a deliberate brush. Until it does it tracks
    // the live edge, so the strip's selection is always exactly the minute the
    // lanes are showing — the two are one window at two scales, and a strip
    // whose default disagreed with the lanes would be two windows arguing in the
    // same hundred pixels.
    var brushed by remember { mutableStateOf(false) }

    LaunchedEffect(span, brushed) {
        if (brushed) return@LaunchedEffect
        // Trailing rather than literally the first minute: on a live capture a
        // window frozen at the start would quietly stop showing new traffic,
        // and on a fresh capture the two are the same thing anyway.
        val start = (span - WINDOW_MS).coerceAtLeast(0.0)
        onQuery(query.copy(window = start..(start + WINDOW_MS)))
    }

    // The seeded window is the band's own default rather than something anybody
    // asked for, so it must not take over the chip that says how to start
    // searching — otherwise the affordance is gone from the moment of launch.
    val asked = if (!brushed && query.copy(window = null).isEmpty) null else query.describe()

    // Published rather than passed: the grid's columns are a static catalog
    // built once at class-init, so there is no parameter to thread a needle
    // through — the same reason `P` publishes the palette instead of every
    // composable taking one.
    SideEffect { FlowHighlight.needle = query.text.trim() }

    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        if (laneAlpha > 0f) {
            Box(Modifier.fillMaxSize().alpha(laneAlpha)) {
                IdleBand(shown, rows, query, asked, origin, span, selectedId, onQuery, { brushed = it }, onSelect) {
                    onSearching(true)
                }
            }
        }
        if (searchAlpha > 0f) {
            Box(Modifier.fillMaxSize().alpha(searchAlpha)) {
                SearchBand(rows, shown, query, origin, span, onQuery, { brushed = it }) { onSearching(false) }
            }
        }
    }
}

// --- idle -------------------------------------------------------------------

/**
 * The waterfall as it always was, plus the field that opens search.
 *
 * The lanes keep their fixed minute — an hour of capture spread across one width
 * makes every bar a sliver, and that is a decision this band does not revisit.
 * The full span belongs to the strip below, where it is the axis you brush on.
 *
 * What the brush reaches is where that minute sits: a window brushed an hour
 * back scrolls the lanes to it. Without that the lanes would filter down to the
 * brushed flows and then show the last minute of the capture, which is the one
 * stretch those flows are guaranteed not to be in.
 */
@Composable
private fun IdleBand(
    shown: List<TrafficRow>,
    rows: List<TrafficRow>,
    query: FlowQuery,
    /** How the query reads, or null while nothing but the default window is set. */
    asked: String?,
    origin: Long?,
    span: Double,
    selectedId: String?,
    onQuery: (FlowQuery) -> Unit,
    onBrushed: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Waterfall(shown, selectedId, origin, query.window, onSelect)

            // Parked in the lanes' empty right third. When a query is running its
            // label is replaced by the query's own tokens: collapsed, this is the
            // only place that says what is being filtered.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp)
                    .height(20.dp)
                    .background(P.panel)
                    .border(1.dp, P.line)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.TEXT_CURSOR)))
                    .clickable { onOpen() }
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PzText("⌕", color = P.faint, style = Typo.label, family = P.Ui)
                Spacer(Modifier.width(6.dp))
                if (asked == null) {
                    PzText("Search", color = P.faint, style = Typo.micro, family = P.Ui)
                    Spacer(Modifier.width(8.dp))
                    Keycap("/")
                } else {
                    PzText(asked, color = P.accent, style = Typo.micro, maxLines = 1)
                }
            }
        }

        // The same strip the search band ends with, in the same place, so it
        // survives the transition rather than being swapped for a second control
        // that draws the same picture. It replaces the scroll map it used to be:
        // both were "the whole capture, with the part you are looking at marked
        // on it", and only one of them could also say which part that is.
        TimeStrip(rows, query, origin, span, onQuery, onBrushed)
    }
}

@Composable
private fun Keycap(text: String) {
    Box(
        Modifier.border(1.dp, P.line2, ChipShape).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        PzText(text, color = P.faint, style = Typo.micro)
    }
}

// --- search -----------------------------------------------------------------

@Composable
private fun SearchBand(
    rows: List<TrafficRow>,
    shown: List<TrafficRow>,
    query: FlowQuery,
    origin: Long?,
    span: Double,
    onQuery: (FlowQuery) -> Unit,
    onBrushed: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        QueryBar(rows.size, shown.size, query, onQuery, onClose)
        // The grid absorbs whatever the bar and the strip leave.
        FacetGrid(rows, query, origin, Modifier.weight(1f), onQuery)
        TimeStrip(rows, query, origin, span, onQuery, onBrushed)
    }
}

@Composable
private fun QueryBar(
    total: Int,
    matching: Int,
    query: FlowQuery,
    onQuery: (FlowQuery) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // After the height transition, not with it: focusing mid-animation puts a
    // caret in a box that is still growing.
    LaunchedEffect(Unit) {
        delay(FOCUS_DELAY_MS.milliseconds)
        runCatching { focus.requestFocus() }
    }

    Row(
        Modifier.fillMaxWidth().height(QUERY_BAR_HEIGHT).background(P.chrome).bottomBorder(P.line)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PzText("⌕", color = P.accent, style = Typo.label, family = P.Ui)

        query.facets.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key.ordinal }.forEach { (group, values) ->
            Token(
                label = group.label,
                value = if (values.size > 2) "${values.size} values" else values.sorted().joinToString(", "),
            ) {
                onQuery(query.clearGroup(group))
            }
        }
        query.window?.let { window ->
            // Warn-coloured, and the only thing in the app that is: the colour
            // is the link back to the brush that made it. It must not spread.
            Token("Time", secondsLabel(window), tone = P.warn) {
                onQuery(query.copy(window = null))
            }
        }
        query.isolate?.let {
            Token("Isolate", "1 flow") {
                onQuery(query.copy(isolate = null))
            }
        }

        TextInput(
            value = query.text,
            onValueChange = { text ->
                onQuery(query.copy(text = text, isolate = null))
            },
            placeholder = "search bodies, host, path or status…",
            bordered = false,
            modifier = Modifier.weight(1f).focusRequester(focus),
        )

        // Which body the text is searched in. Beside the field rather than in
        // the facet grid because it is not a filter — it says what the words you
        // are typing mean, and a control that changes the meaning of an input
        // belongs next to that input.
        SegmentedToggle(
            segments = listOf(Segment("req", "Request"), Segment("res", "Response")),
            selected = if (query.side == BodySide.REQUEST) "req" else "res",
        ) { picked ->
            onQuery(query.copy(side = if (picked == "req") BodySide.REQUEST else BodySide.RESPONSE))
        }

        SegmentedToggle(
            segments = listOf(Segment("and", "AND"), Segment("or", "OR")),
            selected = if (query.joinFacetsWithAnd) "and" else "or",
        ) { picked ->
            onQuery(query.copy(joinFacetsWithAnd = picked == "and"))
        }

        PzText("$matching / $total match", color = P.accent, style = Typo.micro)

        // The same close button the grid's filter popup uses. A key name in a
        // box told you the shortcut but was not itself obviously pressable, and
        // the shortcut still works either way.
        IconActionButton(
            key = AllIconsKeys.General.Close,
            contentDescription = "Close the search band",
            onClick = onClose,
        )
    }
}

/** One chip in the token strip. Clicking it removes the whole group. */
@Composable
private fun Token(label: String, value: String, tone: Color = P.accent, onRemove: () -> Unit) {
    Row(
        Modifier.height(18.dp)
            .background(tone.copy(alpha = TOKEN_FILL), ChipShape)
            .border(1.dp, tone, ChipShape)
            .clip(ChipShape)
            .clickable { onRemove() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PzText(label.lowercase(), color = P.dim, style = Typo.micro)
        PzText(":", color = P.dim, style = Typo.micro)
        PzText(value, color = tone, style = Typo.micro, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        PzText("✕", color = tone, style = Typo.micro)
    }
}

@Composable
private fun FacetGrid(
    rows: List<TrafficRow>,
    query: FlowQuery,
    origin: Long?,
    modifier: Modifier,
    onQuery: (FlowQuery) -> Unit,
) {
    Row(modifier.fillMaxWidth()) {
        FacetGroup.entries.forEachIndexed { index, group ->
            val values = remember(rows.size, group) { facetValues(group, rows) }
            val counts = remember(rows.size, query, group) { crossFilteredCounts(rows, query, group, origin) }
            val selected = query.facets[group].orEmpty()

            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .then(if (index == FacetGroup.entries.lastIndex) Modifier else Modifier.rightBorder(P.line2)),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(FACET_ROW_HEIGHT).background(P.head).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PzText(
                        group.label,
                        color = P.dim, style = Typo.label, family = P.Ui, weight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    PzText(values.size.toString(), color = P.faint, style = Typo.label, family = P.Ui)
                }

                // Each column scrolls on its own: hosts run to hundreds while
                // status never exceeds five, and one shared scroll would make
                // the short columns unreachable.
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    values.forEach { value ->
                        FacetRow(
                            value = value,
                            count = counts[value] ?: 0,
                            on = value in selected,
                        ) { onQuery(query.toggle(group, value)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FacetRow(value: String, count: Int, on: Boolean, onToggle: () -> Unit) {
    // A zero-count value stays tickable: it is how you clear your way back to
    // it, and a row that cannot be pressed reads as broken rather than empty.
    val dead = count == 0 && !on
    CheckBoxRow(
        checked = on,
        onCheckedChange = { onToggle() },
        modifier = Modifier.fillMaxWidth().height(FACET_ROW_HEIGHT)
            .background(if (on) P.sel else Color.Transparent)
            .bottomBorder(P.line2)
            .padding(horizontal = 8.dp),
    ) {
        PzText(
            value,
            color = if (dead) P.faint else P.text,
            style = Typo.label,
            family = P.Ui,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        PzText(count.toString(), color = if (on) P.accent else P.faint, style = Typo.label, family = P.Ui)
    }
}

/**
 * The compressed waterfall, and the component's whole point.
 *
 * No lanes: bars overlap freely, because this is a density read — *when was it
 * busy* — rather than a per-flow one. The lanes upstairs are where an individual
 * flow is legible.
 */
@Composable
private fun TimeStrip(
    rows: List<TrafficRow>,
    query: FlowQuery,
    origin: Long?,
    span: Double,
    onQuery: (FlowQuery) -> Unit,
    onBrushed: (Boolean) -> Unit,
) {
    var pressX by remember { mutableStateOf<Float?>(null) }
    var atX by remember { mutableStateOf<Float?>(null) }
    // Non-null while the press landed inside the window: the window as it stood
    // when the drag began, so a move is measured from where it started rather
    // than accumulating rounding from each frame's delta.
    var moving by remember { mutableStateOf<ClosedFloatingPointRange<Double>?>(null) }
    var width by remember { mutableStateOf(1f) }
    val density = LocalDensity.current

    fun shifted(base: ClosedFloatingPointRange<Double>, byPx: Float): ClosedFloatingPointRange<Double> {
        val length = base.endInclusive - base.start
        // Clamped by the start alone, so dragging past either end parks the
        // window against it at full width instead of squashing it.
        val at = (base.start + byPx / width * span).coerceIn(0.0, (span - length).coerceAtLeast(0.0))
        return at..(at + length)
    }

    /** What the window looks like right now, drag included. */
    fun preview(): ClosedFloatingPointRange<Double>? {
        val from = pressX ?: return query.window
        val to = atX ?: return query.window
        moving?.let { return shifted(it, to - from) }
        val lo = (min(from, to) / width).coerceIn(0f, 1f) * span
        val hi = (max(from, to) / width).coerceIn(0f, 1f) * span
        // Too narrow to be a brush: this press was a click, and a click outside
        // the window clears it.
        return if (hi - lo < CLICK_FRACTION * span) null else lo..hi
    }

    Box(
        Modifier.fillMaxWidth().height(STRIP_HEIGHT).background(P.bg).topBorder(P.line)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            // One gesture handler, not a drag detector beside a tap detector:
            // a click and a zero-width drag are the same act here, and two
            // competing detectors would have had to agree about which of them
            // owned it. Awaiting the whole press instead means the press *is*
            // the gesture, and where it landed decides what it meant.
            .pointerInput(rows.size, span, query.window) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressX = down.position.x
                    atX = down.position.x
                    // Inside an existing window the press moves it; anywhere
                    // else it starts a new one. That is the whole difference
                    // between adjusting the window you have and drawing another,
                    // and it is the only thing the hit test decides.
                    moving = query.window?.takeIf { down.position.x / width * span in it }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        atX = pointer.position.x
                        if (!pointer.pressed) break
                        pointer.consume()
                    }
                    val settled = preview()
                    val wasMoving = moving != null
                    pressX = null
                    atX = null
                    moving = null
                    // A click inside the window is a zero-length move, which
                    // leaves it exactly as it was — deliberately, so the window
                    // is not something you lose by touching it.
                    // A press that produced a window is the deliberate act that
                    // stops the strip tracking the live edge. One that cleared
                    // it hands the window back, rather than leaving the lanes
                    // with no minute to show.
                    onBrushed(settled != null)
                    onQuery(
                        when {
                            wasMoving -> query.copy(window = settled)
                            // Drawing a fresh window is a deliberate act, and an
                            // isolate left on top of it would show one flow and
                            // look like the brush had failed.
                            settled != null -> query.copy(window = settled, isolate = null)
                            else -> query.copy(window = null)
                        },
                    )
                }
            },
    ) {
        val painted = preview()

        Canvas(Modifier.fillMaxSize()) {
            val bottom = size.height - 4f
            rows.forEach { row ->
                val start = offsetOf(row, origin) ?: return@forEach
                val x = (start / span * size.width).toFloat()
                val w = ((row.response?.time ?: 0.0) / span * size.width)
                    .toFloat()
                    .coerceAtLeast(size.width * MIN_BAR_FRACTION)
                val hit = query.matches(row, origin)
                drawRect(
                    color = if (hit) statusOf(row).second else P.line,
                    topLeft = Offset(x, bottom - 5f),
                    size = Size(w, 5f),
                )
            }

            painted?.let { window ->
                val a = (window.start / span * size.width).toFloat()
                val b = (window.endInclusive / span * size.width).toFloat()
                drawRect(P.accent.copy(alpha = TOKEN_FILL), Offset(a, 0f), Size(b - a, size.height))
                drawRect(P.accent, Offset(a, 0f), Size(1f, size.height))
                drawRect(P.accent, Offset(b - 1f, 0f), Size(1f, size.height))
            }
        }

        // An overlay carrying nothing but the move cursor, so the window says it
        // is draggable before you try. It takes no clicks of its own — the press
        // still belongs to the strip, which is what knows where it landed.
        painted?.let { window ->
            val left = (window.start / span * width).toFloat()
            val right = (window.endInclusive / span * width).toFloat()
            Box(
                Modifier
                    .offset { IntOffset(left.toInt(), 0) }
                    .width(with(density) { (right - left).coerceAtLeast(1f).toDp() })
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR))),
            )
        }
    }
}

/**
 * The free-text needle the grid marks in its URL cells.
 *
 * Filtering tells you *which* rows matched; it does not tell you **where**. On a
 * path like `/v2/accounts/9182/orders` a search for `orders` leaves you reading
 * the row to find what you already asked for, and that re-reading is most of the
 * cost of a search on a wide grid.
 *
 * Published rather than passed: the grid's columns are a static catalog built
 * once at class-init, so there is no parameter to thread a needle through — the
 * same reason `P` publishes the palette instead of every composable taking one.
 */
object FlowHighlight {
    var needle by mutableStateOf("")
}

/**
 * [text] with every occurrence of [FlowHighlight.needle] marked.
 *
 * Returns the plain string when there is nothing to mark, so the common case
 * allocates no spans.
 */
@Composable
fun highlighted(text: String, base: Color): AnnotatedString {
    val needle = FlowHighlight.needle
    if (needle.isBlank() || !text.contains(needle, ignoreCase = true)) {
        return AnnotatedString(text, SpanStyle(color = base))
    }
    val mark = SpanStyle(color = P.accent, background = P.accentFill)
    return buildAnnotatedString {
        var at = 0
        while (true) {
            val hit = text.indexOf(needle, at, ignoreCase = true)
            if (hit < 0) break
            withStyle(SpanStyle(color = base)) { append(text.substring(at, hit)) }
            withStyle(mark) { append(text.substring(hit, hit + needle.length)) }
            at = hit + needle.length
        }
        withStyle(SpanStyle(color = base)) { append(text.substring(at)) }
    }
}

// --- metrics ----------------------------------------------------------------

/** The lanes plus the strip beneath them. */
val IDLE_HEIGHT = 88.dp

/** The band never grows past this, whatever the facet columns hold. */
val SEARCH_HEIGHT = 250.dp

/**
 * The query bar, with room to breathe around the input.
 *
 * Chips and a text field packed to a row's exact height read as a toolbar rather
 * than something you type in, and this is the one control in the band that is
 * asking for a caret.
 */
private val QUERY_BAR_HEIGHT = 36.dp

/**
 * The brush strip.
 *
 * Tall enough to grab and to read the traffic's shape off, short enough not to
 * be a second chart competing with the lanes above it — the same judgement the
 * scroll map it replaced was making at 16dp, plus the few pixels a draggable
 * window needs over a thing you only clicked.
 */
private val STRIP_HEIGHT = 22.dp

/** Tall enough for Jewel's own checkbox, which sets the height here. */
private val FACET_ROW_HEIGHT = 22.dp

private const val TRANSITION_MS = 160

private const val FADE_MS = 120

private const val FADE_DELAY_MS = 40

/** After the height transition, so the caret does not land in a growing box. */
private const val FOCUS_DELAY_MS = 60L

private val BAND_EASING = CubicBezierEasing(0.3f, 0.7f, 0.4f, 1f)

/** The token and window wash — the same value both, since they are the same idea. */
private const val TOKEN_FILL = 0.12f

/** A brush narrower than this is a click, and a click clears the window. */
private const val CLICK_FRACTION = 0.012f

/** So a 2ms flow in an hour-long capture is still a mark rather than nothing. */
private const val MIN_BAR_FRACTION = 0.0025f
