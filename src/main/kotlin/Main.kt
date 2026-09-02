package org.bittrace

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import org.bittrace.api.HistoryStore
import org.bittrace.api.requestFromFlow
import org.bittrace.components.AddressBar
import org.bittrace.components.ApiView
import org.bittrace.components.FlowTable
import org.bittrace.components.HomeView
import org.bittrace.components.BodySearchBar
import org.bittrace.components.BodySearchState
import org.bittrace.components.ImportRequestDialog
import org.bittrace.components.matchingBodies
import org.bittrace.components.NerdStats
import org.bittrace.tools.DiffTool
import org.bittrace.tools.Tool
import org.bittrace.tools.ToolWindow
import org.bittrace.tools.ToolWindows
import org.bittrace.components.Inspector
import org.bittrace.components.LogPanel
import org.bittrace.components.Menu
import org.bittrace.components.MenuAction
import org.bittrace.components.MenuBar
import org.bittrace.components.Outcome
import org.bittrace.components.Rail
import org.bittrace.components.SettingsView
import org.bittrace.components.StatusBar
import org.bittrace.components.TableMode
import org.bittrace.components.Waterfall
import org.bittrace.components.applyOutcome
import org.bittrace.components.columnsFor
import org.bittrace.components.sessionBanners
import org.bittrace.data.ActivityStore
import org.bittrace.data.LogStore
import org.bittrace.data.SessionStore
import org.bittrace.data.TrafficRow
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
import org.bittrace.ui.ColumnFilter
import org.bittrace.ui.FileDialogs
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.SplitPane
import org.bittrace.ui.applyGridFilters
import org.bittrace.ui.bottomBorder
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar

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
            delay(30_000)
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
    var importOpen by remember { mutableStateOf(false) }
    val collections = remember { CollectionStore() }
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
                onRefreshCollections = {
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
                        ApiView(api, collections, history, store, service, settings, formatters, collectionActions, window)
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
        val ok = rows.count { r -> r.response?.let { !it.error && it.response.status < 400 } == true }
        val failed = rows.count { r -> r.response?.let { it.error || it.response.status >= 400 } == true }
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

        StatusBar(
            flows = rows.size,
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
    onRefreshCollections: () -> Unit,
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
        "API",
        listOf(
            MenuAction("Open API client") { onNav("api") },
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
                "Collections",
                separatorBefore = true,
                submenu = listOf(
                    MenuAction("New project", onClick = onNewProject),
                    MenuAction("New collection", onClick = onNewCollection),
                    MenuAction("Refresh from disk", onClick = onRefreshCollections),
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
            MenuAction("API client") { onNav("api") },
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

@Composable
private fun TrafficView(
    modifier: Modifier,
    store: SessionStore,
    service: ProxyService,
    settings: SettingsStore,
    formatters: List<BodyFormatter>,
    flowActions: List<FlowActionPlugin>,
    selectedId: String?,
    outcome: Outcome,
    marked: Set<String>,
    onToggleMark: (TrafficRow) -> Unit,
    onDiffMarked: (() -> Unit)?,
    onNotice: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val rows = store.rows
    // Column order (drag-reorderable) and per-column filters live here so the
    // FLOWS count reflects filtering and the order survives recomposition.
    // Enabled columns, like the row mode, are resolved once per view build.
    val cols = remember { mutableStateListOf(*columnsFor(settings.settings.tableColumns).toTypedArray()) }
    val filters = remember { mutableStateMapOf<String, ColumnFilter>() }
    // Compact vs detailed rows is read once, when this view is built — not on
    // every row, arrival or frame. Changing it in Settings applies on the next
    // visit to the traffic view.
    val tableMode = remember { TableMode.from(settings.settings.tableMode) }
    val visible = applyOutcome(applyGridFilters(rows, cols, filters), outcome)
    // Session boundaries are derived from the visible rows, so filtering can
    // never leave a banner stranded.
    val banners = sessionBanners(visible, store::sessionName, store.exportMarks)

    // Inspector size is persisted per dock, so switching docks restores the
    // size that dock last had rather than reusing the other one's.
    val inspectorHeight = settings.settings.inspectorHeightDp.dp
    val inspectorWidth = settings.settings.inspectorWidthDp.dp
    // Read on every composition, not remembered: the View menu toggles this and
    // the change should land immediately.
    val horizontal = settings.settings.horizontalLayout
    val selectedRow = selectedId?.let { store.get(it) }

    // Body search. The scan runs off the UI thread and is debounced, because it
    // decodes and searches every cached body in the table — cheap for a hundred
    // rows and not for ten thousand, and it re-runs on every keystroke.
    val search = remember { BodySearchState() }
    var scanning by remember { mutableStateOf(false) }
    var bodyMatches by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(search.query, search.side, search.regex, search.open, rows.size) {
        if (!search.open || search.query.isBlank()) {
            bodyMatches = null
            search.problem = null
            scanning = false
            return@LaunchedEffect
        }
        // A pause before scanning, so typing a six-character word scans once
        // rather than six times.
        scanning = true
        delay(SEARCH_DEBOUNCE_MS)
        val snapshot = rows.toList()
        val result = withContext(Dispatchers.Default) {
            matchingBodies(snapshot, search.query, search.side, search.regex, service::body)
        }
        result
            .onSuccess { ids ->
                bodyMatches = ids
                search.matches = ids.size
                search.problem = null
            }
            // A regex mid-typing is usually invalid; saying so beats showing
            // zero results as though the search had run.
            .onFailure { search.problem = "bad pattern" }
        scanning = false
    }

    val shown = bodyMatches?.let { ids -> visible.filter { it.id in ids } } ?: visible

    // Hoisted rather than written into each dock branch: the two used to carry
    // the same eight arguments, and only one of the copies would get updated.
    val table: @Composable (Modifier) -> Unit = { paneModifier ->
        Box(paneModifier) {
            FlowTable(
                cols, filters, shown, rows, selectedId, tableMode, banners,
                bodyProvider = service::body, flowActions = flowActions,
                marked = marked, onToggleMark = onToggleMark, onDiffMarked = onDiffMarked,
                onNotice = onNotice, onSelect = onSelect,
            )
        }
    }

    Column(
        // `onKeyEvent`, not `onPreviewKeyEvent`. A preview travels root-down and
        // fires before the focused node, so previewing here claimed Ctrl+F from
        // whatever had focus — and the inspector's body view has its own Ctrl+F,
        // which stopped working the moment this was added. Bubbling is the right
        // semantic for a view-level shortcut: the focused thing gets first
        // refusal, and this only sees what nothing else wanted.
        modifier.fillMaxHeight().onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when {
                event.isCtrlPressed && event.key == Key.F -> { search.show(); true }
                event.key == Key.Escape && search.open -> { search.hide(); true }
                else -> false
            }
        },
    ) {
        // The waterfall keeps showing everything: it is the overview, and an
        // overview narrowed by a search is no longer one. The grid below is
        // what the search filters.
        Waterfall(visible, selectedId, onSelect)
        if (search.open) BodySearchBar(search, scanning)

        // In the horizontal layout the inspector sits beside the table and its
        // own panes stack, since height is the plentiful axis there. No selection means no
        // inspector and no splitter either — a grip that resizes nothing is a
        // control that lies — so the table takes the whole area until a row is
        // picked.
        SplitPane(
            horizontal = horizontal,
            secondSize = if (horizontal) inspectorWidth else inspectorHeight,
            onResize = { delta ->
                settings.update {
                    if (horizontal) {
                        it.copy(inspectorWidthDp = (it.inspectorWidthDp + delta.value).coerceIn(280f, 1200f))
                    } else {
                        it.copy(inspectorHeightDp = (it.inspectorHeightDp + delta.value).coerceIn(120f, 640f))
                    }
                }
            },
            second = selectedRow?.let { row ->
                { paneModifier: Modifier ->
                    Box(paneModifier) {
                        Inspector(row, service::body, settings, formatters, stacked = horizontal)
                    }
                }
            },
            first = table,
        )
    }
}

/**
 * How long typing settles before a body scan runs.
 *
 * Short enough to feel immediate, long enough that a word typed at speed costs
 * one pass over the bodies rather than one per character.
 */
private const val SEARCH_DEBOUNCE_MS = 250L
