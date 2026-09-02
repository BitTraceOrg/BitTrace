package org.bittrace.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.theme.comboBoxStyle

/**
 * A compact picker, for when a [SegmentedToggle] would be too wide — seven HTTP
 * methods laid out in a row leave nothing for the URL beside them.
 *
 * Jewel's own `Dropdown` is `@ExperimentalJewelApi` and slated for replacement,
 * so this is `ListComboBox`, which is stable and brings keyboard selection.
 */
@Composable
fun Dropdown(
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    /** False when it sits inside another bordered control and shares its frame. */
    bordered: Boolean = true,
    onSelect: (String) -> Unit,
) {
    val index = options.indexOf(value).coerceAtLeast(0)
    ListComboBox(
        items = options,
        selectedIndex = index,
        onSelectedItemChange = { i -> options.getOrNull(i)?.let(onSelect) },
        // Borderless means borderless: sharing a frame with the field beside it
        // needs Jewel's undecorated style, not just a transparent background —
        // the combo box draws its own border otherwise, and the address bar ends
        // up as a box inside a box.
        style = if (bordered) JewelTheme.comboBoxStyle else undecoratedComboBoxStyle(),
        modifier = modifier.width(width),
    )
}
