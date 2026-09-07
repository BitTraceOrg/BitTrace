package org.bittrace.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.EventQueue
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.HarContent
import org.bittrace.data.HarTimings
import org.bittrace.data.ImportedFlow
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.NameValuePair
import org.bittrace.api.oauth.OAuthService
import org.bittrace.api.oauth.OAuthTokens
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficStrings
import org.bittrace.proxy.BodySide
import org.bittrace.proxy.ProxyService
import kotlin.time.Duration.Companion.milliseconds

/**
 * The AWT event thread — which is also Compose Desktop's UI thread.
 *
 * Mirrors the hop [SessionStore] already does internally, so a coroutine can
 * read snapshot state the store mutates without racing it.
 */
object EdtDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (EventQueue.isDispatchThread()) block.run() else EventQueue.invokeLater(block)
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()
}

/**
 * One open request: its draft, where it came from, and its last response.
 *
 * Everything that differs between tabs lives here, so switching tabs cannot
 * leak a response or an in-flight send from one request into another.
 */
/**
 * Something open in the main pane.
 *
 * A sum rather than a request with a mode flag on it. The alternative — one
 * class carrying a placeholder `ApiRequest` for the non-request case — compiles
 * everywhere and is wrong at runtime in a dozen places, the worst of which is
 * `CollectionStore.save(openPath, request)`: with a placeholder that call is
 * writable, and it would put a six-line YAML file where a project folder was.
 * Split like this, the call cannot be written at all.
 */
sealed interface EditorTab {
    val id: Long
    val title: String
    var dirty: Boolean
}

/** A project's variables, open for editing. */
class VariablesTab(
    override val id: Long,
    val project: java.nio.file.Path,
    rows: List<KeyValue>,
) : EditorTab {
    var rows by mutableStateOf(rows)
    override var dirty by mutableStateOf(false)
    override val title: String get() = "Variables"
}

class RequestTab(
    override val id: Long,
    request: ApiRequest,
    path: java.nio.file.Path?,
) : EditorTab {
    var request by mutableStateOf(request)
    var openPath by mutableStateOf(path)
    override var dirty by mutableStateOf(false)

    /** The flow id whose response is on show — captured or synthesized alike. */
    var resultId by mutableStateOf<String?>(null)

    var inFlight by mutableStateOf<Job?>(null)
    var status by mutableStateOf<String?>(null)

    val busy: Boolean get() = inFlight != null

    /** What the tab strip shows; falls back to the method when unnamed. */
    override val title: String get() = request.name.ifBlank { request.method }
}

/** What a checkout or pull did to the open tabs. */
class ReconcileReport(val reloaded: Int, val orphaned: Int, val failed: Int) {
    /** The half-sentence that follows "Switched to main". */
    fun summary(): String = buildList {
        if (reloaded > 0) add("$reloaded reloaded")
        if (orphaned > 0) add("$orphaned no longer here")
        if (failed > 0) add("$failed could not be read")
    }.joinToString(", ")
}

/**
 * Everything the API client holds across navigations.
 *
 * Hoisted above the view on purpose: `when (nav)` swaps the whole subtree, so a
 * scope owned by the view would cancel an in-flight request the moment someone
 * switched to Traffic to watch the flow arrive.
 */
