package org.bittrace.ui.layouts.forge.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bittrace.api.CollectionNode
import org.bittrace.api.CollectionStore
import org.bittrace.api.Node
import org.bittrace.api.ProjectNode
import org.bittrace.api.RequestNode
import org.bittrace.git.GitStore
import org.bittrace.ui.P
import org.bittrace.ui.bottomBorder
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The strip above the projects tree.
 *
 * Everything here is also in a row's context menu, and that is the point: a
 * menu you reach by hovering a row and finding a button is fine once you know
 * it exists, and invisible until then. These are the two or three things done
 * often enough to deserve being on screen.
 *
 * Every button acts on the current selection, so what New makes depends on
 * where you are — which is the same rule the context menu follows, since a
 * menu on a collection offers "New request" and one on a project offers "New
 * collection". Nothing selected means the only thing that can be made is a
 * project.
 */
@Composable
fun ProjectToolbar(
    selected: Node?,
    collections: CollectionStore,
    git: GitStore,
    prompts: GitPrompts,
    onNew: (Node?) -> Unit,
    onDelete: (Node) -> Unit,
) {
    // Git acts on the project the selection sits in — the same project whose
    // row carries the branch. With nothing selected there is no project to act
    // on, and the buttons say so by being disabled rather than by guessing.
    val project = selected?.path?.let { collections.projectOf(it) }
    val state = project?.let { git.stateOf(it) }
    val repo = state?.repo == true
    val busy = project != null && git.busy == project

    Row(
        Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(
            icon = AllIconsKeys.General.Add,
            label = newLabel(selected),
            onClick = {
                onNew(selected)
            }
        )
        IconBtn(
            icon = AllIconsKeys.General.Delete,
            label = selected?.let { "Delete ${it.name}" } ?: "Delete",
            onClick = {
                onNew(selected)
            }
        )

        Spacer(Modifier.width(4.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(P.line))
        Spacer(Modifier.width(10.dp))

        IconBtn(
            icon = AllIconsKeys.Vcs.CommitNode,
            label = "Commit…",
            enabled = repo && !busy,
            onClick = { project?.let { dir -> prompts.commit(dir) } },
        )
        IconBtn(
            icon = AllIconsKeys.Vcs.Fetch,
            label = "Fetch",
            enabled = repo && state.hasRemote && !busy,
            onClick = {
                project?.let {
                    git.run(
                        it,
                        "Fetch"
                    ) { fetch(it).map { report -> "Fetched ${report.updated.size} ref(s)." } }
                }
            },
        )
        IconBtn(
            icon = AllIconsKeys.Vcs.Push,
            label = if (state?.hasRemote == true) "Push" else "Publish…",
            enabled = repo && !busy && !state.detached,
            onClick = {
                if (project != null)
                if (state?.hasRemote == true) {
                        git.run(project, "Push") {
                            push(project, setUpstream = state.upstream == null).map { "Pushed to ${it.remote}." }
                        }
                    } else {
                        prompts.publish(project)
                    }
            },
        )
    }
}

/** What New would make from here, so the tooltip is not a guess. */
private fun newLabel(selected: Node?): String = when (selected) {
    is ProjectNode -> "New collection in ${selected.name}"
    is CollectionNode -> "New request in ${selected.name}"
    is RequestNode -> "New request beside ${selected.name}"
    else -> "New project"
}

@Composable
private fun IconBtn(
    icon: IconKey,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    @OptIn(ExperimentalFoundationApi::class)
    Tooltip(tooltip = { Text(label) }) {
        IconActionButton(
            key = icon,
            contentDescription = label,
            onClick = onClick,
            modifier = Modifier.size(16.dp),
            enabled = enabled
        )
    }
    Spacer(Modifier.width(6.dp))
}
