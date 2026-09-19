package org.bittrace.ui.layouts.forge.components

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bittrace.api.CollectionNode
import org.bittrace.api.Node
import org.bittrace.api.ProjectNode
import org.bittrace.api.ProjectSection
import org.bittrace.api.ProjectTab
import org.bittrace.api.RequestNode
import org.bittrace.git.GitState
import org.bittrace.ui.dayClockOf
import org.bittrace.ui.rightBorder
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.leftBorder
import org.bittrace.ui.components.GhostButton
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import org.bittrace.ui.components.TextArea
import org.bittrace.ui.components.CheckBoxRow
import org.bittrace.git.GitStore
import org.bittrace.git.FileChange
import org.bittrace.git.CommitEntry
import java.nio.file.Path
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import org.bittrace.ui.P
import org.bittrace.ui.Typo
import org.bittrace.ui.components.EmptyState
import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.PrimaryButton
import org.jetbrains.jewel.ui.component.DefaultSplitButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.TabContentSwitcher
import org.bittrace.ui.components.TabLabel
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Dropdown as JewelDropdown
import androidx.compose.foundation.ExperimentalFoundationApi
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * What the git panel can set off, from the view that owns the dialogs.
 *
 * A bundle rather than five parameters on [ProjectPane]: they arrive together,
 * they are all the same kind of thing, and the pane passes them straight down
 * to the section that uses them.
 *
 * Each is an operation the panel gives a control of its own. Anything not
 * here — publishing, or a plain push — stays on the tree's context menu, which
 * is still built by `gitItems`.
 */
class ProjectGitActions(
    val switchBranch: (String) -> Unit,
    val newBranch: () -> Unit,
    val publish: () -> Unit,
    val push: () -> Unit,
    val fetch: () -> Unit,
    val pull: () -> Unit,
    /** Put the working tree back to how it looked at this commit, staged. */
    val restoreTo: (CommitEntry) -> Unit,
    /** Fetch, then replay this branch's commits on top of the upstream. */
    val resync: () -> Unit,
    /** Make this project a repository, with a first commit. */
    val initRepo: () -> Unit,
)

/**
 * A project, as a tab: what it holds, its variables, and its git controls.
 *
 * Three bands down the pane — the project's own name, the section strip, then
 * the section. The name sits above the strip rather than inside it because it
 * names the whole tab, not the section you happen to be on; putting it in the
 * strip's own header made it read as a label for Overview alone.
 *
 * The sections are views over one folder, so this uses the [TabContentSwitcher]
 * overload that keeps the body in the caller's hands — and the selected section
 * lives on [ProjectTab], so leaving the tab and coming back does not send you
 * to Overview again.
 *
 * Save lives in the Variables section, with the only table it writes. The tab's
 * dirty dot on the editor strip is what says there is unsaved work from any
 * other section, and the checkout guard names the tab by title when it stops a
 * branch switch — so neither depends on the button being on screen.
 */
@Composable
fun ProjectPane(
    tab: ProjectTab,
    node: ProjectNode?,
    git: GitStore,
    actions: ProjectGitActions,
    onSave: () -> Unit,
) {
    val state = git.stateOf(tab.project)

    Column(Modifier.fillMaxSize().background(P.bg)) {
        ProjectHeading(tab)

        TabContentSwitcher(
            tabs = ProjectSection.entries.map { TabLabel(it.label) },
            selected = tab.section.label,
            modifier = Modifier.weight(1f),
            onSelect = { label ->
                tab.section = ProjectSection.entries.first { it.label == label }
            },
        ) {
            when (tab.section) {
                ProjectSection.Overview -> Overview(tab, node, state)
                ProjectSection.Variables -> Variables(tab, onSave)
                ProjectSection.Git -> Git(tab.project, git, state, actions)
            }
        }
    }
}

/**
 * The project's icon and name, above the section strip.
 *
 * Its own padded row rather than a [PaneHeader], which is sized for a strip of
 * controls and would put this name at the same rank as the section labels
 * directly beneath it. The heading is the tab's subject; the strip below
 * divides it.
 */
