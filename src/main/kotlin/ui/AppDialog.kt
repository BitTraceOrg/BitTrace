package org.bittrace.ui

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
import androidx.compose.ui.window.rememberDialogState
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

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
        undecorated = true,
        resizable = resizable,
    ) {
        Column(Modifier.fillMaxSize().background(surface).border1(P.line)) {
            WindowDraggableArea {
                Row(
                    Modifier.fillMaxWidth().background(P.chrome).bottomBorder(P.line).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PzText(
                        title,
                        color = P.dim, style = Typo.label, family = P.Ui,
                        weight = FontWeight.SemiBold, softWrap = false,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) {
                        IconActionButton(
                            key = AllIconsKeys.General.Close,
                            contentDescription = "Close",
                            onClick = onClose,
                        )
                    }
                }
            }

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
