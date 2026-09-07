package org.bittrace.ui.layouts.forge.components

import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.EmptyState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import org.bittrace.git.ChangeKind
import org.bittrace.git.FileChange
import org.bittrace.ui.components.AppDialog
import org.bittrace.ui.components.DialogFooter
import org.bittrace.ui.components.rememberFocused
import org.bittrace.ui.components.CheckBoxRow
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.Typo

/**
 * Names a new branch.
 *
 * The focus dance is the one from the tree's rename field, and for the same
 * reason: a field that requests focus in a `LaunchedEffect` reports *unfocused*
 * on the frame its modifier attaches, before the effect has run.
 */
@Composable
fun NewBranchDialog(current: String?, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = rememberFocused()

    val valid = name.isNotBlank() && name.none { it.isWhitespace() }

    AppDialog(
        title = "New branch",
        size = DpSize(420.dp, 190.dp),
        resizable = false,
        surface = P.panel,
        onClose = onDismiss,
        footer = { DialogFooter("Create", onDismiss, valid) { onCreate(name.trim()) } },
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            PzText(
                current?.let { "Branching from $it." } ?: "Branching from the current commit.",
                color = P.dim, style = Typo.label, family = P.Ui,
            )
            Spacer(Modifier.height(10.dp))
            TextInput(
                value = name,
                onValueChange = { name = it },
                placeholder = "feature/retry-policy",
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (valid) onCreate(name.trim())
                                true
                            }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
            )
            if (name.isNotBlank() && !valid) {
                Spacer(Modifier.height(6.dp))
                PzText("A branch name cannot contain spaces.", color = P.err, style = Typo.caption)
            }
        }
    }
}

/**
 * Picks what goes into a commit, and says why.
 *
 * A tick list rather than a bare message box because a project is one folder of
 * many requests: half-finished work sits beside the thing you actually want to
 * record, and a commit that always takes everything makes you tidy up first.
 */
@Composable
fun CommitDialog(
    project: String,
    changes: List<FileChange>,
    onDismiss: () -> Unit,
    onCommit: (List<Path>, String) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf(emptySet<Path>()) }
    val focus = rememberFocused()

    val picked = changes.map { it.path }.filterNot { it in excluded }
    val ready = message.isNotBlank() && picked.isNotEmpty()

    AppDialog(
        title = "Commit to $project",
        size = DpSize(560.dp, 420.dp),
        surface = P.panel,
        onClose = onDismiss,
        footer = {
            DialogFooter(
                confirm = "Commit",
                onCancel = onDismiss,
                confirmEnabled = ready,
                leading = {
                    PzText(
                        "${picked.size} of ${changes.size} selected",
                        color = P.faint, style = Typo.label, family = P.Ui,
                    )
                },
            ) { onCommit(picked, message.trim()) }
        },
    ) {
        Column(Modifier.fillMaxWidth().weight(1f)) {
            PaneHeader {
                PzText("CHANGES", color = P.faint, style = Typo.micro, family = P.Ui)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (changes.isEmpty()) {
                    EmptyState("Nothing has changed since the last commit.")
                }
                changes.forEach { change ->
                    CheckBoxRow(
                        checked = change.path !in excluded,
                        modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp),
                        onCheckedChange = { on ->
                            excluded = if (on) excluded.minusElement(change.path) else excluded.plusElement(change.path)
                        },
                    ) {
                        Spacer(Modifier.width(2.dp))
                        PzText(
                            change.path.fileName?.toString().orEmpty(),
                            color = P.text, style = Typo.label, maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        PzText(labelOf(change.kind), color = toneOf(change.kind), style = Typo.micro, family = P.Ui)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            TextInput(
                value = message,
                onValueChange = { message = it },
                placeholder = "What changed, and why",
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

/**
 * Refuses a checkout or pull while tabs hold unsaved work.
 *
 * The alternative — letting git write over the files and reloading — trades a
 * dialog for silently discarded edits, which is not a trade. There is no
 * "discard" button here for the same reason: the operation can simply wait.
 */
@Composable
fun PendingChangesDialog(
    action: String,
    names: List<String>,
    onDismiss: () -> Unit,
    onSaveAll: () -> Unit,
) {
    AppDialog(
        title = "Unsaved changes",
        size = DpSize(440.dp, 220.dp),
        resizable = false,
        surface = P.panel,
        onClose = onDismiss,
        footer = { DialogFooter("Save all and $action", onDismiss) { onSaveAll() } },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(14.dp)) {
            PzText(
                "$action would overwrite files these requests are still being edited in:",
                color = P.text, style = Typo.label, family = P.Ui,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                names.forEach { PzText("• $it", color = P.warn, style = Typo.label) }
            }
        }
    }
}

private fun labelOf(kind: ChangeKind): String = when (kind) {
    ChangeKind.ADDED -> "new"
    ChangeKind.MODIFIED -> "changed"
    ChangeKind.REMOVED -> "deleted"
    ChangeKind.UNTRACKED -> "new"
    ChangeKind.CONFLICT -> "conflict"
}

private fun toneOf(kind: ChangeKind) = when (kind) {
    ChangeKind.ADDED, ChangeKind.UNTRACKED -> P.ok
    ChangeKind.MODIFIED -> P.warn
    ChangeKind.REMOVED -> P.err
    ChangeKind.CONFLICT -> P.err
}

/**
 * Sends a project that only exists locally to a remote for the first time.
 *
 * Asks for a URL and nothing else: the repository already exists, the branch
 * already has commits, and the only thing missing is somewhere to put them. It
 * does not offer to create the remote repository — that is a forge's job, and
 * an app that silently made repositories on your account would be doing
 * something you did not ask for.
 */
@Composable
fun PublishDialog(project: String, branch: String?, onDismiss: () -> Unit, onPublish: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val focus = rememberFocused()

    val trimmed = url.trim()
    val ssh = trimmed.startsWith("git@") || trimmed.startsWith("ssh://")
    val valid = trimmed.isNotBlank() && (ssh || trimmed.startsWith("http"))

    AppDialog(
        title = "Publish $project",
        size = DpSize(500.dp, 260.dp),
        resizable = false,
        surface = P.panel,
        onClose = onDismiss,
        footer = { DialogFooter("Publish", onDismiss, valid) { onPublish(trimmed) } },
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            PzText(
                "Create an empty repository on your host first, then paste its URL here. " +
                    "${branch ?: "The current branch"} will be pushed and set to track it.",
                color = P.dim, style = Typo.label, family = P.Ui,
            )
            Spacer(Modifier.height(10.dp))
            TextInput(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://github.com/you/payments-api.git",
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (valid) onPublish(trimmed)
                                true
                            }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
            )
            Spacer(Modifier.height(10.dp))
            PzText(
                if (ssh) {
                    "SSH: your ~/.ssh keys and agent are used. Nothing is needed in Settings."
                } else {
                    "HTTPS: the token from Settings › Git is used."
                },
                color = P.faint, style = Typo.caption, family = P.Ui,
            )
            Spacer(Modifier.height(4.dp))
            // Worth saying explicitly at the moment somebody first sends a
            // project outward, which is the point at which "what exactly is in
            // these files" stops being a private question.
            PzText(
                "Everything in the project is committed, variables included — so any " +
                    "password or token you have typed goes to the remote with it.",
                color = P.warn, style = Typo.caption, family = P.Ui,
            )
        }
    }
}
