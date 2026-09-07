package org.bittrace.git

import org.bittrace.data.catching
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.FollowFilter
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Git for one API-client project, over JGit.
 *
 * Every function suspends and hops to IO itself rather than trusting callers to
 * remember. A fetch is seconds, not milliseconds, and the rest of this app has
 * a standing habit of calling blocking store methods straight off the UI thread
 * — making the dispatcher hop impossible to forget is cheaper than making it
 * conventional.
 *
 * Failures come back as [GitFailure] rather than JGit's own exceptions, so the
 * UI can branch on a type instead of matching a message.
 *
 * @param identity read per call, so changing it in Settings reaches the next commit.
 */
class GitService(
    private val identity: () -> GitIdentity,
    private val credentials: GitCredentials,
) : AutoCloseable {

    /**
     * One lock per project.
     *
     * Two operations at once produce `LockFailedException` and leave a stale
     * `.git/index.lock` behind, which the user then has to find and delete by
     * hand. That is the difference between "occasionally odd" and "the project
     * is stuck until somebody explains git to me".
     */
    private val locks = ConcurrentHashMap<Path, Mutex>()

    private fun lockFor(project: Path): Mutex = locks.computeIfAbsent(project.normalize()) { Mutex() }

    suspend fun isRepo(project: Path): Boolean = io {
        Files.isDirectory(project.resolve(Constants.DOT_GIT))
    }

    /**
     * Makes [project] a repository, with a first commit.
     *
     * The first commit is not optional. An empty repository has no HEAD, and
     * `branches`, `log`, `status` and ahead/behind each take a different code
     * path on an unborn branch — committing what is already there removes that
     * entire second set of states before anything can hit one.
     */
    suspend fun init(project: Path): Result<Unit> = guarded(project) {
        if (Files.isDirectory(project.resolve(Constants.DOT_GIT))) return@guarded
        Git.init().setDirectory(project.toFile()).setInitialBranch("main").call().use { git ->
            writeRepoConfig(git.repository)
            Files.writeString(project.resolve(".gitattributes"), ATTRIBUTES)
            Files.writeString(project.resolve(".gitignore"), IGNORE)
            git.add().addFilepattern(".").call()
            val who = identity().takeIf { it.usable }
            git.commit()
                .setMessage("Initial commit")
                .apply { who?.let { setAuthor(it.name, it.email); setCommitter(it.name, it.email) } }
                .setAllowEmpty(true)
                .call()
        }
    }

    suspend fun state(project: Path): Result<GitState> = runIn(project, lock = false) { git ->
        val repository = git.repository
        val head = repository.resolve(Constants.HEAD)
        val status = git.status().call()
        val branch = repository.branch
        val detached = head != null && repository.fullBranch?.startsWith(Constants.R_HEADS) != true
        val tracking = org.eclipse.jgit.lib.BranchTrackingStatus.of(repository, branch)
        GitState(
            repo = true,
            branch = if (detached) null else branch,
            head = head?.name,
            detached = detached,
            dirty = status.added.size + status.changed.size + status.removed.size +
                status.modified.size + status.missing.size + status.untracked.size,
            conflicts = status.conflicting.size,
            // Shortened: the tracking status reports the full ref, and
            // "refs/remotes/origin/main" is not what a status line should read.
            upstream = tracking?.remoteTrackingBranch?.let(Repository::shortenRefName),
            ahead = tracking?.aheadCount ?: -1,
            behind = tracking?.behindCount ?: -1,
            hasRemote = repository.config.getSubsections("remote").isNotEmpty(),
            branches = branchNames(git),
            readAt = System.currentTimeMillis(),
        )
    }

    suspend fun status(project: Path): Result<GitStatus> = runIn(project, lock = false) { git ->
        val status = git.status().call()
        val changes = buildList {
            status.added.forEach { add(FileChange(project.resolve(it), ChangeKind.ADDED)) }
            status.changed.forEach { add(FileChange(project.resolve(it), ChangeKind.MODIFIED)) }
            status.modified.forEach { add(FileChange(project.resolve(it), ChangeKind.MODIFIED)) }
            status.removed.forEach { add(FileChange(project.resolve(it), ChangeKind.REMOVED)) }
            status.missing.forEach { add(FileChange(project.resolve(it), ChangeKind.REMOVED)) }
            status.untracked.forEach { add(FileChange(project.resolve(it), ChangeKind.UNTRACKED)) }
            status.conflicting.forEach { add(FileChange(project.resolve(it), ChangeKind.CONFLICT)) }
        }
        // The same file can be both staged and unstaged; the dialog wants one row.
        GitStatus(changes.distinctBy { it.path })
    }

    /**
     * Switchable branch names: locals, then remote-tracking ones with no local
     * counterpart.
     *
     * The remotes matter — without them there is no way to start working on a
     * colleague's branch, since checking one out is what creates the local
     * branch that follows it. They keep their `origin/` prefix so the list says
     * which are which, and `checkout` already reads that prefix as "make a local
     * branch tracking this".
     */
    private fun branchNames(git: Git): List<String> {
        val refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
            .map { Repository.shortenRefName(it.name) to it.name.startsWith(Constants.R_REMOTES) }
        val local = refs.filterNot { it.second }.map { it.first }
        val remote = refs.filter { it.second }
            .map { it.first }
            .filterNot { it.endsWith("/HEAD") || it.substringAfter('/') in local }
        return local.sorted() + remote.sorted()
    }

    suspend fun branches(project: Path): Result<List<BranchRef>> = runIn(project, lock = false) { git ->
        val current = git.repository.branch
        git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call().map { ref ->
            val remote = ref.name.startsWith(Constants.R_REMOTES)
            val short = Repository.shortenRefName(ref.name)
            BranchRef(short, ref.name, remote, !remote && short == current)
        }
    }

    suspend fun createBranch(project: Path, name: String, checkout: Boolean = true): Result<BranchRef> =
        runIn(project) { git ->
            val created = git.branchCreate().setName(name).call()
            if (checkout) git.checkout().setName(name).call()
            BranchRef(Repository.shortenRefName(created.name), created.name, false, checkout)
        }

    suspend fun checkout(project: Path, name: String): Result<CheckoutReport> =
        runIn(project) { git ->
            // A remote-tracking name checks out as a new local branch following
            // it, which is what clicking `origin/feature` in a list means.
            val local = name.substringAfter("origin/", name)
            val known = git.repository.findRef(Constants.R_HEADS + local) != null
            git.checkout()
                .setName(local)
                .setCreateBranch(!known && name != local)
                .apply { if (!known && name != local) setStartPoint(name) }
                .call()
            CheckoutReport(local)
        }

    /**
     * Commits exactly [paths] and nothing else.
     *
     * `setOnly` per path rather than a plain commit of the index: somebody may
     * have staged something in a terminal, and a tick list that quietly carried
     * it along would be a tick list that lies.
     */
    suspend fun commit(project: Path, paths: List<Path>, message: String): Result<CommitEntry> =
        runIn(project) { git ->
            val who = identity().takeIf { it.usable } ?: throw GitFailure.NoIdentity()
            if (paths.isEmpty()) throw GitFailure.Broken("Nothing selected to commit.")
            val relative = paths.map { relativeIn(project, it) }
            relative.forEach { rel ->
                if (Files.exists(project.resolve(rel))) {
                    git.add().addFilepattern(rel).call()
                } else {
                    git.rm().setCached(true).addFilepattern(rel).call()
                }
            }
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(who.name, who.email)
                .setCommitter(who.name, who.email)
                .apply { relative.forEach { setOnly(it) } }
                .call()
            commit.entry()
        }

    suspend fun fetch(project: Path): Result<FetchReport> = runIn(project) { git ->
        val remote = requireRemote(git)
        val result = git.fetch()
            .setRemote(remote.name)
            .setTransportConfigCallback(credentials.callbackFor(remote.url))
            .call()
        FetchReport(result.trackingRefUpdates.map { it.localName })
    }

    /**
     * Fast-forward only. Never a merge.
     *
     * A merge that conflicts writes `<<<<<<<` markers into the request files,
     * and those files are YAML that `RequestYaml.decode` then refuses — so a
     * single bad pull would break every affected request at once and leave the
     * collection unopenable. BitTrace is not a merge tool; it refuses and says
     * where to go.
     */
    suspend fun pull(project: Path): Result<PullReport> = runIn(project) { git ->
        val remote = requireRemote(git)
        val branch = git.repository.branch
        org.eclipse.jgit.lib.BranchTrackingStatus.of(git.repository, branch)
            ?: throw GitFailure.NoUpstream(branch)
        val result = git.pull()
            .setRemote(remote.name)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setTransportConfigCallback(credentials.callbackFor(remote.url))
            .call()
        if (!result.isSuccessful) {
            val tracking = org.eclipse.jgit.lib.BranchTrackingStatus.of(git.repository, branch)
            throw GitFailure.Diverged(tracking?.aheadCount ?: 0, tracking?.behindCount ?: 0)
        }
        val merge = result.mergeResult
        PullReport(
            fastForwarded = merge?.mergeStatus?.isSuccessful == true,
            commits = result.fetchResult?.trackingRefUpdates?.size ?: 0,
        )
    }

    /**
     * Points [project] at [url] as `origin`.
     *
     * Only ever adds: changing where a project pushes is not something to do
     * from a menu item labelled "publish", and a project that already has a
     * remote is asking a different question than one that does not.
     */
    suspend fun addRemote(project: Path, url: String, name: String = "origin"): Result<RemoteRef> =
        runIn(project) { git ->
            if (git.remoteList().call().any { it.name == name }) {
                throw GitFailure.Broken("This project already has a remote called '$name'.")
            }
            git.remoteAdd().setName(name).setUri(URIish(url)).call()
            RemoteRef(name, url)
        }

    /** Drops a remote again — the undo half of [addRemote]. */
    suspend fun removeRemote(project: Path, name: String = "origin"): Result<Unit> =
        runIn(project) { git ->
            git.remoteRemove().setRemoteName(name).call()
        }

    /**
     * Pushes the current branch, optionally recording where it went.
     *
     * Two things JGit will not do on its own:
     *
     * A **rejected** push is not an exception. Every update comes back with a
     * status, and a non-fast-forward or a hook rejection is reported as an
     * ordinary result — so without checking them, "Pushed to origin" is printed
     * for a push the remote refused outright.
     *
     * **Upstream tracking is not set** by `PushCommand`; there is no
     * `setUpstream`. It is two config keys, and without them the branch reads as
     * untracked forever: ahead/behind stay unknown and Pull refuses with
     * "not tracking a remote branch" immediately after a successful publish.
     */
    suspend fun push(project: Path, setUpstream: Boolean = false): Result<PushReport> =
        runIn(project) { git ->
            val remote = requireRemote(git, needsWrite = true)
            val branch = git.repository.branch
            if (git.repository.fullBranch?.startsWith(Constants.R_HEADS) != true) {
                throw GitFailure.DetachedHead(git.repository.resolve(Constants.HEAD)?.name.orEmpty())
            }
            val results = git.push()
                .setRemote(remote.name)
                .setRefSpecs(RefSpec("${Constants.R_HEADS}$branch:${Constants.R_HEADS}$branch"))
                .setTransportConfigCallback(credentials.callbackFor(remote.url))
                .call()

            val updates = results.flatMap { it.remoteUpdates }
            updates.firstOrNull { it.status !in ACCEPTED }?.let { rejected ->
                throw GitFailure.Broken(
                    "The remote refused the push (${rejected.status})" +
                        rejected.message?.let { ": $it" }.orEmpty(),
                )
            }

            if (setUpstream) {
                val config = git.repository.config
                config.setString("branch", branch, "remote", remote.name)
                config.setString("branch", branch, "merge", "${Constants.R_HEADS}$branch")
                config.save()
            }
            PushReport(remote.name, updates.map { "${it.remoteName}: ${it.status}" })
        }

    /**
     * Commits touching [file], newest first.
     *
     * Uses `FollowFilter` so a rename keeps its history — renaming a request in
     * the tree is a plain `Files.move`, so without this the log would go blank
     * the moment somebody renamed anything.
     */
    suspend fun log(project: Path, file: Path? = null, limit: Int = 50): Result<List<CommitEntry>> =
        runIn(project, lock = false) { git ->
            val repository = git.repository
            val head = repository.resolve(Constants.HEAD) ?: return@runIn emptyList()
            RevWalk(repository).use { walk ->
                walk.markStart(walk.parseCommit(head))
                file?.let {
                    // `FollowFilter`, not `LogCommand.addPath`: the latter is a
                    // plain path filter and stops dead at a rename, and renaming
                    // a request in the tree is an ordinary `Files.move`. It takes
                    // exactly one path and is slower than a path filter, which is
                    // why it is only built when a file is actually named.
                    walk.treeFilter = FollowFilter.create(
                        relativeIn(project, it),
                        repository.config.get(DiffConfig.KEY),
                    )
                }
                walk.asSequence().take(limit).map { commit -> commit.entry() }.toList()
            }
        }

    /** [file]'s text at [commit], or null when it did not exist there. */
    suspend fun contentAt(project: Path, file: Path, commit: String): Result<String?> =
        runIn(project, lock = false) { git ->
            val repository = git.repository
            val id = repository.resolve(commit) ?: return@runIn null
            RevWalk(repository).use { walk ->
                val tree = walk.parseCommit(id).tree
                TreeWalk.forPath(repository, relativeIn(project, file), tree)?.use { found ->
                    val loader = repository.open(found.getObjectId(0))
                    if (loader.size > MAX_BLOB) "(too large to show: ${loader.size} bytes)"
                    else loader.bytes.decodeToString()
                }
            }
        }

    /** The commit before [commit] on its first parent, or null at the root. */
    suspend fun parentOf(project: Path, commit: String): Result<String?> = runIn(project, lock = false) { git ->
        val id = git.repository.resolve(commit) ?: return@runIn null
        RevWalk(git.repository).use { walk ->
            walk.parseCommit(id).takeIf { it.parentCount > 0 }?.getParent(0)?.name
        }
    }

    override fun close() {
        credentials.close()
        locks.clear()
    }

    // --- plumbing -------------------------------------------------------------

    /**
     * Opens [project], runs [block], closes it again. Never cached.
     *
     * An open `Repository` holds `.git/index` and the pack files, and on Windows
     * that makes the directory unmovable — which matters because renaming or
     * deleting a project is a `Files.move` of exactly this folder. A cache here
     * would turn "rename a project" into `AccessDeniedException` on the second
     * try, intermittently.
     *
     * Discovery is pinned rather than searched: `findGitDir()` walks upward and
     * could find a repository *above* the collections root, and
     * `readEnvironment()` would let a stray `GIT_DIR` hijack every call.
     */
    private inline fun <T> open(project: Path, block: (Git) -> T): T {
        val gitDir = project.resolve(Constants.DOT_GIT)
        if (!Files.isDirectory(gitDir)) throw GitFailure.NotARepo(project)
        val repository = FileRepositoryBuilder()
            .setGitDir(gitDir.toFile())
            .setWorkTree(project.toFile())
            .setMustExist(true)
            .build()
        return repository.use { Git(it).use(block) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * Opens [project], runs [block], and reports what happened.
     *
     * `catching` rather than `runCatching`, and that is not a stylistic choice:
     * `runCatching` swallows `CancellationException`, so cancelling a fetch or a
     * push would come back as a *failed* operation — the notice strip would show
     * an error for something the user asked to stop, and the coroutine would
     * stop unwinding. `OAuthService` hit this first and documented it; the git
     * layer had the same bug for the same reason.
     *
     * @param lock false only for a read, which cannot leave a lock file behind.
     */
    private suspend fun <T> runIn(project: Path, lock: Boolean = true, block: (Git) -> T): Result<T> = io {
        if (lock) {
            lockFor(project).withLock { catching { open(project, block) }.translated() }
        } else {
            catching { open(project, block) }.translated()
        }
    }

    /** As [runIn], for a block that must run before the repository exists. */
    private suspend fun guarded(project: Path, block: () -> Unit): Result<Unit> = io {
        lockFor(project).withLock { catching(block).translated() }
    }

    private fun <T> Result<T>.translated(): Result<T> = recoverCatching { throw translate(it) }

    /**
     * The remote to talk to; when [needsWrite], refusing early if nothing could
     * authenticate.
     *
     * Asking the far end first only buys a worse message: GitHub answers an
     * unauthenticated push with `git-receive-pack not permitted`, which names a
     * wire protocol rather than the setting to go and change.
     */
    private fun requireRemote(git: Git, needsWrite: Boolean = false): RemoteRef {
        val remote = git.remoteList().call().firstOrNull() ?: throw GitFailure.NoRemote()
        val ref = RemoteRef(remote.name, remote.urIs.firstOrNull()?.toString().orEmpty())
        // Writes only. Reading a public repository over HTTPS needs no token at
        // all, and refusing that would be inventing a requirement git does not
        // have.
        if (needsWrite && !credentials.canReach(ref.url)) {
            throw GitFailure.AuthRequired(
                host = GitCredentials.hostOf(ref.url).ifBlank { "This remote" },
                ssh = false,
                hasToken = false,
            )
        }
        return ref
    }

    private fun tokenPresent(): Boolean = credentials.hasToken()

    /**
     * `Collection/Request.yaml` — always with forward slashes.
     *
     * JGit filepatterns are POSIX paths whatever the platform, and on Windows
     * `relativize().toString()` yields backslashes. `addFilepattern` then matches
     * nothing at all and the commit succeeds with zero files in it, silently.
     */
    private fun relativeIn(project: Path, file: Path): String =
        project.normalize().relativize(file.normalize()).joinToString("/")

    private fun RevCommit.entry() = CommitEntry(
        id = name,
        short = name.take(SHORT_ID),
        message = fullMessage.trim(),
        author = authorIdent?.name.orEmpty(),
        whenAt = Instant.ofEpochSecond(commitTime.toLong()),
    )

    /**
     * Sets what a repository must not inherit from the user's global config.
     *
     * `core.autocrlf=true` is common on Windows and, with it, git checks out
     * CRLF while `CollectionStore` writes LF — so every request file reads as
     * modified, permanently, and the dirty count never reaches zero. Pinning it
     * here plus a `.gitattributes` covers both the file and anyone who clones it.
     */
    private fun writeRepoConfig(repository: Repository) {
        val config = repository.config
        config.setString("core", null, "autocrlf", "false")
        config.setBoolean("core", null, "safecrlf", false)
        config.save()
    }

    /**
     * Reads an auth refusal out of a transport message, or null if it is not one.
     *
     * The list is longer than it looks like it should be because every forge
     * words this differently, and the one that matters most does not use the
     * word "auth" at all: GitHub answers an unauthorised push with
     * `git-receive-pack not permitted`, which reads as a capability problem and
     * sends people looking at the wrong thing entirely.
     */
    private fun authFailureIn(text: String): GitFailure.AuthRequired? {
        // Rejected outright: the credential was not accepted.
        val rejected = listOf("not authorized", "authentication", "auth fail", "401", "invalid credentials")
            .any { text.contains(it, ignoreCase = true) }
        // Accepted, then refused. GitHub words this as `git-receive-pack not
        // permitted`, which mentions neither auth nor permission and is the
        // single most confusing message on this whole path.
        val refused = listOf("403", "forbidden", "not permitted", "permission denied", "access denied")
            .any { text.contains(it, ignoreCase = true) }
        if (!rejected && !refused) return null
        val ssh = text.contains("ssh", ignoreCase = true) || text.contains("git@")
        return GitFailure.AuthRequired(
            host = text.substringAfter("://", "").substringBefore('/').ifBlank { "The remote" },
            ssh = ssh,
            hasToken = ssh || tokenPresent(),
            recognised = refused && !rejected,
        )
    }

    /** JGit's exceptions, in this app's vocabulary. */
    private fun translate(error: Throwable): Throwable = when (error) {
        is GitFailure -> error
        is TransportException -> authFailureIn(error.message.orEmpty())
            ?: GitFailure.Broken(error.message.orEmpty().ifBlank { "The remote could not be reached." })

        is org.eclipse.jgit.errors.RepositoryNotFoundException -> GitFailure.Broken("This project's repository is missing or unreadable.")
        is org.eclipse.jgit.api.errors.CheckoutConflictException -> GitFailure.DirtyWorkingTree(error.conflictingPaths.orEmpty().map { Path.of(it) })
        is org.eclipse.jgit.errors.LockFailedException -> GitFailure.Broken(
            "Another git operation is holding this project. If nothing else is running, " +
                    "delete .git/index.lock inside the project folder.",
        )

        is org.eclipse.jgit.errors.CorruptObjectException -> GitFailure.Broken("This project's repository is damaged: ${error.message}")
        else -> GitFailure.Broken(error.message ?: error::class.simpleName ?: "git failed")
    }

    private companion object {
        /** Statuses that mean the remote took it. Everything else is a refusal. */
        val ACCEPTED = setOf(
            RemoteRefUpdate.Status.OK,
            RemoteRefUpdate.Status.UP_TO_DATE,
        )

        const val SHORT_ID = 7

        /** Big enough for any request, small enough not to page in a stray binary. */
        const val MAX_BLOB = 2L * 1024 * 1024

        /**
         * `CollectionStore` writes LF and normalises CRLF away before encoding,
         * so the repository is told to leave line endings alone. Without this a
         * clone on a machine with `core.autocrlf=true` shows every file dirty.
         */
        val ATTRIBUTES = """
            # Requests are written LF-only by BitTrace; leave them alone.
            * text eol=lf
        """.trimIndent() + "\n"

        /**
         * `*.tmp` is not incidental: `CollectionStore.save` writes
         * `<name>.yaml.tmp` and then moves it, so a status taken in that window
         * would list a file that is about to stop existing.
         */
        val IGNORE = """
            # The atomic-save temp files, which exist for microseconds.
            *.tmp

            # Deleted requests, kept at the collections root rather than here.
            .trash/
        """.trimIndent() + "\n"
    }
}
