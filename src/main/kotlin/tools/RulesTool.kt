package org.bittrace.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.bittrace.rules.RewriteOp
import org.bittrace.rules.RewriteStep
import org.bittrace.rules.RewriteTarget
import org.bittrace.rules.RuleFile
import org.bittrace.rules.RuleForm
import org.bittrace.rules.RuleKind
import org.bittrace.rules.RuleStore
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.CheckBox
import org.bittrace.ui.components.CheckBoxRow
import org.bittrace.ui.components.Dot
import org.bittrace.ui.components.Dropdown
import org.bittrace.ui.components.EmptyState
import org.bittrace.ui.components.FormField
import org.bittrace.ui.components.FormNote
import org.bittrace.ui.components.FormTextField
import org.bittrace.ui.components.GhostButton
import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.Segment
import org.bittrace.ui.components.SegmentedToggle
import org.bittrace.ui.components.TextArea
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.leftBorder
import org.bittrace.ui.rightBorder
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Map local, Map remote, Rewrite and Breakpoint rules, in one list.
 *
 * One list rather than four, because they are one ordered pipeline — the
 * sidecar evaluates them top to bottom — and four lists could not show that a
 * Map remote above a Rewrite runs first. The kind switches along the top are
 * the per-feature on/off that four separate tools would have given.
 *
 * Every change is written to the rules folder as it is made (see [RuleStore]);
 * there is nothing to apply. A rule's editor is the exception: it holds a draft
 * until Save, because a half-typed host pattern applied to live traffic would
 * match things it was never meant to.
 *
 * @param selected the rule to show, hoisted so a row menu in the main window
 *   can open this tool on the rule it just made
 */
@Composable
fun RulesTool(
    store: RuleStore,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val manifest = store.manifest
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(P.chrome).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CheckBoxRow("Rules on", manifest.enabled, onCheckedChange = store::setMasterEnabled)
            PzText("·", color = P.faint, style = Typo.label, family = P.Ui)
            RuleKind.entries.forEach { kind ->
                CheckBoxRow(
                    kind.label, manifest.kinds[kind], enabled = manifest.enabled,
                    onCheckedChange = { store.setKindEnabled(kind, it) },
                )
            }
            Spacer(Modifier.weight(1f))
            PzText(
                "${store.activeCount} of ${manifest.rules.size} active",
                color = P.dim, style = Typo.label, family = P.Ui, softWrap = false,
            )
            GhostButton("Reload from disk") { store.reload() }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(360.dp).fillMaxHeight().background(P.panel).rightBorder(P.line)) {
                RuleList(store, selected, onSelect)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val id = selected?.takeIf { s -> manifest.rules.any { it.id == s } }
                if (id == null) {
                    EmptyState("Pick a rule, or make one with New rule.", centred = true)
                } else {
                    RuleEditor(store, id, onDeleted = { onSelect(null) })
                }
            }
        }
    }
}

