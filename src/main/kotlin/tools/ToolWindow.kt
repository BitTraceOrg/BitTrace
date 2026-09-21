package org.bittrace.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import org.bittrace.ui.P
import org.bittrace.ui.appIcon
import org.bittrace.ui.components.PzText
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

/**
 * A tool's window, wearing the same frame as the main one.
 *
 * `DecoratedWindow` rather than an undecorated `Window` with hand-drawn
 * controls. The app already requires the JetBrains Runtime for the main window,
 * so there was nothing to gain by re-implementing what it provides and plenty to
 * lose: minimise, maximise, restore, edge resize, Windows snap layouts and
 * double-click-to-maximise all arrive from the platform, correctly, instead of
 * from three buttons that approximate them.
 *
 * The same frame `AppDialog` now wears, and for the same reason. What still
 * separates them is what each is for: a dialog is kept in front of the window
 * that opened it and wants an answer, whereas a tool is a second place to work
 * — it sits behind the main window when you click back to it, and waits in the
 * taskbar as something to return to.
 */
@Composable
fun ToolWindow(
    title: String,
    onClose: () -> Unit,
    size: DpSize = DpSize(1100.dp, 720.dp),
    content: @Composable () -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onClose,
        state = rememberWindowState(size = size),
        title = "BitTrace — $title",
        // Its own task-bar entry, so it needs its own icon: a tool window is a
        // second place to work, and one showing the default Java cup beside the
        // main window reads as a different program.
        icon = appIcon(),
    ) {
        // The same strip the main window draws, holding a name where that one
        // holds a menu bar: a tool has nothing to put on the left, and a centred
        // title is what the platform would have shown anyway.
        TitleBar(Modifier.bottomBorder(P.line)) {
            PzText(title, color = P.dim, style = Typo.label, family = P.Ui, softWrap = false)
        }
        Box(Modifier.fillMaxSize().background(P.bg)) { content() }
    }
}
