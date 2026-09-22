package org.bittrace.ui.components

import org.bittrace.ui.BitTraceTheme
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.defaultSlimButtonStyle
import org.jetbrains.jewel.ui.component.OutlinedButton

/**
 * The app's two text buttons, as thin adapters over Jewel's.
 *
 * They keep their original names and parameter order so the call sites did not
 * have to change; what draws them is Jewel, which brings the focus ring, the
 * pressed state and a disabled state that actually blocks the click — the old
 * `GhostButton` passed `enabled` to its colours but not to `clickable`, so it
 * fired while looking disabled.
 *
 * Colours and metrics come from the active palette through [BitTraceTheme].
 */

/**
 * A filled action button, for the primary action in a row.
 *
 * [slim] takes Jewel's slim metrics — shorter, tighter padding — for a button
 * on a compact surface. Only the metrics: the colours stay the app's, which the
 * slim style Jewel ships would not, since the theme only restyles the regular one.
 */
@Composable
fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    slim: Boolean = false,
    onClick: () -> Unit,
) {
    val regular = JewelTheme.defaultButtonStyle
    val style = if (slim) {
        ButtonStyle(regular.colors, JewelTheme.defaultSlimButtonStyle.metrics, regular.focusOutlineAlignment)
    } else {
        regular
    }
    DefaultButton(onClick = onClick, enabled = enabled, modifier = modifier, style = style) {
        PzText(label, color = if (enabled) P.bg else P.faint, style = Typo.label, family = P.Ui, softWrap = false)
    }
}

/** An outlined action button, for anything secondary to the primary one. */
@Composable
fun GhostButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        PzText(label, color = if (enabled) P.text else P.faint, style = Typo.label, family = P.Ui, softWrap = false)
    }
}
