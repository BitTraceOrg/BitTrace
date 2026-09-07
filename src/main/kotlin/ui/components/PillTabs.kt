package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * A strip of mutually exclusive tabs, for the inspector panes, the API client's
 * request sections and its collections/history sidebar — three places that had
 * each inlined the same row of clickable boxes.
 *
 * Jewel's strip scrolls when it runs out of room rather than wrapping onto a
 * second line, which is what the inspector's version did when its pane was
 * dragged narrow; scrolling keeps the header strip one row tall, which is what
 * the layout around it assumes.
 */
@Composable
fun PillTabs(
    tabs: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    TabStrip(
        tabs = tabs.map { name ->
            TabData.Default(
                selected = name == selected,
                closable = false,
                onClose = {},
                onClick = { onSelect(name) },
                content = { _ ->
                    PzText(name, color = P.text, style = Typo.label, family = P.Ui, softWrap = false)
                },
            )
        },
        style = JewelTheme.defaultTabStyle,
        modifier = modifier,
    )
}
