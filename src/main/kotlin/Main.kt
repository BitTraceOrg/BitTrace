package org.bittrace

import org.bittrace.ui.layouts.inspector.TrafficView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.concurrent.thread
import org.bittrace.api.ApiClientState
import org.bittrace.api.CollectionStore
import org.bittrace.api.ProjectVariables
import org.bittrace.api.HistoryStore
import org.bittrace.api.requestFromFlow
import org.bittrace.ui.components.AddressBar
import org.bittrace.ui.layouts.forge.ApiView
import org.bittrace.ui.layouts.inspector.components.matchingBodies
import org.bittrace.ui.layouts.inspector.components.FlowQuery
import org.bittrace.ui.layouts.inspector.components.matches
import org.bittrace.ui.layouts.inspector.components.originOf
import org.bittrace.ui.layouts.home.HomeView
import org.bittrace.ui.components.ImportRequestDialog
import org.bittrace.ui.components.NerdStats
import org.bittrace.tools.DiffTool
import org.bittrace.tools.Tool
import org.bittrace.tools.ToolWindow
import org.bittrace.tools.ToolWindows
import org.bittrace.ui.components.LogPanel
import org.bittrace.ui.components.Menu
import org.bittrace.ui.components.MenuAction
import org.bittrace.ui.components.MenuBar
import org.bittrace.ui.layouts.inspector.components.Outcome
import org.bittrace.ui.components.Rail
import org.bittrace.ui.layouts.settings.SettingsView
import org.bittrace.ui.components.StatusBar
import org.bittrace.data.ActivityStore
import org.bittrace.data.LogStore
import org.bittrace.data.SessionStore
import org.bittrace.git.GitCredentials
import org.bittrace.git.GitIdentity
import org.bittrace.git.GitService
import org.bittrace.git.GitStore
import org.bittrace.data.SettingsStore
import org.bittrace.data.horizontalLayout
import org.bittrace.plugin.PluginLoader
import org.bittrace.plugin.ThemeManager
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.collection.CollectionActionPlugin
import org.bittrace.plugin.flow.FlowActionPlugin
import org.bittrace.plugin.importer.RequestImporter
import org.bittrace.proxy.ProxyService
import org.bittrace.session.HarExporter
import org.bittrace.session.HarImporter
import org.bittrace.ui.BitTraceTheme
import org.bittrace.ui.FileDialogs
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.bottomBorder
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import kotlin.time.Duration.Companion.milliseconds

fun main() = application {
    val activity = remember { ActivityStore() }
    val store = remember { SessionStore(onLiveFlow = activity::record) }
    val settings = remember { SettingsStore() }
    val logs = remember { LogStore() }
    val service = remember {
        ProxyService(
            store = store,
            onLog = { e -> logs.add(e.level, e.source, e.message) },
            onExit = { code -> logs.add("info", "proxy", "sidecar exited ($code)") },
        )
    }
    // Discover plugins (bundled + external JARs) once; derive the theme manager
    // and body formatters from the registry. Load messages go to the same store
    // the proxy logs to, so a plugin that failed to start says so in the app
    // rather than only on stderr.
    val pluginLog: (String, String) -> Unit = { level, message -> logs.add(level, "plugin", message) }
    val registry = remember { PluginLoader.load(pluginLog) }
    val themeManager = remember { ThemeManager(registry, pluginLog) }
    val formatters = remember { registry.formatters }
    val importers = remember { registry.importers }
    val collectionActions = remember { registry.collectionActions }
    val flowActions = remember { registry.flowActions }

    // Start the sidecar off the UI thread; stop it when the app closes.
    DisposableEffect(Unit) {
        val port = settings.settings.proxyPort
        thread(isDaemon = true, name = "proxy-launcher") {
            runCatching { service.start(port) }
                .onSuccess { logs.add("info", "proxy", "listening on 127.0.0.1:$port") }
                .onFailure { logs.add("error", "proxy", "start failed: ${it.message}") }
        }
        onDispose { service.stop(); activity.flush() }
    }

    // The tally is written on a timer as well as on exit: a proxy gets killed
    // rather than closed often enough that "saved on shutdown" means "usually
    // lost". flush() is a no-op unless something changed, so this costs nothing
    // while idle.
    LaunchedEffect(activity) {
        while (true) {
            delay(30_000.milliseconds)
            activity.flush()
        }
    }

    val windowState = rememberWindowState()
    // Apply the persisted theme, and re-apply whenever it changes. Above the
    // window, because the window frame is themed too.
    LaunchedEffect(settings.settings.theme) { themeManager.applyId(settings.settings.theme) }

    // `dark` is no longer a hint: it picks the Int UI base the component styling
    // is derived from. Absent a resolved theme, assume dark — the startup default.
    BitTraceTheme(dark = themeManager.activeTheme?.dark != false) {
        // DecoratedWindow gives us the frame the app used to draw by hand:
        // dragging, minimise/maximise/close, edge resize and Windows snap
        // layouts. It requires the JetBrains Runtime — see build.gradle.kts.
        DecoratedWindow(
            onCloseRequest = { service.stop(); exitApplication() },
            title = "BitTrace",
            state = windowState,
        ) {
            App(
                store, service, settings, themeManager, logs, formatters, importers, collectionActions, flowActions, activity,
            )
        }
    }
}

