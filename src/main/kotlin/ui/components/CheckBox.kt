package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import androidx.compose.foundation.layout.RowScope
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

/**
 * A bare checkbox, for callers that lay out their own label.
 *
 * Note what a null [onCheckedChange] means: the box is **disabled**, which both
 * dims it and makes it swallow presses rather than pass them on. So it is not
 * the thing to put inside a row that carries its own `clickable` — clicking the
 * label would work and clicking the box, the one part that looks like a control,
 * would do nothing. Use [CheckBoxRow] for that; this is for a genuinely inert
 * indicator, or for a caller that handles the change itself.
 */
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

/**
 * A checkbox whose whole row is the click target, laying out its own content.
 *
 * For rows that carry more than a label — a trailing count, a swatch — where
 * [CheckBoxRow]'s plain string is not enough but the click target should still
 * be the row rather than the nine-pixel square.
 */
@Composable
fun CheckBoxRow(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    CheckboxRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        content = content,
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
