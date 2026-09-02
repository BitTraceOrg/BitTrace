package org.bittrace.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bittrace.ui.P
import org.bittrace.ui.rightBorder
import org.jetbrains.jewel.ui.component.SelectableIconActionButton
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The left rail — Home / Traffic / API, with Settings pinned to the bottom.
 *
 * It stays a sibling of the content area rather than overlaying it, so the
 * floating log panel (which is scoped to the content) never covers it.
 */
@Composable
fun Rail(nav: String, onNav: (String) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(40.dp).background(P.chrome).rightBorder(P.line)
            .padding(vertical = 4.dp),
    ) {
        RailItem("home", AllIconsKeys.Nodes.HomeFolder, nav, onNav)
        RailItem("traffic", AllIconsKeys.General.Web, nav, onNav)
        RailItem("api", AllIconsKeys.Actions.SwapPanels, nav, onNav)
        Spacer(Modifier.weight(1f))
        RailItem("settings", AllIconsKeys.General.Settings, nav, onNav)
    }
}

@Composable
private fun RailItem(key: String, icon: IconKey, nav: String, onNav: (String) -> Unit) {
    val on = nav == key
    // No divider between items and no accent edge: the stripe marks the active
    // tool with a filled 30x30 button and nothing else (DESIGN.MD §8).
    Box(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        SelectableIconActionButton(
            key = icon,
            contentDescription = key,
            selected = on,
            onClick = { onNav(key) },
            modifier = Modifier.size(30.dp),
        )
    }
}
