package org.bittrace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.bittrace.components.FlowTable
import org.bittrace.components.Inspector
import org.bittrace.components.applyFilters
import org.bittrace.components.defaultColumns
import org.bittrace.data.LogStore
import org.bittrace.data.SettingsStore
import org.bittrace.data.TrafficStore
import org.bittrace.plugin.PluginLoader
import org.bittrace.plugin.ThemeManager
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.HomeView
import org.bittrace.ui.HorizontalSplitter
import org.bittrace.ui.LogPanel
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.Rail
import org.bittrace.ui.SettingsView
import org.bittrace.ui.StatusBar
import org.bittrace.ui.Waterfall
import kotlin.concurrent.thread

fun main() = application {
    val store = remember { TrafficStore() }
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
    // and body formatters from the registry.
    val registry = remember { PluginLoader.load() }
    val themeManager = remember { ThemeManager(registry) }
    val formatters = remember { registry.formatters }

    // Start the sidecar off the UI thread; stop it when the app closes.
    DisposableEffect(Unit) {
        val port = settings.settings.proxyPort
        thread(isDaemon = true, name = "proxy-launcher") {
            runCatching { service.start(port) }
                .onSuccess { logs.add("info", "proxy", "listening on 127.0.0.1:$port") }
                .onFailure { logs.add("error", "proxy", "start failed: ${it.message}") }
        }
        onDispose { service.stop() }
    }

    Window(
        onCloseRequest = { service.stop(); exitApplication() },
        title = "BitTrace",
    ) {
        App(store, service, settings, themeManager, logs, formatters)
    }
}

@Composable
private fun App(
    store: TrafficStore,
    service: ProxyService,
    settings: SettingsStore,
    themeManager: ThemeManager,
    logs: LogStore,
    formatters: List<BodyFormatter>,
) {
    var nav by remember { mutableStateOf("traffic") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var logsOpen by remember { mutableStateOf(false) }
    var logHeight by remember { mutableStateOf(260.dp) }
    val port = settings.settings.proxyPort

    // Apply the persisted theme, and re-apply whenever it changes.
    LaunchedEffect(settings.settings.theme) { themeManager.applyId(settings.settings.theme) }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Rail stays a sibling on the left, so the floating log panel (scoped
            // to the content area on the right) never covers it.
            Rail(nav) { nav = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (nav) {
                    "traffic" -> TrafficView(Modifier.fillMaxSize(), store, service, settings, formatters, selectedId) { selectedId = it }
                    "home" -> Box(Modifier.fillMaxSize()) {
                        HomeView(store, service, port) { nav = "traffic" }
                    }
                    "settings" -> Box(Modifier.fillMaxSize()) {
                        SettingsView(settings, service, themeManager)
                    }
                    else -> Box(Modifier.fillMaxSize().background(P.panel), contentAlignment = Alignment.Center) {
                        PzText("${nav.replaceFirstChar { it.uppercase() }} view — coming soon", color = P.faint, size = 14)
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
        StatusBar(rows.size, ok, failed, port, logsOpen, logs.warnCount, logs.errorCount) { logsOpen = !logsOpen }
    }
}

@Composable
private fun TrafficView(
    modifier: Modifier,
    store: TrafficStore,
    service: ProxyService,
    settings: SettingsStore,
    formatters: List<BodyFormatter>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val rows = store.rows
    // Column order (drag-reorderable) and per-column filters live here so the
    // FLOWS count reflects filtering and the order survives recomposition.
    val cols = remember { mutableStateListOf(*defaultColumns().toTypedArray()) }
    val filters = remember { mutableStateMapOf<String, String>() }
    val visible = applyFilters(rows, cols, filters)

    // Inspector height is persisted, so it survives restarts.
    val inspectorHeight = settings.settings.inspectorHeightDp.dp

    Column(modifier.fillMaxHeight()) {
        Waterfall(visible, selectedId, onSelect)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FlowTable(cols, filters, visible, selectedId, onSelect)
        }
        // Drag this bar up/down to resize the inspector.
        HorizontalSplitter { delta ->
            settings.update { it.copy(inspectorHeightDp = (it.inspectorHeightDp - delta.value).coerceIn(120f, 640f)) }
        }
        Box(Modifier.fillMaxWidth().height(inspectorHeight)) {
            Inspector(selectedId?.let { store.get(it) }, service::body, settings, formatters)
        }
    }
}
