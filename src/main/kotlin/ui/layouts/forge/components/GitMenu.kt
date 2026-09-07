package org.bittrace.ui.layouts.forge.components

import java.nio.file.Path
import org.bittrace.api.Node
import org.bittrace.api.ProjectNode
import org.bittrace.git.GitStore

/** What a git menu item needs the view to do, since the tree cannot open dialogs. */
class GitPrompts(
    val newBranch: (Path) -> Unit,
    val commit: (Path) -> Unit,
    val publish: (Path) -> Unit,
    /** Runs [action] once no tab under the project has unsaved edits. */
    val guarded: (Path, String, () -> Unit) -> Unit,
)

/**
 * Git items for a project's context menu.
 *
 * A host function rather than a `CollectionActionPlugin`, for the same reason
 * Import and Export are: half of these open a dialog, and the plugin context
 * offers only `refresh` and `notify`. Widening that interface so the tree could
 * host arbitrary plugin UI would be a much bigger promise than this needs.
 *
 * Items that cannot work are disabled rather than absent — a Push that vanishes
 * when a project has no remote reads as a missing feature, where a greyed one
 * reads as a step you have not taken.
 */
fun gitItems(node: Node, git: GitStore, prompts: GitPrompts): List<TreeMenuItem> {
    if (node !is ProjectNode) return emptyList()
    val project = node.path
    val state = git.stateOf(project)
    if (!state.repo) return emptyList()

    return listOf(
        TreeMenuItem("New branch…", startsGroup = true) { prompts.newBranch(project) },
        TreeMenuItem("Commit…") { prompts.commit(project) },
        // Only one of these two is ever offered: a project with nowhere to push
        // needs a remote, and one that has a remote does not need to be asked
        // for another. Showing both, with one greyed, would make the menu longer
        // to say the same thing.
        if (!state.hasRemote) {
            TreeMenuItem("Publish…") { prompts.publish(project) }
        } else {
            TreeMenuItem("Push", enabled = !state.detached) {
                git.run(project, "Push") {
                    push(project, setUpstream = state.upstream == null).map { "Pushed to ${it.remote}." }
                }
            }
        },
        TreeMenuItem("Fetch", enabled = state.hasRemote) {
            git.run(project, "Fetch") {
                fetch(project).map { report ->
                    if (report.updated.isEmpty()) "Already up to date." else "Fetched ${report.updated.size} ref(s)."
                }
            }
        },
        // Pull and checkout rewrite the working tree, so both go through the
        // dirty-tab guard. Fetch does not — it only moves remote-tracking refs.
        TreeMenuItem("Pull", enabled = state.hasRemote) {
            prompts.guarded(project, "Pull") {
                git.run(project, "Pull") {
                    pull(project).map { report ->
                        if (report.commits == 0) "Already up to date." else "Pulled ${report.commits} commit(s)."
                    }
                }
            }
        },
    )
}

/** A git operation held back until the tabs it would overwrite are saved. */
class PendingGit(
    val project: Path,
    val action: String,
    val names: List<String>,
    val run: () -> Unit,
)
