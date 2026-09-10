package org.bittrace.ui.components

import org.bittrace.ui.ChipShape
import org.bittrace.ui.Typo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import org.bittrace.ui.P

import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.window.utils.clientRegion

/**
 * One entry in a toolbar menu; a null [onClick] renders it disabled.
 *
 * An entry with a [submenu] opens it rather than doing anything itself, so the
 * two are alternatives: give it actions or give it a click, not both.
 */
class MenuAction(
    val label: String,
    val hint: String = "",
    val submenu: List<MenuAction> = emptyList(),
    /**
     * Draws a rule above this entry, splitting the menu into groups.
     *
     * A flag on the entry below the rule rather than a separator entry of its
     * own: a list holding two kinds of thing needs every reader of it to handle
     * both, and a stray separator at the end of a group that later loses its
     * last item would draw a rule against nothing.
     */
    val separatorBefore: Boolean = false,
    // Last, so `MenuAction("Exit") { … }` still binds the trailing lambda here.
    val onClick: (() -> Unit)? = null,
)

/** A menu title plus the actions behind it. */
class Menu(val label: String, val actions: List<MenuAction>)

/**
 * The app menus, as a row of titles that open Jewel popup menus.
 *
 * The window frame — dragging, minimise, maximise, close, resize and Windows
 * snap layouts — belongs to Jewel's `DecoratedWindow`/`TitleBar`, which is why
 * there are no window buttons here any more. What survives is the part that is
 * BitTrace's rather than the platform's: which menus exist, that only one opens
 * at a time, and that sliding along the row switches between them.
 *
 * Every interactive strip is marked as a client region, or the title bar would
 * swallow its clicks as window drags.
 */
@Composable
fun MenuBar(menus: List<Menu>, modifier: Modifier = Modifier) {
    // Only one menu may be open, and hovering the row moves between them once
    // any is open — the behaviour a real menu bar has.
    var openMenu by remember { mutableStateOf<String?>(null) }

    Row(modifier.fillMaxHeight().clientRegion("menu-bar"), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(8.dp))
        // The app's own mark, where a plain accent square used to sit. Not
        // decorative: the title bar is drawn by Jewel rather than by Windows, so
        // this is the only place in the frame the application names itself.
        //
        // 16dp inside a 30dp bar (see `TitleBarMetrics` in `ui/JewelBridge.kt`)
        // — the icon's own artwork carries its margins, so it does not want
        // padding of its own on top.
        Image(
            painter = painterResource("icon.png"),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        menus.forEach { menu ->
            MenuButton(
                menu = menu,
                open = openMenu == menu.label,
                anyOpen = openMenu != null,
                onToggle = { openMenu = if (openMenu == menu.label) null else menu.label },
                onHover = { openMenu = menu.label },
                onDismiss = { openMenu = null },
            )
        }
    }
}

@Composable
private fun MenuButton(
    menu: Menu,
    open: Boolean,
    anyOpen: Boolean,
    onToggle: () -> Unit,
    onHover: () -> Unit,
    onDismiss: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // With a menu already open, sliding along the bar switches to the one under
    // the pointer — what a menu bar does everywhere else.
    LaunchedEffect(hovered, anyOpen) {
        if (hovered && anyOpen && !open) onHover()
    }

    Box {
        // The open menu keeps a background of its own — Jewel's button styles
        // hover and press, but has no notion of "my popup is showing".
        Box(Modifier.fillMaxHeight().background(if (open) P.sel else Color.Transparent)) {
            ActionButton(
                onClick = onToggle,
                interactionSource = interaction,
                contentPadding = PaddingValues(horizontal = 9.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                PzText(menu.label, color = if (open) P.text else P.dim, style = Typo.label, family = P.Ui)
            }
        }
        if (open) {
            // Jewel owns the popup: keyboard navigation, submenu handling and
            // item styling come with it. We keep only which entries exist.
            PopupMenu(
                onDismissRequest = { onDismiss(); true },
                horizontalAlignment = Alignment.Start,
            ) {
                menuEntries(menu.actions, onDismiss)
            }
        }
    }
}

/**
 * Maps one [MenuAction] onto a Jewel menu entry. A null [MenuAction.onClick] is
 * a state the app forbids right now, not a placeholder, so it renders disabled;
 * [MenuAction.hint] rides in the keybinding slot, which is where Int UI puts
 * right-aligned trailing text.
 */
private fun MenuScope.menuEntries(actions: List<MenuAction>, onDismiss: () -> Unit) {
    actions.forEachIndexed { index, action ->
        // A rule above the first entry would sit against the popup's own top
        // edge, so a leading separator is dropped rather than drawn.
        if (action.separatorBefore && index > 0) separator()
        menuEntry(action, onDismiss)
    }
}

private fun MenuScope.menuEntry(action: MenuAction, onDismiss: () -> Unit) {
    if (action.submenu.isNotEmpty()) {
        submenu(
            submenu = { menuEntries(action.submenu, onDismiss) },
            content = { PzText(action.label, color = P.text, style = Typo.label, family = P.Ui) },
        )
    } else {
        selectableItem(
            selected = false,
            keybinding = action.hint.takeIf { it.isNotEmpty() }?.let { setOf(it) },
            onClick = { action.onClick?.invoke(); onDismiss() },
            enabled = action.onClick != null,
        ) {
            PzText(action.label, color = P.text, style = Typo.label, family = P.Ui)
        }
    }
}

// ---------------------------------------------------------------------------
// Address
// ---------------------------------------------------------------------------

/**
 * The proxy endpoint, styled like a browser address field: scheme dimmed, host
 * in body text, port called out in the accent since it is the part users change
 * and re-type into their client.
 */
@Composable
fun AddressBar(host: String, port: Int, running: Boolean) {
    Row(
        // The one rounded thing in an otherwise square app, and deliberately so:
        // the radius is what makes this read as an address field rather than as
        // another panel. Background and border take the same shape — give the
        // fill a shape and not the outline, and square corners show through it.
        Modifier
            .height(25.dp)
            .background(P.bg, ChipShape)
            .border(1.dp, P.line, ChipShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Dot(if (running) P.ok else P.err, 5)
        Spacer(Modifier.width(7.dp))
        PzText("https://", color = P.faint, style = Typo.label)
        PzText(host, color = P.text, style = Typo.label)
        PzText(":", color = P.faint, style = Typo.label)
        PzText(port.toString(), color = P.accent, style = Typo.label, weight = FontWeight.Medium)
    }
}

