package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.appIcon
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.topBorder
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

/**
 * A dialog, wearing the same frame as the main window and the tool windows.
 *
 * `DecoratedWindow` rather than an undecorated `DialogWindow` with a title
 * strip drawn by hand. The frame ends up looking the same either way, but
 * everything around it does not: minimise, maximise, edge resize, Windows snap
 * layouts and double-click-to-maximise arrive from the platform instead of from
 * a drag area and one close button, and the strip is the app's own `TitleBar`
 * rather than a second thing that had to be kept looking like it.
 *
 * What a `DialogWindow` gave and this does not is ownership: a dialog sat above
 * the window that opened it and stayed out of the taskbar. [alwaysOnTop]
 * replaces the first half, because a dialog lost behind the main window is one
 * somebody will think has vanished. The second half is simply gone — a dialog
 * now appears in the taskbar, as the tool windows already do.
 *
 * [content] fills the space between the title strip and the footer; [footer] is
 * the button row along the bottom, laid out in a `RowScope` so the caller puts
 * its own `Spacer(Modifier.weight(1f))` wherever the split belongs. Closing the
 * window calls [onClose], so dialogs should map it to their cancel: the safe
 * answer is the one you get by doing nothing.
 */
@Composable
fun AppDialog(
    title: String,
    size: DpSize,
    onClose: () -> Unit,
    resizable: Boolean = true,
    /** The body surface: [P.panel] for a panel, [P.bg] for one holding an editor. */
    surface: Color = P.bg,
    /** False for a dialog that may sensibly be left open behind the window. */
    alwaysOnTop: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onClose,
        state = rememberWindowState(size = size),
        resizable = resizable,
        alwaysOnTop = alwaysOnTop,
        title = title,
        // A decorated window carries a task-bar entry of its own, which a
        // `DialogWindow` did not — so a dialog now needs the app's icon for the
        // same reason the tool windows do.
        icon = appIcon(),
    ) {
        // The same strip the main window and the tool windows draw, holding the
        // dialog's name where the main one holds a menu bar. Close, minimise
        // and maximise are the platform's own, on the right, so nothing here
        // draws one.
        TitleBar(Modifier.bottomBorder(P.line)) {
            PzText(title, color = P.dim, style = Typo.label, family = P.Ui, softWrap = false)
        }
        Column(Modifier.fillMaxSize().background(surface)) {
            // Weighted, so the slack in a fixed-size dialog goes here rather
            // than under the footer. Without it the buttons floated wherever
            // the content ended and left a band of empty panel below them.
            Column(Modifier.fillMaxWidth().weight(1f)) { content() }

            if (footer != null) {
                Row(
                    Modifier.fillMaxWidth().background(P.panel).topBorder(P.line)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = footer,
                )
            }
        }
    }
}

/**
 * A dialog's Cancel-and-confirm pair.
 *
 * The four git dialogs each wrote this out and each added an explicit 8dp
 * spacer — on top of the [Arrangement.spacedBy] the footer row already applies,
 * so their buttons sat 16dp apart while the two older dialogs sat at 8dp. One
 * component removes the arithmetic and the inconsistency together.
 *
 * @param leading anything that belongs at the far left — a count, a status line,
 *   an extra verb. It is what the two dialogs that are not just Cancel/confirm
 *   need in order to use this too.
 */
@Composable
fun RowScope.DialogFooter(
    confirm: String,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    leading: @Composable RowScope.() -> Unit = {},
    onConfirm: () -> Unit,
) {
    Spacer(Modifier.weight(1f))
    GhostButton("Cancel", onClick = onCancel)
    PrimaryButton(confirm, enabled = confirmEnabled, onClick = onConfirm)
}

/**
 * A [FocusRequester] that takes focus once the composable appears.
 *
 * Three dialogs each carried this pair of lines. The `runCatching` is not
 * defensive noise: requesting focus on a node that is not yet attached throws,
 * and whether it is attached depends on composition order rather than on
 * anything the caller controls.
 */
@Composable
fun rememberFocused(): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return focus
}