@Composable
private fun DecoratedWindowScope.App(
    store: SessionStore,
    service: ProxyService,
    settings: SettingsStore,
    themeManager: ThemeManager,
    logs: LogStore,
    formatters: List<BodyFormatter>,
    importers: List<RequestImporter>,
    collectionActions: List<CollectionActionPlugin>,
    flowActions: List<FlowActionPlugin>,
    activity: ActivityStore,
) {
    var nav by remember { mutableStateOf("home") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var logsOpen by remember { mutableStateOf(false) }
    var logHeight by remember { mutableStateOf(260.dp) }
    val port = settings.settings.proxyPort
    // One import or export at a time; both menu entries disable while either runs.
    var sessionBusy by remember { mutableStateOf(false) }
    // Driven by the status bar's ok/failed counts; applied on top of the
    // per-column filters the table header owns.
    var outcome by remember { mutableStateOf(Outcome.ALL) }
    // The overview band's query, and whether the band is open on it. Hoisted
    // here because `when (nav)` swaps the traffic subtree away: state owned down
    // there would be discarded by a visit to Home and the query would silently
    // clear itself while its status-bar cell still claimed it was running.
    var query by remember { mutableStateOf(FlowQuery()) }
    var searching by remember { mutableStateOf(false) }
    // The body scan. It decodes every cached body in the table, so it runs off
    // the UI thread behind a debounce — typing a six-character word costs one
    // pass rather than six. `null` until the first pass lands, which the filter
    // reads as "not yet" rather than "no matches".
    var bodyHits by remember { mutableStateOf<Set<String>?>(null) }
    // Set across the debounce as well as the scan: from where the user is
    // standing, typing and waiting are one wait, and a bar that only appeared
    // for the scan would flicker on after a pause rather than acknowledging the
    // keystroke.
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(query.text, query.side, store.rows.size) {
        if (query.text.isBlank()) {
            bodyHits = null
            scanning = false
            return@LaunchedEffect
        }
        scanning = true
        try {
            delay(SEARCH_DEBOUNCE_MS.milliseconds)
            val snapshot = store.rows.toList()
            bodyHits = withContext(Dispatchers.Default) {
                matchingBodies(snapshot, query.text.trim(), query.side, service::body)
            }
        } finally {
            // A keystroke cancels this effect and starts another, so the flag
            // has to come off on cancellation too or it sticks on forever.
            scanning = false
        }
    }

    var importOpen by remember { mutableStateOf(false) }
    val collections = remember { CollectionStore() }
    // Git lives as long as the app does: the SSH factory owns a thread pool, so
    // one per view build would leak one per visit to the API client.
    val git = remember {
        GitStore(
            GitService(
                identity = { GitIdentity(settings.settings.gitAuthorName, settings.settings.gitAuthorEmail) },
                credentials = GitCredentials { settings.settings.gitToken },
            ),
            onLog = { level, message -> logs.add(level, "git", message) },
        )
    }
    // The store tells us it touched a file; we decide that means a repository
    // needs re-reading. Nothing in CollectionStore knows git exists.
    DisposableEffect(collections, git) {
        collections.onChanged = { path -> collections.projectOf(path)?.let(git::invalidate) }
        // The fourth refresh trigger, and the only one that catches a change
        // BitTrace did not make: somebody ran `git pull` in a terminal and
        // alt-tabbed back. Without it the branch chip keeps showing what was
        // true when the window lost focus, which reads as a bug rather than as
        // stale data.
        val onFocus = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(event: java.awt.event.WindowEvent?) {
                git.refreshAll(collections.tree.map { it.path })
            }
        }
        window.addWindowFocusListener(onFocus)
        onDispose {
            window.removeWindowFocusListener(onFocus)
            collections.onChanged = null
            git.service.close()
        }
    }
    val tools = remember { ToolWindows() }
    // Flows picked out for the diff tool. Two at most: marking a third drops the
    // oldest, so the pair is always the two you most recently asked for rather
    // than a set you have to clear before you can choose again.
    var markedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val history = remember { HistoryStore() }
    // Hoisted above the view: `when (nav)` swaps the subtree, and a scope owned
    // by the view would cancel an in-flight request the moment someone switched
    // to Traffic to watch the flow arrive.
    val api = remember {
        ApiClientState(
            store, service,
            proxyPort = { settings.settings.proxyPort },
            defaults = { settings.settings },
            // Resolved per send from the project the request was opened in — or,
            // for a draft, from the Forge selection it would be saved into, which
            // `ApiClientState.draftHome` supplies as the path.
            variablesFor = { path ->
                path?.let(collections::projectOf)?.let(ProjectVariables::lookupIn).orEmpty()
            },
            history = history,
            onLog = { level, message -> logs.add(level, "api", message) },
        )
    }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        // The title bar is Jewel's; what sits inside it is ours — the menus on
        // the left, the proxy endpoint centred. The window buttons on the right
        // are drawn by DecoratedWindow.
        //
        // The endpoint is the only child left unaligned, and that is what puts
        // it dead centre: Jewel centres the unaligned group on the *whole* bar
        // width, so anything else sharing that group would drag it off centre.
        TitleBar(Modifier.bottomBorder(P.line)) {
            MenuBar(
                modifier = Modifier.align(Alignment.Start),
                menus = appMenus(
                service = service,
                running = service.isRunning,
                port = port,
                logsOpen = logsOpen,
                horizontalLayout = settings.settings.horizontalLayout,
                onNav = { nav = it },
                onToggleLogs = { logsOpen = !logsOpen },
                onOpenTool = { tools.show(it) },
                onToggleLayout = {
                    settings.update {
                        it.copy(inspectorDock = if (it.horizontalLayout) "bottom" else "right")
                    }
                },
                onClearSelection = { selectedId = null },
                onNewSession = { service.clear() },
                onNewRequest = { api.open(org.bittrace.api.ApiRequest(), null); nav = "api" },
                onImportRequest = { importOpen = true },
                onNewProject = {
                    nav = "api"
                    collections.createNamedProject()
                        .onFailure { logs.add("warn", "api", it.message ?: "Could not create a project.") }
                },
                // No project named: the store puts it in the only one, or makes
                // a project for it. The menu bar does not know what the tree has
                // selected, and asking it to would be a worse trade than a
                // sensible default the user can drag or rename afterwards.
                onNewCollection = {
                    nav = "api"
                    collections.createNamedCollection()
                        .onFailure { logs.add("warn", "api", it.message ?: "Could not create a collection.") }
                },
                // Walking the folder touches the disk, so it stays off the EDT.
                onRefreshProjects = {
                    thread(isDaemon = true, name = "collections-reload") { collections.reload() }
                },
                onClearHistory = if (history.entries.isEmpty()) null else {
                    { history.clear() }
                },
                onSendRequest = { nav = "api"; api.send() },
                onEditFlow = selectedId?.let { id ->
                    {
                        store.get(id)?.let { row ->
                            api.open(requestFromFlow(row, service::body), null)
                            nav = "api"
                        }
                    }
                },
                apiBusy = api.busy,
                onImportSession = if (sessionBusy) null else {
                    {
                        // The dialog is modal on the event thread; the read that
                        // follows can take minutes, so it gets its own thread.
                        FileDialogs.openHar(window)?.let { path ->
                            sessionBusy = true
                            thread(isDaemon = true, name = "har-import") {
                                val name = path.fileName.toString().substringBeforeLast('.')
                                logs.add("info", "session", "importing $name…")
                                val result = HarImporter(
                                    store, service.bodies,
                                    onLog = { level, message -> logs.add(level, "session", message) },
                                ).import(path)
                                if (result.error != null) {
                                    logs.add("error", "session", "import failed: ${result.error}")
                                }
                                logs.add("info", "session", "imported ${result.imported} flow(s) from $name")
                                sessionBusy = false
                            }
                        }
                    }
                },
                onExportSession = if (sessionBusy || store.size == 0) null else {
                    {
                        FileDialogs.saveHar(window, "bittrace.har")?.let { path ->
                            sessionBusy = true
                            thread(isDaemon = true, name = "har-export") {
                                val name = path.fileName.toString().substringBeforeLast('.')
                                val result = HarExporter(
                                    store, service.bodies,
                                    onLog = { level, message -> logs.add(level, "session", message) },
                                ).export(path)
                                if (result.error != null) {
                                    logs.add("error", "session", "export failed: ${result.error}")
                                } else {
                                    logs.add("info", "session", "exported ${result.written} flow(s) to $name")
                                    store.addExportMark(name)
                                }
                                sessionBusy = false
                            }
                        }
                    }
                },
                busy = sessionBusy,
            ),
            )
            AddressBar(host = "127.0.0.1", port = port, running = service.isRunning)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Rail stays a sibling on the left, so the floating log panel (scoped
            // to the content area on the right) never covers it.
            Rail(nav) { nav = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (nav) {
                    "traffic" -> TrafficView(
                        Modifier.fillMaxSize(), store, service, settings, formatters, flowActions, selectedId, outcome,
                        query = query,
                        bodyHits = bodyHits,
                        searching = searching,
                        onQuery = { query = it },
                        onSearching = { searching = it },
                        marked = markedIds.toSet(),
                        onToggleMark = { row ->
                            markedIds = if (row.id in markedIds) {
                                markedIds - row.id
                            } else {
                                (markedIds + row.id).takeLast(2)
                            }
                        },
                        onDiffMarked = if (markedIds.size == 2) {
                            { tools.show(Tool.DIFF) }
                        } else {
                            null
                        },
                        onNotice = { logs.add("warn", "ui", it) },
                    ) { selectedId = it }
                    "home" -> Box(Modifier.fillMaxSize()) {
                        HomeView(store, service, activity, port) { nav = "traffic" }
                    }
                    "api" -> Box(Modifier.fillMaxSize()) {
                        ApiView(
                            api, collections, history, store, service, settings, formatters,
                            git, collectionActions, window,
                            onLog = { level, message -> logs.add(level, "forge", message) },
                        )
                    }
                    "settings" -> Box(Modifier.fillMaxSize()) {
                        SettingsView(settings, service, themeManager)
                    }
                    else -> Box(Modifier.fillMaxSize().background(P.panel), contentAlignment = Alignment.Center) {
                        PzText("${nav.replaceFirstChar { it.uppercase() }} view — coming soon", color = P.faint, style = Typo.h2)
                    }
                }
                // Floating log panel, docked to the bottom of the content area.
                if (logsOpen) {
                    Box(Modifier.align(Alignment.BottomStart)) {
                        LogPanel(logs, logHeight) { delta ->
                            logHeight = (logHeight - delta).coerceIn(120.dp, 560.dp)
                        }
                    }
                }
            }
        }

        // Live status counts, read from the snapshot list so they recompose.
        val rows = store.rows
        val ok = rows.count { it.failed == false }
        val failed = rows.count { it.failed == true }
        // Headers and bodies, both directions — what actually crossed the wire,
        // not just the payloads. HAR writes -1 for a size it does not know, so
        // every term is floored at zero rather than allowed to subtract.
        val captured = rows.sumOf { r ->
            val request = r.request.request
            val response = r.response?.response
            request.headersSize.coerceAtLeast(0) + request.bodySize.coerceAtLeast(0) +
                (response?.headersSize?.coerceAtLeast(0) ?: 0L) + (response?.bodySize?.coerceAtLeast(0) ?: 0L)
        }
        // Tool windows are siblings of the main one and live inside the same
        // composition, so they inherit the theme without being told about it.
        tools.open.forEach { tool ->
            ToolWindow(title = tool.title, onClose = { tools.close(tool) }) {
                when (tool) {
                    // The marked pair when there is one, so opening the tool
                    // from the grid lands on the comparison you asked for
                    // rather than on a picker you have to fill in again.
                    Tool.DIFF -> DiffTool(
                        store, service::body, formatters,
                        initial = markedIds.takeIf { it.size == 2 }?.let { it[0] to it[1] },
                        fallbackId = selectedId,
                    )
                }
            }
        }

        if (importOpen) {
            ImportRequestDialog(
                importers = importers,
                onDismiss = { importOpen = false },
            ) { request, importer, warnings ->
                api.open(request, null)
                nav = "api"
                logs.add("info", "api", "imported a request via $importer")
                warnings.forEach { logs.add("warn", "api", it) }
            }
        }

        // Counted here rather than reported up from the grid, and against the
        // query alone: the column filters have their own visible controls in the
        // headers, whereas a query survives the band collapsing and this cell is
        // the only place that still says it is running.
        val queryOrigin = remember(rows.size) { originOf(rows) }
        StatusBar(
            flows = rows.size,
            // Whichever is running. Git wins a tie: it is the one that can take
            // seconds and the one where knowing something is still happening
            // stops a second click.
            busy = git.busyLabel ?: "Searching bodies…".takeIf { scanning },
            // The band seeds a default minute-wide window, so a bare "is there a
            // window" test would have this cell claiming a query from launch.
            // What it reports is the count, which is true either way.
            query = if (query.isEmpty) null else query.describe(),
            queryMatches = if (query.isEmpty) rows.size else rows.count { query.matches(it, queryOrigin, bodyHits) },
            ok = ok,
            failed = failed,
            captured = captured,
            // A lambda, so the row walk behind the estimate only happens when
            // the panel is actually opened.
            nerdStats = { NerdStats(store.size, store.estimatedBytes(), service.bodies.bytes) },
            logsOpen = logsOpen,
            warn = logs.warnCount,
            error = logs.errorCount,
            okFilterOn = outcome == Outcome.OK,
            failedFilterOn = outcome == Outcome.FAILED,
            // Clicking the active filter clears it, so the same cell toggles.
            onFilterOk = { outcome = if (outcome == Outcome.OK) Outcome.ALL else Outcome.OK },
            onFilterFailed = { outcome = if (outcome == Outcome.FAILED) Outcome.ALL else Outcome.FAILED },
            onToggleLogs = { logsOpen = !logsOpen },
        )
    }
}

