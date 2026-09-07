package org.bittrace.ui.layouts.forge.components

import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.EmptyState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import org.bittrace.api.ApiClientState
import org.bittrace.api.CollectionStore
import org.bittrace.git.CommitEntry
import org.bittrace.git.GitStore
import org.bittrace.tools.DiffResult
import org.bittrace.tools.DiffView
import org.bittrace.tools.diffLines
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.Typo
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.rightBorder

/**
 * What has happened to this request's file.
 *
 * Reads git rather than any record the app keeps of its own, which is the point
 * of putting projects under version control: the history is the same one a
 * terminal shows, survives the app being closed, and travels with the folder.
 *
 * Only saved requests have one. A draft has no file, so there is nothing to
 * have a history *of* — that is an empty state, not an error.
 */
@Composable
fun RequestHistoryTab(state: ApiClientState, collections: CollectionStore, git: GitStore) {
    val path = state.openPath
    val project = path?.let { collections.projectOf(it) }

    if (path == null || project == null) {
        EmptyState("Save this request into a collection to start tracking its history.")
        return
    }
    if (!git.stateOf(project).repo) {
        EmptyState("${project.fileName} is not a git repository yet.")
        return
    }

    var commits by remember(path) { mutableStateOf<List<CommitEntry>?>(null) }
    var selected by remember(path) { mutableStateOf<String?>(null) }
    var diff by remember(path) { mutableStateOf<DiffResult?>(null) }
    var problem by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path, git.stateOf(project).head) {
        // Keyed on HEAD as well as the path, so committing from the menu is
        // reflected here without anybody having to reopen the tab.
        git.service.log(project, path, LIMIT)
            .onSuccess { commits = it; problem = null }
            .onFailure { commits = emptyList(); problem = it.message }
    }

    LaunchedEffect(selected) {
        val commit = selected ?: return@LaunchedEffect
        diff = null
        val after = git.service.contentAt(project, path, commit).getOrNull()
        val parent = git.service.parentOf(project, commit).getOrNull()
        val before = parent?.let { git.service.contentAt(project, path, it).getOrNull() }
        diff = diffLines(before.orEmpty().lines(), after.orEmpty().lines())
    }

    Row(Modifier.fillMaxSize().background(P.input)) {
        Column(Modifier.width(LIST_WIDTH).fillMaxHeight().rightBorder(P.line)) {
            Header("COMMITS", commits?.size?.toString())
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    when {
                        problem != null -> EmptyState(problem.orEmpty())
                        commits == null -> EmptyState("Reading history…")
                        commits.orEmpty().isEmpty() ->
                            EmptyState("This request has not been committed yet.")
                        else -> commits.orEmpty().forEach { commit ->
                            CommitRow(commit, commit.id == selected) { selected = commit.id }
                        }
                    }
                }
                VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            val commit = commits.orEmpty().firstOrNull { it.id == selected }
            Header(commit?.short?.uppercase() ?: "CHANGES", null)
            Box(Modifier.fillMaxSize()) {
                when {
                    selected == null -> EmptyState("Pick a commit to see what changed in it.")
                    diff == null -> EmptyState("Reading the diff…")
                    // The root commit has no parent, so everything in it is new
                    // — the diff renderer already shows that as an all-added
                    // column, which reads correctly without a special case.
                    else -> DiffView(diff!!)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, count: String?) = PaneHeader {
    PzText(title, color = P.faint, style = Typo.micro, family = P.Ui)
    count?.let {
        Spacer(Modifier.weight(1f))
        // In the UI family like every sibling count. It was mono here, which is
        // the sort of thing only a shared component makes visible.
        PzText(it, color = P.faint, style = Typo.micro, family = P.Ui)
    }
}

/**
 * One commit: subject on top, who and when beneath.
 *
 * Two lines rather than a table row, matching the send-history list beside it —
 * a commit subject is the one part worth full width, and squeezing an author, a
 * date and a hash onto the same line leaves none of them readable at this width.
 */
@Composable
private fun CommitRow(commit: CommitEntry, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) P.sel else P.input)
            .bottomBorder(P.line2)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        PzText(
            commit.subject,
            color = if (selected) P.text else P.dim,
            style = Typo.label, maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PzText(commit.short, color = P.accent, style = Typo.micro)
            Spacer(Modifier.width(6.dp))
            PzText(commit.author, color = P.faint, style = Typo.micro, maxLines = 1)
            Spacer(Modifier.weight(1f))
            PzText(agoOf(commit.whenAt), color = P.faint, style = Typo.micro)
        }
    }
}

/**
 * "3d ago" — relative, because that is the question being asked.
 *
 * A timestamp answers "when exactly", which nobody wants from a history list;
 * what you want is whether this was the change you made this morning.
 */
private fun agoOf(instant: Instant): String {
    val elapsed = Duration.between(instant, Instant.now())
    val minutes = elapsed.toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        elapsed.toHours() < 24 -> "${elapsed.toHours()}h ago"
        elapsed.toDays() < 30 -> "${elapsed.toDays()}d ago"
        else -> "${elapsed.toDays() / 30}mo ago"
    }
}

/** Wide enough for a subject line, narrow enough to leave the diff readable. */
private val LIST_WIDTH = 240.dp

/** One screenful and then some; a request's file rarely has more. */
private const val LIMIT = 100
