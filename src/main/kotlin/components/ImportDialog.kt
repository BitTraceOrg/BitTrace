package org.bittrace.components

import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.bittrace.api.ApiRequest
import org.bittrace.api.importRequest
import org.bittrace.plugin.importer.RequestImporter
import org.bittrace.ui.AppDialog
import org.bittrace.ui.CodeEditor
import org.bittrace.ui.GhostButton
import org.bittrace.ui.P
import org.bittrace.ui.PrimaryButton
import org.bittrace.ui.PzText
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.clipboardText

/**
 * The import window: paste a request in, see which importer claims it, import.
 *
 * A window rather than an inline panel because importing is a detour from
 * whatever you were building — the draft stays untouched behind it until the
 * import actually succeeds.
 *
 * Which importers are installed is shown rather than chosen: detection is by
 * [RequestImporter.canImport], so the list is there to say what the app
 * understands, and the claim updates as you type.
 */
@Composable
fun ImportRequestDialog(
    importers: List<RequestImporter>,
    onDismiss: () -> Unit,
    onImported: (ApiRequest, String, List<String>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Recomputed as the text changes, so the claim is visible before importing.
    val claimed = importers.firstOrNull { runCatching { it.canImport(text) }.getOrDefault(false) }

    AppDialog(
        title = "Import request",
        size = DpSize(720.dp, 520.dp),
        onClose = onDismiss,
        footer = {
            GhostButton("Paste") { text = clipboardText(); error = null }
            Spacer(Modifier.weight(1f))
            error?.let { PzText(it, color = P.err, style = Typo.caption, family = P.Ui) }
            if (error == null && claimed != null) {
                PzText("recognised by ${claimed.name}", color = P.faint, style = Typo.caption, family = P.Ui)
            }
            GhostButton("Cancel") { onDismiss() }
            PrimaryButton("Import", enabled = text.isNotBlank()) {
                val attempt = importRequest(text, importers)
                val request = attempt.request
                if (request == null) {
                    error = attempt.error ?: "Could not read that."
                } else {
                    onImported(request, attempt.importer, attempt.warnings)
                    onDismiss()
                }
            }
        },
    ) {
        // What the app can read, and which of them claims what is pasted.
        FlowRow(
            Modifier.fillMaxWidth().background(P.panel).bottomBorder(P.line)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PzText("Importers", color = P.faint, style = Typo.micro, family = P.Ui)
            Spacer(Modifier.width(10.dp))
            if (importers.isEmpty()) {
                PzText("none installed", color = P.warn, style = Typo.caption, family = P.Ui)
            }
            importers.forEach { importer ->
                val on = importer.id == claimed?.id
                Box(
                    Modifier.background(if (on) P.accent else P.head)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    PzText(
                        importer.name,
                        color = if (on) P.bg else P.dim,
                        style = Typo.caption, family = P.Ui, softWrap = false,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            CodeEditor(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxSize(),
                placeholder = "Paste a request — a curl command, for example",
            )
        }
    }
}
