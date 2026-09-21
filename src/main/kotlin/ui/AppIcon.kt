package org.bittrace.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * The app's icon, for every window that has a frame to hang one on.
 *
 * The packaged app gets its icon from the installer, which sets it once for the
 * process; a `gradlew run` has no installer, so a window that does not ask for
 * one comes up wearing the default Java cup. That was true of the tool windows
 * and the dialogs, which each opened a task-bar entry of their own and each
 * showed a different icon from the main window.
 *
 * One function rather than the file name written at four call sites: a resource
 * that is loaded by string is a resource that goes missing quietly, and it
 * should go missing in one place.
 */
@Composable
fun appIcon(): Painter = painterResource(ICON_RESOURCE)

/** In `src/main/resources`, and named in `build.gradle.kts` for the installer. */
private const val ICON_RESOURCE = "icon.png"