@Composable
private fun RuleList(store: RuleStore, selected: String?, onSelect: (String?) -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        PaneHeader(title = "Rules, in the order they run") {
            Spacer(Modifier.weight(1f))
            PrimaryButton("New rule", slim = true) {
                onSelect(store.create(RuleFile(name = "New rule", kind = RuleKind.REWRITE.id,
                    rewrite = listOf(RewriteStep()))))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (store.manifest.rules.isEmpty()) {
                    EmptyState(
                        "No rules yet. Make one here, or right-click a flow in the traffic grid " +
                            "to start one from it.",
                    )
                }
                store.ordered.forEachIndexed { index, (entry, rule) ->
                    val on = entry.id == selected
                    val error = store.localErrors[entry.id]
                        ?: store.status[entry.id]?.takeIf { !it.ok }?.error
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) P.sel else P.panel)
                            .then(if (on) Modifier.leftBorder(P.accent, 2.dp) else Modifier)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onSelect(entry.id) }
                            .bottomBorder(P.line)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckBox(entry.enabled, onCheckedChange = { store.setEnabled(entry.id, it) })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (error != null) {
                                    Dot(P.err)
                                    Spacer(Modifier.width(6.dp))
                                }
                                CellText(
                                    rule?.name?.ifBlank { null } ?: entry.id,
                                    color = if (store.isActive(entry.id)) P.text else P.faint,
                                    style = Typo.label, family = P.Ui,
                                )
                            }
                            CellText(
                                (rule?.ruleKind?.label ?: "Unknown kind") + "  " + (rule?.match?.summary() ?: ""),
                                color = P.dim, style = Typo.caption,
                            )
                        }
                        IconActionButton(
                            key = AllIconsKeys.General.ArrowUp,
                            contentDescription = "Run earlier",
                            enabled = index > 0,
                            onClick = { store.move(entry.id, index - 1) },
                        )
                        IconActionButton(
                            key = AllIconsKeys.General.ArrowDown,
                            contentDescription = "Run later",
                            enabled = index < store.manifest.rules.size - 1,
                            onClick = { store.move(entry.id, index + 1) },
                        )
                    }
                }
                val strays = remember(store.manifest) { store.strays() }
                if (strays.isNotEmpty()) {
                    FormNote(
                        "Not in the manifest, so not loaded: ${strays.joinToString { "$it.yaml" }}",
                        inset = true,
                    )
                }
            }
            VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        PzText(
            store.dir.toString(),
            color = P.faint, style = Typo.caption, family = P.Mono,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun RuleEditor(store: RuleStore, id: String, onDeleted: () -> Unit) {
    val saved = store.rules[id]
    // Re-seeded when the stored rule changes underneath — a reload from disk —
    // as well as when another rule is picked.
    var form by remember(id, saved) { mutableStateOf(saved?.let(RuleForm::of) ?: RuleForm()) }
    var problem by remember(id, saved) { mutableStateOf<String?>(null) }
    val dirty = saved == null || form != RuleForm.of(saved)
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        PaneHeader(title = form.name.ifBlank { id }) {
            Spacer(Modifier.weight(1f))
            GhostButton("Delete") {
                store.delete(id)
                onDeleted()
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("Revert", enabled = dirty && saved != null) {
                saved?.let { form = RuleForm.of(it) }
                problem = null
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Save", enabled = dirty, slim = true) {
                form.toRule()
                    .onSuccess { store.save(id, it); problem = null }
                    .onFailure { problem = it.message }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                problem?.let { PzText(it, color = P.err, style = Typo.label, family = P.Ui) }
                store.localErrors[id]?.let {
                    PzText("BitTrace could not read this file: $it", color = P.err, style = Typo.label, family = P.Ui)
                }
                store.status[id]?.takeIf { !it.ok }?.let {
                    PzText(
                        "The proxy is not using this version: ${it.error}",
                        color = P.err, style = Typo.label, family = P.Ui,
                    )
                }

                FormTextField("Name", form.name) { form = form.copy(name = it) }
                FormField("Kind") {
                    SegmentedToggle(
                        segments = RuleKind.entries.map { Segment(it.id, it.label) },
                        selected = form.kind.id,
                    ) { picked -> form = form.copy(kind = RuleKind.of(picked) ?: form.kind) }
                }

                Section("Matches")
                FormTextField("Methods", form.methods, placeholder = "any — or GET, POST") {
                    form = form.copy(methods = it)
                }
                FormTextField("Schemes", form.schemes, placeholder = "any — or https") { form = form.copy(schemes = it) }
                FormTextField("Host", form.host, placeholder = "any — or *.example.com") { form = form.copy(host = it) }
                FormTextField("Port", form.port, placeholder = "any", width = 90.dp) { form = form.copy(port = it) }
                FormTextField("Path", form.path, placeholder = "any — or /api/*") { form = form.copy(path = it) }
                FormField("") {
                    CheckBoxRow("Path is a regular expression", form.pathRegex) { form = form.copy(pathRegex = it) }
                }
                FormTextField("Query contains", form.query) { form = form.copy(query = it) }
                FormNote("Rules match the request as the client sent it, before any Map rule moved it.")

                when (form.kind) {
                    RuleKind.REWRITE -> RewriteSection(form.steps) { form = form.copy(steps = it) }
                    RuleKind.MAP_LOCAL -> {
                        Section("Serve from disk")
                        FormTextField("File or folder", form.localPath, placeholder = "/path/to/file.json") {
                            form = form.copy(localPath = it)
                        }
                        FormNote(
                            "A folder is served under the path's fixed prefix: /static/* onto a folder " +
                                "serves /static/app.js from app.js inside it. Relative paths are relative " +
                                "to the rules folder.",
                        )
                        FormTextField("Status", form.localStatus, width = 90.dp) { form = form.copy(localStatus = it) }
                        FormField("Headers") {
                            TextArea(
                                form.localHeaders, { form = form.copy(localHeaders = it) },
                                modifier = Modifier.weight(1f).height(110.dp),
                                placeholder = "Name: value, one per line",
                            )
                        }
                        FormNote("Content-Type comes from the file's extension unless a header here sets it.")
                    }
                    RuleKind.MAP_REMOTE -> {
                        Section("Send it to")
                        FormField("Scheme") {
                            Dropdown(
                                value = form.remoteScheme.ifEmpty { "keep" },
                                options = listOf("keep", "http", "https"),
                            ) { form = form.copy(remoteScheme = if (it == "keep") "" else it) }
                        }
                        FormTextField("Host", form.remoteHost, placeholder = "keep") { form = form.copy(remoteHost = it) }
                        FormTextField("Port", form.remotePort, placeholder = "keep", width = 90.dp) {
                            form = form.copy(remotePort = it)
                        }
                        FormTextField("Path", form.remotePath, placeholder = "keep — or /v2/*") {
                            form = form.copy(remotePath = it)
                        }
                        FormNote("A path ending in * swaps the matched prefix and keeps the rest; without it, it replaces the whole path.")
                        FormField("") {
                            CheckBoxRow("Keep the original Host header", form.preserveHost) {
                                form = form.copy(preserveHost = it)
                            }
                        }
                    }
                    RuleKind.BREAKPOINT -> {
                        Section("Pause")
                        FormField("") {
                            CheckBoxRow("Before the request is sent", form.pauseRequest) { form = form.copy(pauseRequest = it) }
                        }
                        FormField("") {
                            CheckBoxRow("Before the response is delivered", form.pauseResponse) {
                                form = form.copy(pauseResponse = it)
                            }
                        }
                        FormField("") {
                            CheckBoxRow("Also pause the API client's own sends", form.includeApiClient) {
                                form = form.copy(includeApiClient = it)
                            }
                        }
                        BreakpointTimeout(store)
                    }
                }
            }
            VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun BreakpointTimeout(store: RuleStore) {
    var text by remember(store.manifest.breakpointTimeoutSec) {
        mutableStateOf(store.manifest.breakpointTimeoutSec.toString())
    }
    FormField("Give up after") {
        TextInput(text, { text = it }, modifier = Modifier.width(90.dp))
        Spacer(Modifier.width(8.dp))
        PzText("seconds", color = P.dim, style = Typo.label, family = P.Ui)
        Spacer(Modifier.width(8.dp))
        GhostButton("Apply", enabled = text.toIntOrNull()?.let { it != store.manifest.breakpointTimeoutSec && it > 0 } == true) {
            text.toIntOrNull()?.let(store::setBreakpointTimeout)
        }
    }
    FormNote("A flow nobody answers goes on unchanged after this. Applies to every breakpoint.")
}

@Composable
private fun RewriteSection(steps: List<RewriteStep>, onChange: (List<RewriteStep>) -> Unit) {
    Section("Steps, in order")
    steps.forEachIndexed { i, step ->
        val target = RewriteTarget.of(step.target)
        val op = RewriteOp.of(step.op)
        fun update(s: RewriteStep) = onChange(steps.toMutableList().also { it[i] = s })
        Row(
            Modifier.fillMaxWidth().background(P.input).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dropdown(
                value = target.label,
                options = RewriteTarget.entries.map { it.label },
                width = 140.dp,
            ) { label ->
                val picked = RewriteTarget.entries.first { it.label == label }
                // A status can only be set; keep the op valid rather than saving
                // a step the proxy will refuse.
                update(step.copy(target = picked.id, op = if (picked == RewriteTarget.STATUS) RewriteOp.SET.id else step.op))
            }
            Dropdown(
                value = op.label,
                options = (if (target == RewriteTarget.STATUS) listOf(RewriteOp.SET) else RewriteOp.entries).map { it.label },
                width = 90.dp,
            ) { label -> update(step.copy(op = RewriteOp.entries.first { it.label == label }.id)) }
            if (target.named) {
                TextInput(step.name, { update(step.copy(name = it)) }, Modifier.width(140.dp), placeholder = "name")
            }
            if (op == RewriteOp.REPLACE) {
                TextInput(step.find, { update(step.copy(find = it)) }, Modifier.weight(1f), placeholder = "find")
                CheckBoxRow("regex", step.regex) { update(step.copy(regex = it)) }
            }
            if (op != RewriteOp.REMOVE) {
                TextInput(
                    step.value, { update(step.copy(value = it)) }, Modifier.weight(1f),
                    placeholder = if (op == RewriteOp.REPLACE) "with" else "value",
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconActionButton(
                key = AllIconsKeys.General.Delete,
                contentDescription = "Remove this step",
                onClick = { onChange(steps.filterIndexed { j, _ -> j != i }) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Add step") { onChange(steps + RewriteStep()) }
    }
    FormNote(
        "Body steps hold that body in memory to edit it; live streams and bodies over 16 MiB are " +
            "left alone. Replace with regex uses Python's syntax, since the proxy runs it.",
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    PzText(title, color = P.text, style = Typo.label, family = P.Ui)
}
