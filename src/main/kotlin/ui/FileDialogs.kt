package org.bittrace.ui

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Native open/save dialogs for HAR files.
 *
 * `java.awt.FileDialog` rather than `JFileChooser`: it is the platform's own
 * dialog, which suits an app that already replaces its window chrome. Both
 * block the event thread while open — expected for a modal dialog — so the work
 * that follows must hop to a background thread.
 */
object FileDialogs {

    /** The chosen file, or null if the user cancelled. */
    fun openHar(owner: Frame): Path? = show(owner, "Import HAR", FileDialog.LOAD, null)

    /**
     * The chosen destination, or null if cancelled. A missing extension gets
     * `.har` appended, so "checkout" saves as "checkout.har".
     */
    fun saveHar(owner: Frame, suggested: String): Path? =
        show(owner, "Export HAR", FileDialog.SAVE, suggested)?.let {
            if (it.fileName.toString().contains('.')) it
            else it.resolveSibling("${it.fileName}.har")
        }

    /** The chosen archive, or null if the user cancelled. */
    fun openZip(owner: Frame): Path? = show(owner, "Import zip", FileDialog.LOAD, null, "zip")

    /** The chosen destination, or null if cancelled. `.zip` is appended if missing. */
    fun saveZip(owner: Frame, suggested: String): Path? =
        show(owner, "Export zip", FileDialog.SAVE, suggested, "zip")?.let {
            if (it.fileName.toString().contains('.')) it
            else it.resolveSibling("${it.fileName}.zip")
        }

    /**
     * Shows [path] in the platform's file manager.
     *
     * Windows gets `explorer /select`, which highlights the file rather than
     * merely opening the folder around it — worth the special case, since the
     * point of opening it is to hand somebody the file they just made. Its exit
     * code is deliberately not checked: explorer returns 1 on success.
     *
     * Every failure here is swallowed. Revealing a file is a courtesy after the
     * work is done, and an export that wrote correctly must not be reported as
     * having failed because a file manager would not start.
     */
    fun revealInFolder(path: Path) {
        val absolute = path.toAbsolutePath()
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            runCatching { ProcessBuilder("explorer", "/select,$absolute").start() }
            return
        }
        runCatching {
            val parent = absolute.parent ?: return
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(parent.toFile())
            }
        }
    }

    /** Any file, for a request body. No extension filter — bodies are anything. */
    fun openAny(owner: Frame, title: String = "Choose a file"): Path? {
        val dialog = FileDialog(owner, title, FileDialog.LOAD).apply { isVisible = true }
        val chosen = dialog.file ?: return null
        val directory = dialog.directory ?: return Paths.get(chosen)
        return Paths.get(directory, chosen)
    }

    private fun show(
        owner: Frame,
        title: String,
        mode: Int,
        suggested: String?,
        extension: String = "har",
    ): Path? {
        val dialog = FileDialog(owner, title, mode).apply {
            suggested?.let { file = it }
            // Honoured on Windows; other platforms fall back to showing everything.
            setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
            isVisible = true
        }
        val chosen = dialog.file ?: return null
        val directory = dialog.directory ?: return Paths.get(chosen)
        return Paths.get(directory, chosen)
    }
}
