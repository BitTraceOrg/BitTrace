package org.bittrace.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The system clipboard as text, or blank when it holds something else.
 *
 * Reading it can throw if another process owns the clipboard mid-read, which is
 * not worth surfacing — an empty string produces the same "nothing to import"
 * result the user would get from an empty clipboard.
 */
fun clipboardText(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull().orEmpty()

/**
 * Puts [text] on the system clipboard, reporting whether it landed.
 *
 * Like reading, this can throw when another process owns the clipboard — the
 * caller decides whether a failure is worth telling anyone about.
 */
fun copyToClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}.isSuccess