@Composable
private fun ProjectHeading(tab: ProjectTab) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tree's own project glyph and tint, so a project reads as the same
        // thing in the sidebar and here.
        Icon(
            key = AllIconsKeys.Toolwindows.ToolWindowProject,
            contentDescription = "Project",
            tint = P.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        // Labelled rather than bare: the heading sits above a strip of section
        // names, and a lone word there read as one more of them.
        PzText(
            "Project name:",
            color = P.dim, style = Typo.h2, family = P.Ui,
            softWrap = false, maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        PzText(
            tab.projectName,
            color = P.text, style = Typo.h2, family = P.Ui,
            weight = FontWeight.SemiBold, softWrap = false, maxLines = 1,
        )
    }
}

//region Sections ───────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What this project is and what is in it.
 *
 * A column of readouts, each with its own tinted glyph. The glyph is what makes
 * this scannable — five lines of label-and-value read as a form, where an icon
 * per row lets you find the one you came for without reading any of the others.
 *
 * Safe to scroll, unlike the pane's earlier shape: nothing in here scrolls
 * itself, so there is no child to measure with an unbounded height.
 */
@Composable
private fun Overview(tab: ProjectTab, node: ProjectNode?, git: GitState) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Column(
            Modifier.fillMaxWidth().padding(INSET),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InfoRow(
                key = AllIconsKeys.Nodes.HomeFolder,
                tint = P.info,
                label = "Location",
                value = tab.project.toString(),
            )
            InfoRow(
                key = AllIconsKeys.Vcs.Branch,
                tint = branchTint(git),
                label = "Branch",
                value = if (git.repo) "${git.label ?: "—"} · ${workingTree(git)}" else "Not a repository",
                tone = if (git.repo) P.text else P.faint,
            )
            InfoRow(
                // The tree's variables glyph and tint, for the same reason the
                // heading borrows the project's.
                key = AllIconsKeys.Debugger.VariablesTab,
                tint = P.key,
                label = "Variables",
                value = count(tab.rows.count { it.name.isNotBlank() }, "variable"),
            )
            InfoRow(
                key = AllIconsKeys.FileTypes.Http,
                tint = P.send,
                label = "Requests",
                value = node?.let { count(countRequests(it.children), "request") } ?: UNKNOWN,
                tone = if (node == null) P.err else P.text,
            )
            InfoRow(
                key = AllIconsKeys.Toolwindows.ToolWindowProject,
                tint = P.warn,
                label = "Collections",
                value = node?.let { count(it.children.size, "collection") } ?: UNKNOWN,
                tone = if (node == null) P.err else P.text,
            )
        }
    }
}

/**
 * The project's variables, as a table.
 *
 * Save sits on this section's own header, beside the only rows it writes.
 * Explicit, like a request: saving on every keystroke would write into a
 * git-tracked file per character — churning the project's dirty state and
 * costing a directory walk each time — and, worse, a table that is never dirty
 * is invisible to the guard that stops a checkout overwriting unsaved work.
 */
@Composable
private fun Variables(tab: ProjectTab, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Padded from the inside rather than by insetting the header itself:
        // `PaneHeader` draws its own surface and hairline, so padding its
        // modifier would have left a band of pane behind the strip. Its height
        // is a minimum, so taller content simply makes the row taller.
        PaneHeader {
            PzText(
                "Use {{name}} in a URL, a header, a body or an auth field.",
                color = P.faint, style = Typo.caption, family = P.Ui,
                modifier = Modifier.padding(vertical = HEADER_PAD),
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                "Save",
                enabled = tab.dirty,
                // Horizontal too: the button sat hard against the pane's right
                // edge, where `PaneHeader`'s own 10dp is the whole gap and a
                // filled control reads as clipped rather than merely close.
                modifier = Modifier.padding(vertical = HEADER_PAD, horizontal = 6.dp),
                onClick = onSave,
            )
        }
        KvEditor(
            rows = tab.rows,
            nameHint = "name",
            valueHint = "value",
            descriptions = true,
            modifier = Modifier.fillMaxSize(),
        ) { rows ->
            tab.rows = rows
            tab.dirty = true
        }
    }
}

