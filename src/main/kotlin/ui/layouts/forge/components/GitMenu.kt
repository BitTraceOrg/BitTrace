package org.bittrace.ui.layouts.forge.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import org.bittrace.git.GitStore

/** What a git operation needs the view to do, since a runner cannot open dialogs. */
class GitPrompts(
    val newBranch: (Path) -> Unit,
    val commit: (Path) -> Unit,
    val publish: (Path) -> Unit,
    /** Runs [action] once no tab under the project has unsaved edits. */
    val guarded: (Path, String, () -> Unit) -> Unit,
)

/**
 * Fetch, as the project tab's git panel runs it.
 *
 * These runners are all that is left of what was once a context menu: git left
 * the tree entirely, and the panel beside the changes is the one place any of
 * it happens now. They stay here, apart from the composables, because they are
 * the operations themselves rather than the controls that start them.
 */
fun runFetch(project: Path, git: GitStore) {
    git.run(project, "Fetch") {
        fetch(project).map { report ->
            if (report.updated.isEmpty()) "Already up to date." else "Fetched ${report.updated.size} ref(s)."
        }
    }
}

/**
 * Pull, guarded.
 *
 * Pull and checkout rewrite the working tree, so both go through the dirty-tab
 * guard. Fetch does not — it only moves remote-tracking refs — which is why
 * [runFetch] takes no prompts.
 */
fun runPull(project: Path, git: GitStore, prompts: GitPrompts) {
    prompts.guarded(project, "Pull") {
        git.run(project, "Pull") {
            pull(project).map { report ->
                if (report.commits == 0) "Already up to date." else "Pulled ${report.commits} commit(s)."
            }
        }
    }
}

/**
 * Push, guarded by nothing.
 *
 * Unlike pull it only sends, so there is no working tree to overwrite and no
 * dirty-tab question to ask. [setUpstream] on the first push of a branch that
 * has no upstream yet, which is the state a freshly published project is in.
 */
fun runPush(project: Path, git: GitStore, setUpstream: Boolean) {
    git.run(project, "Push") {
        push(project, setUpstream = setUpstream).map { "Pushed to ${it.remote}." }
    }
}

/** A git operation held back until the tabs it would overwrite are saved. */
class PendingGit(
    val project: Path,
    val action: String,
    val names: List<String>,
    val run: () -> Unit,
)

/**
 * The Forge's git operations, reachable from outside it.
 *
 * The status bar draws the branch the Forge is on, and switching from there has
 * to be the *same* switch — guarded against unsaved tabs, then reconciled —
 * rather than a second, laxer copy. But the guard's dialog and the new-branch
 * dialog live inside `ApiView`, and the status bar is a sibling of it.
 *
 * So `ApiView` fills this in and whoever holds both reads it. The fields are
 * plain lambdas rather than snapshot state because nothing reads them during
 * composition: they are called from a click, long after `ApiView` has composed
 * and set them. Before that they do nothing, which is the right behaviour for a
 * branch menu belonging to a view that is not on screen.
 */
class ForgeGitCommands {
    var switchBranch: (Path, String) -> Unit = { _, _ -> }
    var newBranch: (Path) -> Unit = {}
}

/**
 * Re-sync: fetch, then replay this branch's commits on top of the upstream.
 *
 * Guarded, like pull and checkout, because a rebase rewrites the working tree
 * under whatever is open. A stop is reported rather than hidden: the repository
 * is then sitting in a rebase that BitTrace has no way to finish, and saying so
 * is the difference between a state you can get out of elsewhere and one you
 * did not know you were in.
 */
fun runResync(project: Path, git: GitStore, prompts: GitPrompts) {
    prompts.guarded(project, "Re-sync") {
        git.run(project, "Re-sync") {
            rebase(project).map { report ->
                when {
                    report.stopped ->
                        "Rebase stopped on ${report.conflicts.size} conflicted file(s). " +
                            "Finish or abort it with git; BitTrace cannot."
                    report.upToDate -> "Already up to date."
                    else -> "Replayed ${report.replayed} commit(s) onto the upstream."
                }
            }
        }
    }
}

/**
 * What the Forge has selected, for the menu bar composed beside it.
 *
 * The same gap `ForgeGitCommands` bridges, in the other direction: the tree's
 * selection lives in `ApiView`, and the app menu — built by whoever owns the
 * window — has to grey out the entries that need a collection to act on.
 *
 * Snapshot state, unlike the commands, because this *is* read during
 * composition: the menu has to re-enable the moment a collection is clicked,
 * not on the next click of something else.
 *
 * Cleared when the Forge leaves the composition, so entries do not stay live on
 * the strength of a selection that no longer exists.
 */
class ForgeSelection {
    var collection: java.nio.file.Path? by mutableStateOf(null)
}