/**
 * The toolbar menus. Every entry maps to something the app can already do —
 * a disabled entry (null action) is one the current state forbids, not a
 * placeholder.
 */
private fun appMenus(
    service: ProxyService,
    running: Boolean,
    port: Int,
    logsOpen: Boolean,
    horizontalLayout: Boolean,
    onNav: (String) -> Unit,
    onToggleLogs: () -> Unit,
    onOpenTool: (Tool) -> Unit,
    onToggleLayout: () -> Unit,
    onClearSelection: () -> Unit,
    onNewSession: () -> Unit,
    onNewRequest: () -> Unit,
    onImportRequest: () -> Unit,
    onNewProject: () -> Unit,
    onNewCollection: () -> Unit,
    onRefreshProjects: () -> Unit,
    onClearHistory: (() -> Unit)?,
    onSendRequest: () -> Unit,
    onEditFlow: (() -> Unit)?,
    apiBusy: Boolean,
    onImportSession: (() -> Unit)?,
    onExportSession: (() -> Unit)?,
    busy: Boolean,
): List<Menu> = listOf(
    // Each menu runs: what it makes, then what it moves in and out, then the
    // rest. Rules mark those joins, so a menu is read as two or three short
    // lists rather than one long one, and the entries that destroy something
    // never sit directly under the ones that create it.
    Menu(
        "Session",
        listOf(
            MenuAction("New session", hint = "clears") { onNewSession(); onClearSelection() },
            MenuAction(
                "Import HAR…",
                hint = if (busy) "busy" else "",
                separatorBefore = true,
                onClick = onImportSession,
            ),
            MenuAction("Export HAR…", hint = if (busy) "busy" else "", onClick = onExportSession),
        ),
    ),
    Menu(
        "Forge",
        listOf(
            MenuAction("Open Request Forge") { onNav("api") },
            // Authoring one request: make it, fill it, send it.
            MenuAction("New request", separatorBefore = true, onClick = onNewRequest),
            // Not "Import from clipboard": this opens a dialog to paste into,
            // and a label promising a silent clipboard read described an
            // action the entry does not take.
            MenuAction("Import request…", hint = "cURL", onClick = onImportRequest),
            MenuAction("Send", hint = if (apiBusy) "sending" else "", onClick = if (apiBusy) null else onSendRequest),
            // The point of pairing a proxy with a client: replay what was
            // actually observed. Disabled with nothing selected, and on its own
            // because it is the one entry that reaches across to the grid.
            MenuAction(
                "Edit selected flow as request",
                hint = if (onEditFlow == null) "select a flow" else "",
                separatorBefore = true,
                onClick = onEditFlow,
            ),
            // Everything about stored requests, folded away: it is the part of
            // this menu reached least often, and flattening it would put a
            // destructive entry next to "New request".
            MenuAction(
                "Projects",
                separatorBefore = true,
                submenu = listOf(
                    MenuAction("New project", onClick = onNewProject),
                    MenuAction("New collection", onClick = onNewCollection),
                    MenuAction("Refresh from disk", onClick = onRefreshProjects),
                    MenuAction("Clear request history", separatorBefore = true, onClick = onClearHistory),
                ),
            ),
        ),
    ),
    Menu(
        "Proxy",
        listOf(
            // Start/stop hop off the UI thread — both block on the sidecar.
            MenuAction(
                "Start",
                hint = if (running) "running" else "",
                onClick = if (running) {
                    null
                } else {
                    { thread(isDaemon = true, name = "proxy-start") { runCatching { service.start(port) } } }
                },
            ),
            MenuAction(
                "Stop",
                onClick = if (running) {
                    { thread(isDaemon = true, name = "proxy-stop") { runCatching { service.stop() } } }
                } else {
                    null
                },
            ),
            MenuAction("Settings…", separatorBefore = true) { onNav("settings") },
        ),
    ),
    Menu(
        "Tools",
        // One entry per tool, and the list is the enum's, so a tool that exists
        // is a tool you can open — there is no second place to remember.
        Tool.entries.map { tool -> MenuAction("${tool.title}…") { onOpenTool(tool) } },
    ),
    Menu(
        "View",
        listOf(
            // Where to go, flat and first — this is the most-used menu in the
            // app, and burying four one-click destinations in a submenu to tidy
            // the list would cost a click every time to save a glance once.
            MenuAction("Home") { onNav("home") },
            MenuAction("Traffic") { onNav("traffic") },
            MenuAction("Request Forge") { onNav("api") },
            MenuAction("Settings") { onNav("settings") },
            // How it is arranged, below the rule.
            // Named for the arrangement, not for where the inspector lands —
            // the same two words the Appearance pane uses, so the menu and the
            // setting cannot be read as two different options. The label is the
            // layout it switches to; the hint is the one in force.
            MenuAction(
                if (horizontalLayout) "Vertical layout" else "Horizontal layout",
                hint = if (horizontalLayout) "horizontal" else "vertical",
                separatorBefore = true,
                onClick = onToggleLayout,
            ),
            MenuAction(if (logsOpen) "Hide proxy log" else "Show proxy log", onClick = onToggleLogs),
        ),
    ),
)

/**
 * How long typing settles before a body scan runs.
 *
 * Short enough to feel immediate, long enough that a word typed at speed costs
 * one pass over the bodies rather than one per character.
 */
private const val SEARCH_DEBOUNCE_MS = 250L
