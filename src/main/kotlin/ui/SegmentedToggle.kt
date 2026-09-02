package org.bittrace.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData

/** One choice in a [SegmentedToggle]. */
class Segment(val id: String, val label: String)

/**
 * A row of mutually exclusive options sharing one border — used for theme, row
 * mode, dock side, log level and the Home range picker.
 *
 * This was the app's most-copied widget: five screens had inlined their own
 * version rather than calling the one that already existed.
 */
@Composable
fun SegmentedToggle(
    segments: List<Segment>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    SegmentedControl(
        buttons = segments.map { segment ->
            SegmentedControlButtonData(
                selected = segment.id == selected,
                onSelect = { onSelect(segment.id) },
                content = { PzText(segment.label, color = P.text, style = Typo.label, family = P.Ui, softWrap = false) },
            )
        },
        modifier = modifier,
    )
}
