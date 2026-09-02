package org.bittrace.plugin.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * A ramp of related shades, ordered **darkest first**.
 *
 * Shades are read one-based — `gray(1)`, `blue(6)` — matching the IntelliJ
 * palette this app's theming is modelled on, so an index means the same thing
 * here as it does in the IntelliJ UI specs. The ordering is darkest-to-lightest
 * in *both* light and dark themes; what changes between them is which index a
 * given role picks, not the direction of the ramp.
 *
 * Out-of-range indices clamp rather than throw. A theme that ships a short ramp
 * still renders; it just has fewer distinct steps.
 */
class Ramp(shades: List<Color>) {
    init {
        require(shades.isNotEmpty()) { "a ramp needs at least one shade" }
    }

    private val shades: List<Color> = shades.toList()

    /** The [step]-th shade, one-based and clamped. */
    operator fun invoke(step: Int): Color = shades[(step - 1).coerceIn(shades.indices)]

    /** How many distinct shades this ramp carries. */
    val size: Int get() = shades.size

    override fun equals(other: Any?): Boolean = this === other || (other is Ramp && shades == other.shades)

    override fun hashCode(): Int = shades.hashCode()

    override fun toString(): String = "Ramp(${shades.size} shades)"

    companion object {
        /** A ramp of exactly the shades given, darkest first. */
        fun of(shades: List<Color>): Ramp = Ramp(shades)

        /**
         * A ramp of [steps] shades interpolated between [anchors], darkest first.
         *
         * Most themes only really decide a handful of shades and want the rest
         * filled in evenly; this is for them. Two anchors give a plain gradient,
         * more anchors pin the curve at points along the way — which is what you
         * want for a ramp that has to hit an exact brand colour in the middle.
         */
        fun between(steps: Int, anchors: List<Color>): Ramp {
            require(steps > 0) { "a ramp needs at least one step" }
            require(anchors.size >= 2) { "interpolation needs at least two anchors" }
            if (steps == 1) return Ramp(listOf(anchors.first()))
            val spans = anchors.size - 1
            return Ramp(
                List(steps) { i ->
                    // Where this step falls along the whole ramp, then which
                    // span of the anchor list that lands in.
                    val t = i.toFloat() / (steps - 1) * spans
                    val span = t.toInt().coerceAtMost(spans - 1)
                    lerp(anchors[span], anchors[span + 1], t - span)
                }
            )
        }
    }
}
