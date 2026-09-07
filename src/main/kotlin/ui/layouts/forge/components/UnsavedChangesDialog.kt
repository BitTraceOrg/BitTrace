package org.bittrace.ui.layouts.forge.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.bittrace.ui.components.AppDialog
import org.bittrace.ui.components.GhostButton
import org.bittrace.ui.P
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.Typo

/**
 * Asked before a request with unsaved edits is closed.
 *
 * Three answers, not two: saving and discarding are both destructive in one
 * direction or the other, so backing out has to be as easy as either. Dismissing
 * the window is a cancel — the safe answer is the one you get by doing nothing.
 */
@Composable
fun UnsavedChangesDialog(
    name: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AppDialog(
        title = "Unsaved changes",
        size = DpSize(420.dp, 172.dp),
        // Dismissing the window is the cancel, so the frame's own close is too.
        onClose = onCancel,
        resizable = false,
        surface = P.panel,
        footer = {
            Spacer(Modifier.weight(1f))
            GhostButton("Cancel", onClick = onCancel)
            GhostButton("Don't save", onClick = onDiscard)
            PrimaryButton("Save", onClick = onSave)
        },
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PzText("$name has changes that have not been saved.", color = P.text, style = Typo.h2, family = P.Ui)
            PzText(
                "Closing it now discards them.",
                color = P.faint, style = Typo.caption, family = P.Ui,
            )
        }
    }
}
