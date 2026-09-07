package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.border1
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.topBorder
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.rememberDialogState
import org.bittrace.plugin.ThemeManager
import org.bittrace.ui.BitTraceTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.DecoratedWindow

/**
 * The app's own window frame, for the dialogs that do not use the platform's.
 *
 * Undecorated, because every other title bar in BitTrace is drawn rather than
 * borrowed and a dialog suddenly wearing the OS's would read as belonging to
 * another program. What that costs is the drag, the border and the close
 * button — which is exactly what this supplies, once rather than per dialog.
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
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = size),
        resizable = resizable,
        title = title
    ) {
        Column(Modifier.fillMaxSize().background(surface).border1(P.line)) {
            content()

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
