package org.bittrace.git

import java.nio.file.Path
import java.time.Instant

/** Who a commit is attributed to. */
class GitIdentity(val name: String, val email: String) {
    val usable: Boolean get() = name.isNotBlank() && email.isNotBlank()
}

/**
 * Everything a project row and a git menu need, read in one pass.
 *
 * One object rather than a call per question: the tree asks for this on every
 * frame it draws, and a branch name, a dirty count and an ahead/behind pair are
 * three walks of the same repository if fetched separately.
 */
class GitState(
    val repo: Boolean = false,
    /** Null on a detached HEAD, which is why [detached] is separate from it. */
    val branch: String? = null,
    val head: String? = null,
    val detached: Boolean = false,
    val dirty: Int = 0,
    val conflicts: Int = 0,
    val upstream: String? = null,
    /** -1 when there is no upstream to be ahead or behind of. */
    val ahead: Int = -1,
    val behind: Int = -1,
    val hasRemote: Boolean = false,
    /**
     * Every branch that can be switched to, local first.
     *
     * Carried on the state rather than fetched when a picker opens, because the
     * picker is now a combo box on the row: its options have to exist before it
     * is clicked. Reading them costs one extra ref walk inside a repository this
     * call already has open, which is far cheaper than a second round trip.
     */
    val branches: List<String> = emptyList(),
    val readAt: Long = 0,
    val error: String? = null,
) {
    val clean: Boolean get() = dirty == 0 && conflicts == 0

    /** What the project row shows: a branch, a short head, or nothing. */
    val label: String?
        get() = when {
            !repo -> null
            error != null -> "!"
            detached -> "detached at ${head.orEmpty().take(7)}"
            else -> branch
        }

    companion object {
        /** Before the first read lands. Renders as a repo-less project, then fills in. */
        val Unknown = GitState()
    }
}

enum class ChangeKind { ADDED, MODIFIED, REMOVED, UNTRACKED, CONFLICT }

/** [path] is absolute, so the commit dialog can match it against the tree. */
class FileChange(val path: Path, val kind: ChangeKind)

class GitStatus(val changes: List<FileChange>) {
    val clean: Boolean get() = changes.isEmpty()
}

class BranchRef(val name: String, val full: String, val remote: Boolean, val current: Boolean)

class RemoteRef(val name: String, val url: String) {
    val ssh: Boolean get() = url.startsWith("git@") || url.startsWith("ssh://")
}

class CommitEntry(
    val id: String,
    val short: String,
    val message: String,
    val author: String,
    val whenAt: Instant,
) {
    /** The first line, which is all a list row has space for. */
    val subject: String get() = message.lineSequence().firstOrNull().orEmpty().ifBlank { "(no message)" }
}

class CheckoutReport(val branch: String)

class PullReport(val fastForwarded: Boolean, val commits: Int)

class FetchReport(val updated: List<String>)

class PushReport(val remote: String, val updates: List<String>)

/**
 * The ways git says no, as types.
 *
 * Typed rather than left as JGit's own exceptions so the UI can decide what to
 * offer — a missing upstream wants a "push and set upstream" button, a missing
 * identity wants a link to Settings, and neither is served by putting a Java
 * class name in a notice strip. JGit's messages also name raw relative paths and
 * assume a terminal, which is the wrong register for a dialog.
 */
sealed class GitFailure(message: String) : Exception(message) {
    class NotARepo(val project: Path) :
        GitFailure("${project.fileName} is not a git repository.")

    class NoRemote :
        GitFailure("This project has no remote. Add one before fetching or pushing.")

    class NoUpstream(val branch: String) :
        GitFailure("'$branch' is not tracking a remote branch yet.")

    class DetachedHead(val head: String) :
        GitFailure("HEAD is detached at $head. Create a branch here before pushing.")

    class DirtyWorkingTree(val paths: List<Path>) :
        GitFailure(
            "${paths.size} request${if (paths.size == 1) " has" else "s have"} " +
                "uncommitted changes. Commit or discard them first.",
        )

    class Diverged(val ahead: Int, val behind: Int) :
        GitFailure(
            "This branch and its remote have both moved on ($ahead ahead, $behind behind). " +
                "Resolve that in a git client — BitTrace will not merge.",
        )

    /**
     * @param recognised whether the far end accepted the credential at all.
     *   A 401 means it did not — the token is wrong, expired, or revoked. A 403
     *   means it did and refused anyway, which is a different problem with
     *   entirely different things to check. Forges deliberately answer 403 for a
     *   repository you cannot see, so "it is not there" and "you have no access
     *   to it" arrive as the same reply, and both belong in that message.
     */
    class AuthRequired(
        val host: String,
        val ssh: Boolean,
        val hasToken: Boolean = true,
        val recognised: Boolean = false,
    ) : GitFailure(
        when {
            ssh -> "$host refused the SSH key. Start ssh-agent and add your key."
            !hasToken -> "$host needs a token to push. Add one under Settings \u203A Git."
            !recognised ->
                "$host did not accept the token. It is wrong, expired, or has been revoked — " +
                    "check it under Settings \u203A Git."
            else ->
                "$host accepted the token but refused the push. Either the token has no write " +
                    "access to this repository, or the repository does not exist on that account " +
                    "— a repository you cannot see is refused the same way as one that is not there."
        },
    )

    class NoIdentity :
        GitFailure("Set a name and email under Settings › Git before committing.")

    class Broken(val detail: String) : GitFailure(detail)
}