/**
 * The project's git panel: what has changed on the left, what has happened on
 * the right.
 *
 * The split is the point. Committing is a narrow, repetitive job — read a short
 * list, type a line, press a button — and it wants a column, where history is a
 * wide one and wants the room. Stacking them, as the status readout used to,
 * gave the thing you do every hour the same weight as the thing you read once a
 * week.
 *
 * Status and log are read when the panel is shown and again whenever HEAD, the
 * branch or the dirty count moves. Those three are what [GitState] already
 * watches, so a commit made here, a pull from the menu, or a branch switch all
 * refresh this without the panel having to know which of them happened.
 */
@Composable
private fun Git(
    project: Path,
    git: GitStore,
    state: GitState,
    actions: ProjectGitActions,
) {
    if (!state.repo) {
        NoRepository(actions.initRepo)
        return
    }

    // Keyed on the project so opening a second project's tab cannot briefly
    // show the first one's changes.
    var changes by remember(project) { mutableStateOf(emptyList<FileChange>()) }
    var history by remember(project) { mutableStateOf(emptyList<CommitEntry>()) }
    var message by remember(project) { mutableStateOf("") }
    var excluded by remember(project) { mutableStateOf(emptySet<Path>()) }
    var loading by remember(project) { mutableStateOf(true) }
    var selected by remember(project) { mutableStateOf<CommitEntry?>(null) }
    var files by remember(project) { mutableStateOf(emptyList<FileChange>()) }

    // The selected commit's own files, read only once one is picked. A diff per
    // row as the log rendered would be one tree walk per commit on screen.
    LaunchedEffect(project, selected?.id) {
        val commit = selected
        files = if (commit == null) {
            emptyList()
        } else {
            git.service.changedIn(project, commit.id).getOrDefault(emptyList())
        }
    }

    LaunchedEffect(project, state.head, state.branch, state.dirty) {
        loading = true
        // Both suspend and do their own thread handling. A failure leaves the
        // lists as they were rather than blanking a panel you are reading.
        git.service.status(project).onSuccess { changes = it.changes }
        git.service.log(project, limit = HISTORY_LIMIT).onSuccess { fresh ->
            history = fresh
            // A restore or a rebase rewrites what is behind the selection, so a
            // commit the log no longer carries stops being selected rather than
            // leaving a file list describing something that is not there.
            if (fresh.none { it.id == selected?.id }) selected = null
        }
        loading = false
    }

    // Clearing before the run, not in its callback: a commit is fire-and-forget
    // on the store's own scope, and a box that still held the message afterwards
    // invites sending it twice.
    fun take(): String {
        val text = message.trim()
        message = ""
        excluded = emptySet()
        return text
    }

    Row(Modifier.fillMaxSize()) {
        ChangesSidebar(
            state = state,
            changes = changes,
            message = message,
            excluded = excluded,
            onMessage = { message = it },
            onToggle = { path, on -> excluded = if (on) excluded - path else excluded + path },
            actions = actions,
            onCommit = { paths ->
                val text = take()
                git.run(project, "Commit") {
                    commit(project, paths, text).map { "Committed ${paths.size} file(s) as ${it.short}." }
                }
            },
            onCommitAndPush = { paths ->
                val text = take()
                val setUpstream = state.upstream == null
                git.run(project, "Commit and push") {
                    // One `run`, so the two report as the single action the
                    // button names — and `mapCatching`, so a push that fails
                    // surfaces as the failure of "Commit and push" rather than
                    // being swallowed behind a commit that did succeed. The
                    // commit still stands; the message says only that it was
                    // not pushed, which is exactly the state on disk.
                    commit(project, paths, text).mapCatching { entry ->
                        val report = push(project, setUpstream = setUpstream).getOrThrow()
                        "Committed ${paths.size} file(s) as ${entry.short} and pushed to ${report.remote}."
                    }
                }
            },
        )
        History(
            history = history,
            loading = loading,
            selected = selected,
            files = files,
            canResync = state.hasRemote && !state.detached,
            onSelect = { commit -> selected = if (selected?.id == commit.id) null else commit },
            actions = actions,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What the Git section shows before there is a repository to show.
 *
 * A card rather than the one grey line this used to be. "Not a git repository"
 * is an accurate thing to tell someone who has never set one up and a useless
 * one: the panel's whole job is the next step, and the next step was on a
 * context menu somewhere else.
 *
 * Down from the top rather than centred in the pane, so it sits where the
 * branch row would be and does not jump when the panel fills in behind it.
 */
@Composable
private fun NoRepository(onInit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(top = CARD_TOP)
                .width(CARD_WIDTH)
                .background(P.panel, CardShape)
                .border(1.dp, P.line, CardShape)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                key = AllIconsKeys.Vcs.Branch,
                contentDescription = "Version control",
                // Grey, like every branch glyph on a project with nowhere to
                // push — and this one has nowhere to push from either.
                tint = P.dim,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(14.dp))
            PzText(
                "Use git to track how this project's requests change over time, and to " +
                    "collaborate with your team. BitTrace commits the project's own files and " +
                    "nothing else, and everything stays on this machine until you add a remote.",
                color = P.dim,
                style = Typo.label.copy(textAlign = TextAlign.Center),
                family = P.Ui,
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Initialise repository", onClick = onInit)
        }
    }
}

/**
 * The left column: which branch, what has changed, and the commit box.
 *
 * The branch sits at the top as the picker itself rather than as a label with a
 * control beside it — everything below is scoped to that branch, so the thing
 * naming it is also the thing that changes it.
 */
@Composable
private fun ChangesSidebar(
    state: GitState,
    changes: List<FileChange>,
    message: String,
    excluded: Set<Path>,
    onMessage: (String) -> Unit,
    onToggle: (Path, Boolean) -> Unit,
    actions: ProjectGitActions,
    onCommit: (List<Path>) -> Unit,
    onCommitAndPush: (List<Path>) -> Unit,
) {
    val picked = changes.map { it.path }.filterNot { it in excluded }
    val ready = message.isNotBlank() && picked.isNotEmpty()
    val canPush = state.hasRemote && !state.detached

    Column(Modifier.width(SIDEBAR).fillMaxHeight().rightBorder(P.line)) {
        // Above the branch, and only until there is a remote. A project with
        // nowhere to push has its branch glyph greyed and its Commit and Push
        // button dead, so without this the panel showed the problem in two
        // places and the fix in none — it lived on the tree's context menu,
        // which is not where you are looking when you are in here.
        if (!state.hasRemote) {
            PrimaryButton(
                "Publish to remote",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = HEADER_PAD),
                onClick = actions.publish,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = HEADER_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = AllIconsKeys.Vcs.Branch,
                contentDescription = "Branch",
                tint = branchTint(state),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Jewel's own dropdown rather than the app's `Dropdown` wrapper:
            // that one takes a list of strings, and this menu is a list of
            // branches *plus* a way to make one, which a list of strings cannot
            // say. A detached HEAD has no branch to be selected, so it stays a
            // readout until one is created.
            if (!state.detached && state.branches.isNotEmpty()) {
                JewelDropdown(
                    modifier = Modifier.weight(1f),
                    menuContent = {
                        state.branches.forEach { branch ->
                            selectableItem(
                                selected = branch == state.branch,
                                onClick = { actions.switchBranch(branch) },
                            ) {
                                PzText(branch, color = P.text, style = Typo.label, family = P.Ui)
                            }
                        }
                        // Below the rule because it is not one of the branches:
                        // everything above switches to something that exists,
                        // and this makes something that does not.
                        separator()
                        selectableItem(selected = false, onClick = actions.newBranch) {
                            PzText("New branch\u2026", color = P.text, style = Typo.label, family = P.Ui)
                        }
                    },
                ) {
                    PzText(
                        state.branch.orEmpty(),
                        color = P.accent, style = Typo.label, family = P.Ui,
                        softWrap = false, maxLines = 1,
                    )
                }
            } else {
                PzText(
                    state.label ?: "—",
                    color = P.dim, style = Typo.label, family = P.Ui,
                    softWrap = false, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            // Fetch and Pull sit here rather than only in the history header:
            // they are what you reach for while reading the changes beside
            // them, and both are one click with nothing to fill in. Everything
            // that opens a dialog stays in the menu.
            Spacer(Modifier.width(6.dp))
            GitIconButton(
                icon = AllIconsKeys.Vcs.Fetch,
                label = "Fetch",
                enabled = state.hasRemote,
                onClick = actions.fetch,
            )
            GitIconButton(
                icon = AllIconsKeys.Actions.CheckOut,
                label = "Pull",
                enabled = state.hasRemote,
                onClick = actions.pull,
            )
            // Push is here and not only inside Commit and Push: a branch that
            // is ahead with nothing left to commit still has to get out, and
            // the split button cannot run without a message.
            GitIconButton(
                icon = AllIconsKeys.Vcs.Push,
                label = "Push",
                enabled = state.hasRemote && !state.detached,
                onClick = actions.push,
            )
        }

        // Where this branch stands against its upstream. It used to be two rows
        // of the status readout this panel replaced, and dropping it would have
        // left nothing on screen saying a push was owed.
        PzText(
            if (state.upstream == null) "No upstream" else "${state.upstream} · ${aheadBehind(state)}",
            color = P.faint, style = Typo.caption, family = P.Ui,
            softWrap = false, maxLines = 1,
            modifier = Modifier.padding(start = 34.dp, end = 10.dp, bottom = HEADER_PAD),
        )
        state.error?.let {
            PzText(
                it,
                color = P.err, style = Typo.caption, family = P.Ui,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = HEADER_PAD),
            )
        }

        PaneHeader(title = "Commit", topRule = true)
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Multi-line: a commit message is a subject and then, often, the
            // paragraph explaining why — and a single line makes the second
            // half of that invisible as you type it.
            TextArea(
                value = message,
                onValueChange = onMessage,
                placeholder = "Enter commit message...",
                modifier = Modifier.fillMaxWidth().height(MESSAGE_HEIGHT),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Plain commit keeps its own button rather than living only in
                // the split's menu: it is the one that always works, and a
                // project with no remote would otherwise have its only usable
                // action hidden behind a disabled control.
                PrimaryButton("Commit", enabled = ready) { onCommit(picked) }

                // Pushing needs somewhere to push and a branch to push from, so
                // the split goes dead without a remote or on a detached HEAD.
                // Its menu repeats Commit because a split button's menu is where
                // people look for "just the first half".
                DefaultSplitButton(
                    onClick = { onCommitAndPush(picked) },
                    enabled = ready && canPush,
                    modifier = Modifier.weight(1f),
                    menuContent = {
                        selectableItem(selected = false, onClick = { onCommit(picked) }) {
                            PzText("Commit", color = P.text, style = Typo.label, family = P.Ui)
                        }
                    },
                ) {
                    PzText(
                        "Commit and Push",
                        color = if (ready && canPush) P.bg else P.faint,
                        style = Typo.label, family = P.Ui, softWrap = false, maxLines = 1,
                    )
                }
            }
        }

        PaneHeader(title = "Changes", topRule = true) {
            Spacer(Modifier.weight(1f))
            PzText(changes.size.toString(), color = P.faint, style = Typo.caption, family = P.Mono)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (changes.isEmpty()) {
                EmptyState("Nothing has changed since the last commit.")
            }
            changes.forEach { change ->
                ChangeRow(
                    change = change,
                    checked = change.path !in excluded,
                    onCheckedChange = { on -> onToggle(change.path, on) },
                )
            }
        }
    }
}

/** A bare glyph button for the branch row, with the tooltip carrying its name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GitIconButton(icon: IconKey, label: String, enabled: Boolean, onClick: () -> Unit) {
    Tooltip(tooltip = { Text(label) }) {
        IconActionButton(
            key = icon,
            contentDescription = label,
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.size(16.dp),
        )
    }
    Spacer(Modifier.width(4.dp))
}

/** One changed file: whether it goes in the commit, its name, and what happened to it. */
@Composable
private fun ChangeRow(change: FileChange, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    CheckBoxRow(
        checked = checked,
        modifier = Modifier.fillMaxWidth().height(CHANGE_ROW).padding(horizontal = 10.dp),
        onCheckedChange = onCheckedChange,
    ) {
        Spacer(Modifier.width(2.dp))
        PzText(
            change.path.fileName?.toString().orEmpty(),
            color = P.text, style = Typo.label, family = P.Ui,
            softWrap = false, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        PzText(
            labelOf(change.kind),
            color = toneOf(change.kind), style = Typo.caption, family = P.Ui,
            softWrap = false,
        )
    }
}

/**
 * The right pane: every commit this branch can see.
 *
 * No action buttons on the header. Everything that row offered now has a place
 * of its own — commit and push on the sidebar's buttons, fetch and pull on its
 * glyphs, a new branch in the branch menu — and the rest stays on the tree's
 * context menu. A strip repeating all of it made the panel's own controls look
 * like one option among several.
 */
@Composable
private fun History(
    history: List<CommitEntry>,
    loading: Boolean,
    selected: CommitEntry?,
    files: List<FileChange>,
    canResync: Boolean,
    onSelect: (CommitEntry) -> Unit,
    actions: ProjectGitActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        PaneHeader(title = "History") {
            Spacer(Modifier.weight(1f))
            // The one button up here, because it is the only thing in the panel
            // that acts on the log as a whole rather than on a commit in it.
            GhostButton("Re-sync", enabled = canResync, onClick = actions.resync)
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                if (history.isEmpty()) {
                    EmptyState(if (loading) "Reading history..." else "No commits yet.", centred = true)
                }
                history.forEach { commit ->
                    CommitRow(
                        commit = commit,
                        selected = commit.id == selected?.id,
                        onClick = { onSelect(commit) },
                        onRestore = { actions.restoreTo(commit) },
                    )
                }
            }
            // Only once something is selected: an empty third column standing
            // there permanently would take a third of the pane to say nothing.
            selected?.let { commit -> CommitFiles(commit, files) }
        }
    }
}

