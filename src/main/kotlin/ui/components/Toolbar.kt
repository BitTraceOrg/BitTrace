package org.bittrace.ui.components

import org.bittrace.ui.ChipShape
import org.bittrace.ui.Typo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import kotlinx.coroutines.delay
import org.bittrace.ui.copyToClipboard
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys
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
        //
        // This centres because `icon.png` is centred *in its own canvas*, and
        // it did not used to be: the mark sat 7.5px high in 256, which put it a
        // whole pixel above the labels beside it. The fix belongs in the asset
        // rather than in an offset here — a dp nudge is right at one display
        // scale and wrong at the next, where the same error is half a pixel.
        // So: if the icon is ever regenerated, centre its alpha bounds in the
        // canvas, or this row goes subtly crooked again. (`packaging/icon.ico`
        // still carries the old offset, which nothing can see — it is only ever
        // drawn on its own, never beside text.)
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
 *
 * Click it to copy. Re-typing `127.0.0.1:8888` into a client's proxy settings
 * is the single most repeated thing anyone does with this bar, and it was the
 * one piece of text in the app you could see but not take.
 *
 * The label says what the dot used to. A green or red square is a legend you
 * have to know; "listening" and "stopped" are the same two states in words, and
 * the word was going to be there anyway.
 *
 * The confirmation is a toast under the bar rather than the label flipping to
 * "Copied". Swapping the label meant the one thing on screen saying whether the
 * proxy was up spent a second and a half saying something else instead.
 */
@Composable
fun AddressBar(host: String, port: Int, running: Boolean) {
    val address = "https://$host:$port"
    var copied by remember { mutableStateOf(false) }

    // Long enough to read at a glance, short enough not to sit over the toolbar
    // while you get on with something else.
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }

    Box {
        Row(
            // The one rounded thing in an otherwise square app, and deliberately so:
            // the radius is what makes this read as an address field rather than as
            // another panel. Background and border take the same shape — give the
            // fill a shape and not the outline, and square corners show through it.
            Modifier
                .height(25.dp)
                .background(P.bg, ChipShape)
                .border(1.dp, P.line, ChipShape)
                // Clipped to the shape so the click target and the hover fill stop
                // at the rounded edge rather than at the square bounds behind it.
                .clip(ChipShape)
                .clickable { copied = copyToClipboard(address) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            PzText(
                if (running) "Listening:" else "Stopped:",
                color = if (running) P.dim else P.err,
                style = Typo.label,
                softWrap = false,
            )
            Spacer(Modifier.width(7.dp))
            PzText("https://", color = P.faint, style = Typo.label)
            PzText(host, color = P.text, style = Typo.label)
            PzText(":", color = P.faint, style = Typo.label)
            PzText(port.toString(), color = P.accent, style = Typo.label, weight = FontWeight.Medium)
        }

        if (copied) {
            // Dismissable, though it also goes on its own: a toast that can
            // only be waited out is a toast that is in the way.
            Popup(popupPositionProvider = BelowAnchor, onDismissRequest = { copied = false }) {
                Toast("Address copied to the clipboard")
            }
        }
    }
}

/**
 * A short-lived confirmation, floated under whatever set it off.
 *
 * Deliberately not a control: nothing in it can be clicked and it says one
 * thing. Anything that needs an answer is a dialog, and anything worth keeping
 * goes to the log — this is for the actions whose whole result is "that
 * worked", where saying nothing at all is the only worse option.
 */
@Composable
private fun Toast(text: String) {
    Row(
        Modifier
            .padding(top = 6.dp)
            .background(P.panel, ChipShape)
            .border(1.dp, P.line, ChipShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = AllIconsKeys.General.InspectionsOK,
            contentDescription = null,
            tint = P.ok,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        PzText(text, color = P.text, style = Typo.label, softWrap = false)
    }
}

/**
 * Under the anchor, left edges aligned, clamped to the window.
 *
 * The mirror of the status bar's `AbovePopup`, and for the mirrored reason:
 * the toolbar is the top edge, so there is nowhere above it to put anything.
 */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchorBounds.bottom.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

