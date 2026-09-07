package org.bittrace.plugin.theme

import androidx.compose.ui.graphics.Color

/**
 * A theme's colours, as ramps.
 *
 * A theme supplies eight ramps and says whether it is dark; every colour the app
 * draws is read off them at a fixed index. That indirection is the point: a flat
 * list of "the border colour, the hover colour, the pressed colour" has no answer
 * when a new control needs a shade nobody picked, so those shades end up invented
 * per control and drift apart. Reading `gray(4)` instead means every control that
 * wants "one step above the surface" gets the same one.
 *
 * The indices follow the IntelliJ UI palette, because the app renders through
 * Jewel and matching its vocabulary is what keeps the two consistent. Ramps run
 * **darkest first** in both light and dark themes; [isDark] decides which end a
 * role reads from, exactly as Int UI does it.
 *
 * `androidx.compose.ui.graphics.Color` is the only Compose type in the plugin
 * API, and it is here so plugins share the exact type the app renders with.
 *
 * @property isDark whether surfaces are dark and text light. Drives every
 *   semantic accessor below, and which Int UI base the app styles Jewel from.
 * @property gray surfaces, borders and text — 14 steps, the workhorse ramp.
 * @property blue the accent: selection, focus, links, the proxy port. 12 steps.
 * @property green success, 2xx, the "ok" count. 12 steps.
 * @property red errors, 5xx, the "failed" count. 12 steps.
 * @property yellow warnings and 4xx. 12 steps.
 * @property orange a second warm ramp, for timing phases. 12 steps.
 * @property purple a cool accent, for timing phases. 12 steps.
 * @property teal a second cool accent, for timing phases. 12 steps.
 */
data class Palette(
    val isDark: Boolean,
    val gray: Ramp,
    val blue: Ramp,
    val green: Ramp,
    val red: Ramp,
    val yellow: Ramp,
    val orange: Ramp,
    val purple: Ramp,
    val teal: Ramp,
) {
    // -----------------------------------------------------------------------
    // Semantic roles
    //
    // The indices below are the design language's, not a guess. Light and dark
    // read different indices, which is normal: a ramp runs
    // darkest-first in both, so the end a role reads from is what flips.
    //
    // One thing worth knowing before changing these: in a dark theme borders are
    // *lighter* than the surface they divide, the opposite of Int UI's
    // own dark scheme. That is deliberate — content sits at the bottom of the
    // ramp so it reads as something you look into, and every division above it
    // steps up.
    // -----------------------------------------------------------------------

    /** Content you read or edit: the flow table, body viewers, field interiors. */
    val bg: Color get() = if (isDark) gray(2) else gray(13)

    /** Chrome you operate: toolbar, stripe, status bar, tool-window headers. */
    val chrome: Color get() = if (isDark) gray(3) else gray(12)

    /** Panel surfaces: popups, cards, menus. Chrome, not content. */
    val panel: Color get() = if (isDark) gray(3) else gray(14)

    /**
     * Header strips above a table or a pane.
     *
     * A step clear of [panel] rather than equal to it. In a dark theme `panel`
     * and `chrome` are both `gray(3)`, so a heading painted in either of them
     * onto a panel — which is what the inspector's Overview does — vanished
     * entirely, while reading correctly in the light theme where `panel` is
     * white. The same collision as `line2` against `head` in light, on the other
     * side of the ramp.
     *
     * `gray(6)` and not `gray(4)`: the 3→4 step is deliberately tiny, because it
     * is the difference between a panel and a hovered row, and a heading needs
     * to be seen rather than merely differ.
     */
    val head: Color get() = if (isDark) gray(6) else gray(12)

    /**
     * What you type into: text-field interiors, the body editor, the segmented
     * control's trough.
     *
     * Not the same as [bg], even though the two coincide in a dark theme. A
     * light theme puts content on the window surface but an input on white, and
     * conflating them leaves the body editor a shade greyer than every field
     * around it.
     */
    val input: Color get() = if (isDark) gray(2) else gray(14)

    /** The primary 1px border: region edges, header underlines, control borders. */
    val line: Color get() = if (isDark) gray(7) else gray(10)

    /**
     * A subtler divider: cell separators, group rows.
     *
     * A step below [line] and, importantly, a step away from [head] — these were
     * the same shade in the light theme, which made every rule drawn against a
     * header strip invisible: the divider was painted in the header's own colour.
     */
    val line2: Color get() = if (isDark) gray(5) else gray(11)

    /** Primary text: titles, data values, active tab labels. */
    val text: Color get() = if (isDark) gray(13) else gray(1)

    /** Secondary text: inactive tabs, column headers, phase labels. */
    val dim: Color get() = if (isDark) gray(12) else gray(5)

    /** Tertiary text: hints, units, counts, line numbers, placeholders. */
    val faint: Color get() = if (isDark) gray(10) else gray(8)

    /** Hover fill on icon buttons, chips, stripe buttons, status widgets. */
    val hover: Color get() = if (isDark) gray(6) else gray(11)

    /** Toggled-on fill for an icon button. */
    val pressed: Color get() = if (isDark) gray(8) else gray(10)

    /**
     * The active tool-window stripe button.
     *
     * Two steps off [chrome] rather than one: a single step reads on a dark
     * chrome, where the eye has room below it, but on a light one it lands too
     * close to the surface it is meant to stand out from.
     */
    val stripeOn: Color get() = if (isDark) gray(7) else gray(10)

    /** Outline-button border — lighter than [line], so it reads as a control. */
    val outline: Color get() = if (isDark) gray(9) else gray(10)

    /** The accent: primary fill, selection, focus, active underline. */
    val accent: Color get() = if (isDark) blue(5) else blue(4)

    /** Success: 2xx, download phase. */
    val ok: Color get() = if (isDark) green(7) else green(4)

    /** Failure: resets, 5xx. */
    val err: Color get() = if (isDark) red(7) else red(4)

    /** Warning: 4xx, the wait phase. */
    val warn: Color get() = if (isDark) yellow(6) else yellow(4)

    /** Method, 3xx, connect phase, INFO — distinct from the accent. */
    val info: Color get() = if (isDark) teal(6) else teal(4)

    /** The send phase. */
    val send: Color get() = if (isDark) orange(6) else orange(4)

    /** Header keys, cookie names, TLS version, DNS phase. */
    val key: Color get() = if (isDark) purple(8) else purple(4)

    /** The wash behind a selected row. Selection is a fill, not an edge. */
    val sel: Color get() = if (isDark) blue(2) else blue(11)

    /** A translucent accent wash, for filter chips and pressed control states. */
    val accentFill: Color get() = accent.copy(alpha = 0.16f)

    /** The wash behind a hovered row. */
    val rowHover: Color get() = if (isDark) gray(4) else gray(12)

    /** Zebra striping on odd data rows — separation without a rule per row. */
    val rowStripe: Color get() = if (isDark) Color.White.copy(alpha = 0.014f) else Color.Black.copy(alpha = 0.014f)

    /** The waterfall's wait segment, the darker half of every overview bar. */
    val waitBar: Color get() = if (isDark) blue(3) else blue(7)
}
