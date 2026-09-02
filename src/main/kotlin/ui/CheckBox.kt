package org.bittrace.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CheckboxRow

/**
 * Checkboxes, over Jewel's.
 *
 * [CheckBox] gained an `onCheckedChange` in the move: the hand-rolled square was
 * a pure indicator, so every caller wrapped it in its own `.clickable` and the
 * click target was whatever that wrapper happened to be.
 */

/** A bare checkbox, for callers that lay out their own label. */
@Composable
fun CheckBox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = { onCheckedChange?.invoke(it) },
        enabled = enabled && onCheckedChange != null,
        modifier = modifier,
    )
}

/** A checkbox and its label as one click target. */
@Composable
fun CheckBoxRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    CheckboxRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
    ) {
        PzText(label, color = if (enabled) P.text else P.faint, style = Typo.label, family = P.Ui, softWrap = false)
    }
}
