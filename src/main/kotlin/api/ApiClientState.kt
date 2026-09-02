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
class RequestTab(val id: Long, request: ApiRequest, path: java.nio.file.Path?) {
    var request by mutableStateOf(request)
    var openPath by mutableStateOf(path)
    var dirty by mutableStateOf(false)

    /** The flow id whose response is on show — captured or synthesized alike. */
    var resultId by mutableStateOf<String?>(null)

    var inFlight by mutableStateOf<Job?>(null)
    var status by mutableStateOf<String?>(null)

    val busy: Boolean get() = inFlight != null

    /** What the tab strip shows; falls back to the method when unnamed. */
    val title: String get() = request.name.ifBlank { request.method }
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

    /** The authorisation in flight, so it can be stopped. Snapshot-backed for the button. */
    private var authJob by mutableStateOf<Job?>(null)

    private val scope = CoroutineScope(SupervisorJob() + EdtDispatcher)
    private val sender = ApiSender()

    private var nextTabId = 1L

    /** Open requests, in tab order. Never empty: closing the last opens a blank one. */
    val tabs = mutableStateListOf(RequestTab(nextTabId++, ApiRequest(), null))

    var activeId by mutableStateOf(tabs.first().id)
        private set

    /** The tab being edited. Null only in the instant between mutations. */
    val active: RequestTab? get() = tabs.firstOrNull { it.id == activeId }

    // Convenience accessors, so the view reads the active tab without reaching
    // through it on every line.
    val request: ApiRequest get() = active?.request ?: ApiRequest()
    val openPath: java.nio.file.Path? get() = active?.openPath
    val dirty: Boolean get() = active?.dirty == true
    val resultId: String? get() = active?.resultId
    val status: String? get() = active?.status

    /** Session the synthesized fallback rows are filed under. */
    private var sessionId: Int? = null
    private var sessionGeneration = -1

    val busy: Boolean get() = active?.busy == true

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
    fun authorize(oauth: OAuthService, auth: ApiAuth, onProgress: (Boolean, String?) -> Unit) {
        // One at a time: a second authorisation would try to bind the same
        // loopback port and fail on the socket rather than on anything the user
        // could act on.
        cancelAuthorization()
        val job = scope.launch {
            oauth.authorize(auth) { message -> onProgress(true, message) }
                .onSuccess { onProgress(false, "Token obtained.") }
                .onFailure { onProgress(false, it.message ?: "Authorisation failed.") }
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
     * Whether an authorisation is running — a browser open, a loopback socket
     * bound, or a device code being polled.
     */
    val authorizing: Boolean get() = authJob?.isActive == true

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
        scope.launch {
            oauth.refresh(auth)
                .onSuccess { onProgress(false, "Token refreshed.") }
                .onFailure { onProgress(false, it.message ?: "Refresh failed.") }
        }
    }

    fun edit(transform: (ApiRequest) -> ApiRequest) {
        val tab = active ?: return
        tab.request = transform(tab.request)
        tab.dirty = true
    }

    /** Records that the in-memory request now matches what is on disk. */
    fun markSaved(path: java.nio.file.Path) {
        val tab = active ?: return
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
    fun open(request: ApiRequest, path: java.nio.file.Path?): RequestTab {
        val existing = path?.let { wanted -> tabs.firstOrNull { it.openPath == wanted } }
        if (existing != null) {
            existing.request = request
            existing.dirty = false
            activeId = existing.id
            return existing
        }
        val tab = RequestTab(nextTabId++, request, path)
        // A pristine, unused blank tab is replaced rather than accumulated.
        val blank = active?.takeIf { it.openPath == null && !it.dirty && it.request == ApiRequest() }
        if (blank != null) tabs[tabs.indexOf(blank)] = tab else tabs.add(tab)
        activeId = tab.id
        return tab
    }

    fun focus(tab: RequestTab) {
        activeId = tab.id
    }

    /** Closes a tab, cancelling anything it had in flight. */
    fun close(tab: RequestTab) {
        tab.inFlight?.cancel()
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add(RequestTab(nextTabId++, ApiRequest(), null))
        if (activeId == tab.id) activeId = tabs[index.coerceAtMost(tabs.lastIndex)].id
    }

    fun cancel() {
        val tab = active ?: return
        tab.inFlight?.cancel()
        tab.inFlight = null
        tab.status = "cancelled"
    }

    /** Sends the current request and binds the response to the captured flow. */
    fun send() {
        val tab = active ?: return
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
            val outcome = withContext(Dispatchers.IO) {
                sender.execute(outgoing, settings, marker.takeIf { viaProxy }, viaProxy, port, tokens)
            }

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
            delay(CORRELATION_POLL_MS)
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
            sessionId = store.beginSession("API client").also { store.endSession(it) }
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