/**
 * The files one commit touched, beside the log.
 *
 * A list of names and what happened to each, in the same words and colours the
 * changes sidebar uses for the working tree — the same question asked of a
 * commit instead of of right now.
 */
@Composable
private fun CommitFiles(commit: CommitEntry, files: List<FileChange>) {
    Column(Modifier.width(DETAIL).fillMaxHeight().leftBorder(P.line)) {
        PaneHeader(title = commit.short) {
            Spacer(Modifier.weight(1f))
            PzText(files.size.toString(), color = P.faint, style = Typo.caption, family = P.Mono)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PzText(
                commit.subject,
                color = P.text, style = Typo.label, family = P.Ui,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            if (files.isEmpty()) {
                EmptyState("No files in this commit.")
            }
            files.forEach { file ->
                Row(
                    Modifier.fillMaxWidth().height(CHANGE_ROW).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PzText(
                        file.path.fileName?.toString().orEmpty(),
                        color = P.text, style = Typo.label, family = P.Ui,
                        softWrap = false, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    PzText(
                        labelOf(file.kind),
                        color = toneOf(file.kind), style = Typo.caption, family = P.Ui,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** One commit: its id, its subject, and who wrote it when. */
@Composable
private fun CommitRow(
    commit: CommitEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onRestore: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        // A fixed height rather than one measured from the children: the
        // restore button only exists while the row is hovered, and it is taller
        // than the text beside it — so a wrap-content row grew by a pixel or
        // two as the pointer crossed it, nudging every row below. Same answer
        // the KV editor's rows already use for the same cause.
        Modifier.fillMaxWidth()
            .height(COMMIT_ROW)
            .background(if (selected) P.sel else Color.Transparent)
            .bottomBorder(P.line2)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PzText(
            commit.short,
            color = P.key, style = Typo.caption, family = P.Mono,
            softWrap = false, modifier = Modifier.width(66.dp),
        )
        PzText(
            commit.subject,
            color = P.text, style = Typo.label, family = P.Ui,
            softWrap = false, maxLines = 1, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        PzText(
            commit.author,
            color = P.dim, style = Typo.caption, family = P.Ui,
            softWrap = false, maxLines = 1, modifier = Modifier.width(AUTHOR_WIDTH),
        )
        PzText(
            dayClockOf(commit.whenAt.toEpochMilli()),
            color = P.faint, style = Typo.caption, family = P.Mono,
            softWrap = false,
        )
        // Revealed on hover or while the row is selected, like the tree's
        // overflow button: a restore button on every row of a long log reads as
        // a column of buttons rather than as a history.
        Spacer(Modifier.width(8.dp))
        // Sized in both directions, so the slot is the same whether or not the
        // button is in it.
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (hovered || selected) {
                GitIconButton(
                    icon = AllIconsKeys.Actions.Rollback,
                    label = "Restore the project to this commit",
                    enabled = true,
                    onClick = onRestore,
                )
            }
        }
    }
}

//endregion

//region Readouts ───────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One Overview row: a tinted glyph, its label, and its value.
 *
 * The tile is the icon's own tint at low alpha, the way `P.accentFill` is built
 * from `P.accent` — so every tile follows the active palette into a light theme
 * instead of carrying five hardcoded pastels that only work in the dark one.
 */
@Composable
private fun InfoRow(
    key: IconKey,
    tint: Color,
    label: String,
    value: String,
    tone: Color = P.text,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(TILE).background(tint.copy(alpha = 0.16f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(key = key, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            PzText(label, color = P.text, style = Typo.label, family = P.Ui, weight = FontWeight.SemiBold)
            PzText(value, color = tone, style = Typo.caption, family = P.Ui)
        }
    }
}

private val HEADER_PAD = 6.dp

/** The changes column. Wide enough for a file name and its state, no wider. */
private val SIDEBAR = 260.dp

/** Room for a subject line and a short body before the box has to scroll. */
private val MESSAGE_HEIGHT = 72.dp

/** One changed file. Matches the commit dialog's rows, which list the same thing. */
private val CHANGE_ROW = 24.dp

/** Far enough down to clear the branch row the panel shows once there is one. */
private val CARD_TOP = 48.dp

/** Wide enough for the paragraph to break into three or four readable lines. */
private val CARD_WIDTH = 380.dp

private val CardShape = RoundedCornerShape(8.dp)

/**
 * One commit in the log.
 *
 * Tall enough for the restore button, which is the tallest thing a row ever
 * holds, so the row does not change size when it appears.
 */
private val COMMIT_ROW = 32.dp

/** The selected commit's file list. Names, not paths, so it needs less than the sidebar. */
private val DETAIL = 240.dp

/** Enough for a name; a long one truncates rather than crowding the subject. */
private val AUTHOR_WIDTH = 120.dp

/**
 * How far back the panel reads.
 *
 * The same 50 `GitService.log` defaults to. A project's collection history is
 * not a kernel tree, and a walk that has to be scrolled past to reach the
 * commit box is worse than one that stops.
 */
private const val HISTORY_LIMIT = 50

/**
 * What a branch glyph is worth saying, in colour.
 *
 * Green once the project has somewhere to push, grey while it is local-only —
 * the distinction that decides whether half the git panel's controls can do
 * anything, and the one thing worth reading off a 16dp glyph at a glance.
 *
 * One rule, used by the tree's row mark, the panel's branch row and the
 * overview alike, so the same repository is never two different colours in two
 * places at once.
 */
fun branchTint(git: GitState): Color = if (git.hasRemote) P.ok else P.dim

/** A section's inset — the same figure the app's other pane content sits at. */
private val INSET = PaddingValues(horizontal = 14.dp, vertical = 12.dp)

private val TILE = 30.dp

/** The folder is gone from the tree, so its contents cannot be counted. */
private const val UNKNOWN = "no longer on disk"

/** "1 request", "3 requests" — the plural the readouts all want. */
private fun count(n: Int, noun: String): String = "$n $noun${if (n == 1) "" else "s"}"

private fun workingTree(git: GitState): String = when {
    git.conflicts > 0 -> count(git.conflicts, "conflict")
    git.dirty > 0 -> count(git.dirty, "change")
    else -> "clean"
}

/** "—" rather than "-1", which is how [GitState] spells "no upstream". */
private fun aheadBehind(git: GitState): String =
    if (git.ahead < 0 || git.behind < 0) "—" else "${git.ahead} ahead, ${git.behind} behind"

/** Requests anywhere beneath these nodes, at any nesting depth. */
private fun countRequests(nodes: List<Node>): Int = nodes.sumOf { node ->
    when (node) {
        is RequestNode -> 1
        is CollectionNode -> countRequests(node.children)
        else -> 0
    }
}

//endregion