class ApiClientState(
    private val store: SessionStore,
    private val service: ProxyService,
    /** The port the sidecar listens on; read per send, since Settings can change it. */
    private val proxyPort: () -> Int,
    /**
     * The app's request defaults, read per send for the same reason the port is:
     * changing one in Settings should reach the next send, not the next restart.
     */
    private val defaults: () -> org.bittrace.data.Settings,
    /**
     * A project's `{{name}}` values, by the file the request was opened from.
     *
     * A lambda for the same reason the port and the defaults are: it is read per
     * send, so editing a variable reaches the next send rather than the next
     * restart. It also keeps this class free of `CollectionStore`, which is the
     * rule the rest of the file follows.
     */
    private val variablesFor: (java.nio.file.Path?) -> Map<String, String> = { emptyMap() },
    /** Records every send, so the history tab has something to show. */
    private val history: HistoryStore,
    private val onLog: (String, String) -> Unit = { _, _ -> },
) {
    /**
     * Tokens obtained this session, shared by every tab.
     *
     * On the state rather than on a tab because a token belongs to a client and
     * an endpoint, not to a request — two tabs against the same API are the same
     * authorisation. Memory-only: see [OAuthTokens].
     */
    val tokens = OAuthTokens()

    /**
     * Where an unsaved draft counts as living, for variables.
     *
     * A draft has no file, so it used to belong to no project and every
     * `{{name}}` in it resolved to nothing — silently, because that is what an
     * unknown name does. But a draft is not homeless: `save` already puts it in
     * whatever the Forge tree has selected, so that is the project it will
     * belong to, and resolving against a different one than it will be saved
     * into is the difference a user cannot see and cannot explain.
     *
     * The Forge keeps this in step with its selection. Null when nothing is
     * selected, which is the one case where a draft really has no project.
     */
    var draftHome by mutableStateOf<java.nio.file.Path?>(null)

    /** The path whose project supplies [tab]'s values: its own file, or the draft's home. */
    private fun homeOf(tab: RequestTab?): java.nio.file.Path? = tab?.openPath ?: draftHome

    /** The authorisation in flight, so it can be stopped. Snapshot-backed for the button. */
    private var authJob by mutableStateOf<Job?>(null)

    private val scope = CoroutineScope(SupervisorJob() + EdtDispatcher)
    private val sender = ApiSender()

    private var nextTabId = 1L

    /** Open requests, in tab order. Never empty: closing the last opens a blank one. */
    val tabs = mutableStateListOf<EditorTab>(RequestTab(nextTabId++, ApiRequest(), null))

    var activeId by mutableStateOf(tabs.first().id)
        private set

    /** The tab being edited. Null only in the instant between mutations. */
    val active: EditorTab? get() = tabs.firstOrNull { it.id == activeId }

    /**
     * The active tab, when it is a request.
     *
     * Everything below that speaks in requests goes through this, so opening the
     * variables table makes `send`, `edit` and `markSaved` no-ops rather than
     * operations on something that is not a request.
     */
    val activeRequest: RequestTab? get() = active as? RequestTab

    // Convenience accessors, so the view reads the active tab without reaching
    // through it on every line.
    val request: ApiRequest get() = activeRequest?.request ?: ApiRequest()
    val openPath: java.nio.file.Path? get() = activeRequest?.openPath
    val dirty: Boolean get() = active?.dirty == true
    val resultId: String? get() = activeRequest?.resultId
    val status: String? get() = activeRequest?.status

    /**
     * Open tabs holding unsaved work that a checkout of [project] would destroy.
     *
     * Both kinds count. A variables table is as much unsaved work as a request,
     * and it is written into the project directory git is about to rewrite — so
     * leaving it out would let the guard wave through the one case where the
     * file being overwritten is the one on screen.
     */
    fun dirtyTabsUnder(project: java.nio.file.Path): List<EditorTab> = tabs.filter { tab ->
        tab.dirty && when (tab) {
            is RequestTab -> tab.openPath?.startsWith(project) == true
            is VariablesTab -> tab.project == project
        }
    }

    /**
     * Brings open tabs back in line with what is now on disk.
     *
     * A checkout or a pull rewrites the working tree underneath every tab
     * pointing into it. Callers guarantee no tab under [project] is dirty
     * before this runs, so replacing a request here cannot destroy an edit.
     *
     * A tab whose file is absent on the new branch is deliberately **not**
     * closed. It keeps its content, loses its path and becomes an unsaved
     * draft, so the work is still there to save somewhere else. Closing tabs
     * out from under someone during a branch switch is the kind of thing people
     * do not forgive, and "your request vanished" is indistinguishable from a
     * bug even when it is correct.
     *
     * @param read supplied as a lambda so this stays free of `CollectionStore`.
     */
    fun reconcile(
        project: java.nio.file.Path,
        branch: String,
        read: (java.nio.file.Path) -> Result<ApiRequest>,
    ): ReconcileReport {
        var reloaded = 0
        var orphaned = 0
        var failed = 0
        // A clean variables table under this project is re-read too: git has
        // just replaced the file, and a pane still showing the old branch's
        // values would substitute them into the next send and write them back
        // over the new branch's on the next save.
        tabs.filterIsInstance<VariablesTab>()
            .filter { it.project == project && !it.dirty }
            .forEach { it.rows = ProjectVariables.read(it.project) }

        tabs.filterIsInstance<RequestTab>().forEach { tab ->
            val path = tab.openPath ?: return@forEach
            if (!path.startsWith(project)) return@forEach
            if (!java.nio.file.Files.exists(path)) {
                tab.openPath = null
                tab.dirty = true
                tab.status = "not on $branch"
                orphaned++
                return@forEach
            }
            read(path).fold(
                onSuccess = { fresh ->
                    // An unchanged file must not be written back: touching every
                    // tab's state would recompose the whole editor on a checkout
                    // that changed nothing.
                    if (fresh != tab.request) {
                        tab.request = fresh
                        tab.status = "reloaded from $branch"
                        reloaded++
                    }
                },
                onFailure = { error ->
                    // Leave the in-memory request alone: whatever is on disk is
                    // unreadable, and the copy in the tab is the better one.
                    tab.status = "could not reload: ${error.message}"
                    onLog("warn", "${tab.title} could not be reloaded on $branch: ${error.message}")
                    failed++
                },
            )
        }
        return ReconcileReport(reloaded, orphaned, failed)
    }

    /** Session the synthesized fallback rows are filed under. */
    private var sessionId: Int? = null
    private var sessionGeneration = -1

    val busy: Boolean get() = activeRequest?.busy == true

    /**
     * Runs an OAuth authorisation on the state's own scope.
     *
     * On *this* scope for the same reason a send is: the view's scope dies when
     * `when (nav)` swaps the subtree, so switching to Traffic to watch the token
     * exchange arrive would cancel the very authorisation that produces it. An
     * authorisation also outlives a tab switch — the browser is open, and the
     * user is in it.
     *
     * [onProgress] is called with (busy, message) and always ends with busy
     * false, so a caller cannot be left with a spinner and no outcome.
     */
    /**
     * The variables the active request would send with.
     *
     * Authorising is a second entry point into the network, and it has to agree
     * with the first: `OAuthTokens` keys a token on the grant, client id, token
     * URL, scope and audience. Store a token under `{{cid}}` and look it up
     * under the resolved value and the lookup misses every time — which shows up
     * not as an error but as a client that silently re-authorises on every send.
     */
    fun variables(): Map<String, String> = variablesFor(homeOf(activeRequest))

    /**
     * Names the request asked for and the project could not supply.
     *
     * A warning rather than a failure: an unknown name resolving to nothing is
     * the documented rule, and the send has already happened by the time this
     * runs. What was missing was any way to find out — the request simply went
     * out with a hole where the value should have been.
     */
    private fun reportMissing(vars: TrackedVariables) {
        if (vars.missing.isEmpty()) return
        val names = vars.missing.joinToString { "{{$it}}" }
        onLog("warn", "no variable named $names — sent as empty")
    }

    fun authorize(oauth: OAuthService, auth: ApiAuth, onProgress: (Boolean, String?) -> Unit) {
        // One at a time: a second authorisation would try to bind the same
        // loopback port and fail on the socket rather than on anything the user
        // could act on.
        cancelAuthorization()
        val vars = TrackedVariables(variables())
        val job = scope.launch {
            oauth.authorize(auth.resolved(vars).also { reportMissing(vars) }) { message ->
                onProgress(true, message)
            }
                .onSuccess { onProgress(false, "Token obtained.") }
                .onFailure {
                    val message = it.message ?: "Authorisation failed."
                    onProgress(false, message)
                    onLog("error", "authorisation failed: $message")
                }
        }
        authJob = job
        // On completion rather than in the body: a cancelled coroutine may never
        // reach the lines above it, and a button left spinning after Stop is
        // worse than no button.
        job.invokeOnCompletion { cause ->
            if (authJob === job) authJob = null
            if (cause is CancellationException) onProgress(false, "Stopped listening.")
        }
    }

    /**
     * Stops one.
     *
     * Cancelling the job is what closes the loopback socket: `awaitRedirect`
     * closes it from its own completion handler, which is the only thing that
     * unblocks a waiting `accept` and frees the port for the next attempt.
     */
    fun cancelAuthorization() {
        authJob?.cancel()
        authJob = null
    }

    /** As [authorize], for the refresh. */
    fun refreshToken(oauth: OAuthService, auth: ApiAuth, onProgress: (Boolean, String?) -> Unit) {
        val vars = TrackedVariables(variables())
        scope.launch {
            oauth.refresh(auth.resolved(vars).also { reportMissing(vars) })
                .onSuccess { onProgress(false, "Token refreshed.") }
                .onFailure {
                    val message = it.message ?: "Refresh failed."
                    onProgress(false, message)
                    onLog("error", "token refresh failed: $message")
                }
        }
    }

    fun edit(transform: (ApiRequest) -> ApiRequest) {
        val tab = activeRequest ?: return
        tab.request = transform(tab.request)
        tab.dirty = true
    }

    /** Records that the in-memory request now matches what is on disk. */
    fun markSaved(path: java.nio.file.Path) {
        val tab = activeRequest ?: return
        tab.openPath = path
        tab.dirty = false
    }

    /**
     * Opens [request] in its own tab.
     *
     * A file already open is focused rather than opened twice — two tabs over
     * one file would let each overwrite the other's edits on save. Anything
     * without a path (history, an import, a captured flow) always gets a new
     * tab, since there is nothing to collide with.
     */
    /**
     * Opens [project]'s variables, or focuses the tab already showing them.
     *
     * Reading from disk on open rather than holding a cache: the file is
     * ordinary project content that a checkout or a pull can replace, and the
     * tab is where you would notice it had.
     */
    fun openVariables(project: java.nio.file.Path): VariablesTab {
        val existing = tabs.filterIsInstance<VariablesTab>().firstOrNull { it.project == project }
        if (existing != null) {
            activeId = existing.id
            return existing
        }
        val tab = VariablesTab(nextTabId++, project, ProjectVariables.read(project))
        val blank = activeRequest?.takeIf {
            it.openPath == null && !it.dirty && it.request == ApiRequest()
        }
        if (blank != null) tabs[tabs.indexOf(blank)] = tab else tabs.add(tab)
        activeId = tab.id
        return tab
    }

    fun open(request: ApiRequest, path: java.nio.file.Path?): RequestTab {
        val existing = path?.let { wanted ->
            tabs.filterIsInstance<RequestTab>().firstOrNull { it.openPath == wanted }
        }
        if (existing != null) {
            existing.request = request
            existing.dirty = false
            activeId = existing.id
            return existing
        }
        val tab = RequestTab(nextTabId++, request, path)
        // A pristine, unused blank tab is replaced rather than accumulated.
        val blank = activeRequest?.takeIf {
            it.openPath == null && !it.dirty && it.request == ApiRequest()
        }
        if (blank != null) tabs[tabs.indexOf(blank)] = tab else tabs.add(tab)
        activeId = tab.id
        return tab
    }

    fun focus(tab: EditorTab) {
        activeId = tab.id
    }

    /** Closes a tab, cancelling anything it had in flight. */
    fun close(tab: EditorTab) {
        (tab as? RequestTab)?.inFlight?.cancel()
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add(RequestTab(nextTabId++, ApiRequest(), null))
        if (activeId == tab.id) activeId = tabs[index.coerceAtMost(tabs.lastIndex)].id
    }

    fun cancel() {
        val tab = activeRequest ?: return
        tab.inFlight?.cancel()
        tab.inFlight = null
        tab.status = "cancelled"
    }

    /** Sends the current request and binds the response to the captured flow. */
    fun send() {
        // Only a request can be sent. Guarding here rather than only on the
        // button, because the menu bar has a Send too and it is not disabled.
        val tab = activeRequest ?: return
        if (tab.busy) return
        val outgoing = tab.request
        val viaProxy = service.isRunning
        val port = proxyPort()
        val settings = outgoing.settings.resolve(defaults())
        val marker = UUID.randomUUID().toString()
        val watermark = store.size
        val generation = store.generation

        tab.status = if (viaProxy) "sending…" else "sending (proxy stopped)…"
        tab.resultId = null
        // Filed before the result is known: a request that fails is often the
        // one worth getting back to.
        history.record(outgoing)

        tab.inFlight = scope.launch {
            val vars = TrackedVariables(variablesFor(homeOf(tab)))
            val outcome = withContext(Dispatchers.IO) {
                sender.execute(
                outgoing, settings, marker.takeIf { viaProxy }, viaProxy, port, tokens,
                variables = vars,
            )
            }
            reportMissing(vars)

            // The sidecar emits its frames before the response reaches us, but
            // they land on the event thread as separate runnables — so wait for
            // the row rather than assuming it is already there.
            val captured = if (viaProxy) awaitCaptured(marker, watermark, generation) else null

            tab.resultId = captured?.id ?: synthesize(outcome)
            tab.status = when {
                outcome.failure != null -> "failed: ${outcome.failure}"
                // Hops are named when there were any: a 200 that took three
                // redirects to reach is not the same answer as a 200 that did
                // not, and the status line is where that shows.
                captured != null -> "${outcome.status}${hopsOf(outcome)} · ${outcome.elapsedMs.toLong()} ms"
                viaProxy -> "${outcome.status} · ${outcome.elapsedMs.toLong()} ms · not captured"
                else -> "${outcome.status} · ${outcome.elapsedMs.toLong()} ms · proxy stopped"
            }
            outcome.failure?.let { failure ->
                // The tab's status line says this too, but only until the next
                // send replaces it — and a request that failed is usually one you
                // come back to. `resolved` so the log names the URL that was
                // actually attempted, not the one with `{{host}}` still in it.
                onLog("error", "${outgoing.method} ${outgoing.resolved(variables()).url} failed: $failure")
            }
            if (viaProxy && captured == null && outcome.failure == null) {
                onLog("warn", "no captured flow matched this request; showing a local copy")
            }
            tab.inFlight = null
        }
    }

    /** ` · 2 hops` when redirects were followed, and nothing when they were not. */
    private fun hopsOf(outcome: SendOutcome): String = when (outcome.redirects) {
        0 -> ""
        1 -> " · 1 hop"
        else -> " · ${outcome.redirects} hops"
    }

    /**
     * Polls for the captured flow carrying [marker].
     *
     * Scans newest-first so a re-send of the same URL finds its own row, and
     * only back to the watermark taken before sending. The marker — not a
     * (method, url, time) guess — is what makes two rapid identical sends
     * unambiguous.
     */
    private suspend fun awaitCaptured(marker: String, watermark: Int, generation: Int): TrafficRowRef? {
        val deadline = System.nanoTime() + CORRELATION_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (store.generation != generation) return null
            val rows = store.rows
            var i = rows.lastIndex
            while (i >= 0 && i >= watermark - 1) {
                val row = rows.getOrNull(i)
                // A row exists before its headers do; keep polling until they land.
                val headers = row?.completeRequest?.request?.headers
                if (headers != null && headers.any { it.name.equals(MARKER_HEADER, true) && it.value == marker }) {
                    return TrafficRowRef(row.id)
                }
                i--
            }
            delay(CORRELATION_POLL_MS.milliseconds)
        }
        return null
    }

    /** Just the id — the row itself is resolved from the store at composition. */
    private class TrafficRowRef(val id: String)

    /**
     * Files the exchange as a flow of our own, for when the proxy is stopped or
     * the capture never arrived. Mirrors what `HarImporter` does for an
     * imported entry, so the Inspector cannot tell the difference.
     */
    private fun synthesize(outcome: SendOutcome): String {
        val id = "api-" + UUID.randomUUID()
        if (outcome.requestBody.isNotEmpty()) service.bodies.put(id, BodySide.REQUEST, outcome.requestBody)
        if (outcome.responseBody.isNotEmpty()) service.bodies.put(id, BodySide.RESPONSE, outcome.responseBody)

        val started = ISO.format(outcome.startedAt)
        val secure = outcome.url.startsWith("https", ignoreCase = true)

        val initialRequest = InitialRequestData(
            id = id,
            startedDateTime = started,
            request = InitialRequestData.RequestHead(
                method = TrafficStrings.intern(outcome.method),
                url = outcome.url,
                httpVersion = TrafficStrings.intern(outcome.httpVersion),
                headersSize = -1,
                // Must be real: the BODY tab short-circuits to "body: none" on 0.
                bodySize = outcome.requestBody.size.toLong(),
                queryString = queryOf(outcome.url),
            ),
            // Unknown without a handshake of our own; blank reads as cleartext.
            tls = if (secure) TrafficStrings.intern("TLS") else "",
        )

        val initialResponse = InitialResponseData(
            id = id,
            serverIPAddress = "",
            connection = "",
            error = outcome.failure != null,
            response = InitialResponseData.ResponseHead(
                status = outcome.status,
                statusText = "",
                httpVersion = TrafficStrings.intern(outcome.httpVersion),
                headersSize = -1,
                bodySize = outcome.responseBody.size.toLong(),
                redirectURL = "",
            ),
            // Only the round trip is ours to measure; the rest is not applicable.
            timings = HarTimings(-1.0, -1.0, -1.0, -1.0, outcome.elapsedMs, -1.0, -1.0),
            time = outcome.elapsedMs,
        )

        val completeRequest = CompleteRequestMessage(
            id = id,
            request = CompleteRequestMessage.RequestBody(
                headers = outcome.requestHeaders.map { TrafficStrings.pair(it.first, it.second) },
                cookies = emptyList(),
                postData = outcome.requestBody
                    .takeIf { it.isNotEmpty() }
                    ?.let { CompleteRequestMessage.PostData(TrafficStrings.intern(contentTypeOf(outcome))) },
            ),
        )

        val completeResponse = CompleteResponseMessage(
            id = id,
            response = CompleteResponseMessage.ResponseBody(
                headers = outcome.responseHeaders.map { TrafficStrings.pair(it.first, it.second) },
                cookies = emptyList(),
                content = HarContent(
                    size = outcome.responseBody.size.toLong(),
                    mimeType = TrafficStrings.intern(
                        outcome.responseHeaders.firstOrNull { it.first.equals("content-type", true) }?.second ?: "",
                    ),
                ),
            ),
            timings = CompleteResponseMessage.Timings(outcome.elapsedMs),
            time = outcome.elapsedMs,
        )

        store.importBatch(
            sessionId(),
            listOf(ImportedFlow(initialRequest, initialResponse, completeRequest, completeResponse)),
        )
        return id
    }

    /**
     * The session synthesized rows are filed under, opened once rather than per
     * send — `beginSession` defers live capture until its `endSession`, so one
     * per request would stall the grid and mint a banner every time.
     */
    private fun sessionId(): Int {
        if (sessionId == null || sessionGeneration != store.generation) {
            sessionId = store.beginSession("Request Forge").also { store.endSession(it) }
            sessionGeneration = store.generation
        }
        return sessionId ?: 0
    }

    private fun contentTypeOf(outcome: SendOutcome): String =
        outcome.requestHeaders.firstOrNull { it.first.equals("content-type", true) }?.second ?: ""

    private fun queryOf(url: String): List<NameValuePair> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return emptyList()
        return query.split('&').filter { it.isNotBlank() }.map {
            TrafficStrings.pair(it.substringBefore('='), if ('=' in it) it.substringAfter('=') else "")
        }
    }

    private companion object {
        /** Long enough for the frames to cross the EDT, short enough not to hang. */
        const val CORRELATION_TIMEOUT_MS = 2_000L
        const val CORRELATION_POLL_MS = 25L

        val ISO: DateTimeFormatter =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
    }
}
