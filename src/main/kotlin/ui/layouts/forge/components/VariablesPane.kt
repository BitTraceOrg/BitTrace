package org.bittrace.ui.layouts.forge.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.bittrace.api.VariablesTab
import org.bittrace.ui.P
import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.Typo

/**
 * A project's variables, as a table.
 *
 * The whole pane rather than a split: there is no response to show. That is the
 * one structural difference from a request tab, and it is why the main column
 * branches on what is open rather than always drawing the request builder.
 *
 * Explicit save, like a request. Saving on every keystroke would write into a
 * git-tracked file per character — churning the project's dirty state and
 * costing a directory walk each time — and, worse, a table that is never dirty
 * is invisible to the guard that stops a checkout overwriting unsaved work.
 */
@Composable
fun VariablesPane(tab: VariablesTab, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().background(P.bg)) {
        PaneHeader(title = "Variables") {
            PzText(
                tab.project.fileName?.toString().orEmpty(),
                color = P.dim, style = Typo.label, family = P.Ui,
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton("Save", enabled = tab.dirty, onClick = onSave)
        }

        PaneHeader {
            PzText(
                "Use {{name}} in a URL, a header, a body or an auth field.",
                color = P.faint, style = Typo.caption, family = P.Ui,
            )
        }

        KvEditor(
            rows = tab.rows,
            nameHint = "name",
            valueHint = "value",
            modifier = Modifier.fillMaxSize(),
        ) { rows ->
            tab.rows = rows
            tab.dirty = true
        }
    }
}
