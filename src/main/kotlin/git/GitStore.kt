package org.bittrace.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bittrace.api.EdtDispatcher

/**
 * What each project's repository looks like, as Compose state.
 *
 * There is no file watcher anywhere in this app, so nothing discovers a change
 * on its own — this cache is refreshed explicitly, from four places:
 *
 *  1. after any `CollectionStore` mutation, through its `onChanged` hook;
 *  2. after any git operation, whether it succeeded or not, since a *failed*
 *     push still moves ahead/behind;
 *  3. when the API view is opened;
 *  4. when the window regains focus, which is what catches "I ran `git pull` in
 *     a terminal and alt-tabbed back" — the only external mutation that
 *     actually happens, and otherwise reported as a bug.
 *
 * Reads are coalesced because a `status()` is a full working-tree walk: a burst
 * of saves must not buy one walk each.
 */
class GitStore(
    val service: GitService,
    /**
     * Where failures go besides the notice strip.
     *
     * The strip is one line that the next operation overwrites, so a push that
     * was refused an hour ago left no trace anywhere — and a git failure is
     * exactly the kind of thing you go looking for after the fact. Level first,
     * then the message, matching every other log sink in the app.
     */
    private val onLog: (String, String) -> Unit = { _, _ -> },
) {

    private val scope = CoroutineScope(SupervisorJob() + EdtDispatcher)
    private val states = mutableStateMapOf<Path, GitState>()
    private val running = mutableMapOf<Path, Job>()

    /** The project a long operation is running on, for the row's spinner. */
    var busy by mutableStateOf<Path?>(null)
        private set

    /** What that operation is, for the status bar. Null whenever [busy] is. */
    var busyLabel by mutableStateOf<String?>(null)
        private set

    /** The last thing that happened, for the notice strip. */
    var notice by mutableStateOf<String?>(null)

    /**
     * The cached state for [project], scheduling a read when it is missing or stale.
     *
     * Returns [GitState.Unknown] rather than null so a row can render on the
     * first frame and fill in a beat later, instead of the branch chip appearing
     * from nowhere once the walk finishes.
     */
    fun stateOf(project: Path): GitState {
        val cached = states[project]
        if (cached == null || System.currentTimeMillis() - cached.readAt > STALE_MS) refresh(project)
        return cached ?: GitState.Unknown
    }

    fun refresh(project: Path, force: Boolean = false) {
        if (running[project]?.isActive == true) return
        val cached = states[project]
        if (!force && cached != null && System.currentTimeMillis() - cached.readAt < COALESCE_MS) return
        running[project] = scope.launch {
            states[project] = if (service.isRepo(project)) {
                service.state(project).getOrElse { failed(it) }
            } else {
                GitState(repo = false, readAt = System.currentTimeMillis())
            }
        }
    }

    fun refreshAll(projects: List<Path>) = projects.forEach { refresh(it, force = true) }

    /** Marks [project] stale so the next read re-walks it. */
    fun invalidate(project: Path) {
        states.remove(project)
        refresh(project, force = true)
    }

    /**
     * Turns every project that is not yet a repository into one.
     *
     * Fire-and-forget on purpose: creating a project must never fail because git
     * did, and a project whose init failed is still a perfectly usable folder of
     * requests with an error on its row.
     *
     * The caller is responsible for having reloaded first, which is what runs
     * the one-shot `adoptLegacyLayout` migration.
     * A repository created before those have run captures the pre-migration
     * shape in its first commit, and a first commit cannot be taken back.
     */
    fun adopt(projects: List<Path>) {
        projects.forEach { project ->
            scope.launch {
                if (!service.isRepo(project)) {
                    service.init(project).onFailure {
                        report("error", "Could not set up git for ${project.fileName}: ${it.message}")
                    }
                }
                refresh(project, force = true)
            }
        }
    }

    /**
     * Runs one operation on [project], reporting it and refreshing afterwards.
     *
     * The refresh happens whether or not the operation worked, because half of
     * what a user does after a failure is look at the row to see what state they
     * are actually in.
     */
    fun run(project: Path, label: String, op: suspend GitService.() -> Result<String>) {
        if (running[project]?.isActive == true) {
            report("warn", "${project.fileName} is busy — wait for the current operation.")
            return
        }
        busy = project
        busyLabel = "$label ${project.fileName}"
        running[project] = scope.launch {
            service.op()
                .onSuccess { report("info", it) }
                .onFailure { report("error", "$label failed: ${it.message}") }
            if (busy == project) {
                busy = null
                busyLabel = null
            }
            states[project] = service.state(project).getOrElse { failed(it) }
        }
    }

    private fun failed(error: Throwable): GitState {
        val message = error.message ?: "git failed"
        // The row shows this as a badge, which says *that* something is wrong
        // without ever saying what. The log is where the what goes.
        onLog("error", "reading repository state failed: $message")
        return GitState(repo = true, readAt = System.currentTimeMillis(), error = message)
    }

    /** Says it once on the strip and once in the log, so neither can be the only record. */
    private fun report(level: String, message: String) {
        notice = message
        onLog(level, message)
    }

    private companion object {
        /** Long enough that a burst of saves costs one walk, short enough to feel live. */
        const val COALESCE_MS = 500L

        /** When a cached read is old enough that merely looking at it re-reads. */
        const val STALE_MS = 5_000L
    }
}
