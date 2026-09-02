package org.bittrace.components

import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bittrace.api.CollectionNode
import org.bittrace.api.Node
import org.bittrace.api.ProjectNode
import org.bittrace.api.RequestNode
import org.bittrace.plugin.collection.CollectionActionContext
import org.bittrace.plugin.collection.CollectionActionPlugin
import org.bittrace.plugin.collection.CollectionTarget
import org.bittrace.plugin.collection.CollectionTargetKind
import kotlinx.coroutines.withContext
import org.bittrace.api.ApiClientState
import org.bittrace.api.RequestTab
import org.bittrace.api.CollectionStore
import org.bittrace.api.oauth.OAuthService
import org.bittrace.api.ImportReport
import org.bittrace.api.HTTP_METHODS
import org.bittrace.api.HistoryEntry
import org.bittrace.api.HistoryStore
import org.bittrace.api.KeyValue
import org.bittrace.api.paramsOf
import org.bittrace.api.resolve
import org.bittrace.api.urlWithParams
import org.bittrace.data.SessionStore
import org.bittrace.data.horizontalLayout
import org.bittrace.data.SettingsStore
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.CellText
import org.bittrace.ui.Format
import org.bittrace.ui.FormatPicker
import org.bittrace.ui.CodeEditor
import org.bittrace.ui.EDITABLE_LIMIT
import org.bittrace.ui.FileDialogs
import org.bittrace.ui.GhostButton
import org.bittrace.ui.P
import org.bittrace.ui.PaneHeader
import org.bittrace.ui.PillTabs
import org.bittrace.ui.PrimaryButton
import org.bittrace.ui.PzText
import org.bittrace.ui.SplitPane
import org.bittrace.ui.TextInput
import org.bittrace.ui.VScrollbar
import org.bittrace.ui.VerticalSplitter
import org.bittrace.ui.bottomBorder
import org.bittrace.ui.revealed
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.editorTabStyle

/**
 * The API client: author a request, send it through BitTrace's own proxy, and
 * read the response in the same Inspector that serves captured traffic.
 *
 * Three columns — saved collections, the request being built, and the response.
 * The Inspector is used unchanged; because the request goes through the proxy,
 * what it shows is the real captured flow, already decoded, with wire timings.
 */
@Composable
fun ApiView(
    state: ApiClientState,
    collections: CollectionStore,
    history: HistoryStore,
    store: SessionStore,
    service: ProxyService,
    settings: SettingsStore,
    formatters: List<BodyFormatter>,
    /** Plugins contributing items to the collections tree's context menu. */
    collectionActions: List<CollectionActionPlugin>,
    /** Owner for the file picker; the API menu and rail both route through App. */
    window: ComposeWindow,
) {
    // One service for the view, holding the token store the client state owns.
    // Built here rather than in the state because it needs the proxy's port and
    // whether it is running — the same two things a send reads.
    val oauth = remember(state, service) {
        OAuthService(state.tokens, proxyPort = { settings.settings.proxyPort }, viaProxy = { service.isRunning })
    }

    var tab by remember { mutableStateOf("Params") }
    var sideTab by remember { mutableStateOf("Collections") }
    var notice by remember { mutableStateOf<String?>(null) }
    // The tab waiting on an answer to "save before closing?".
    var pendingClose by remember { mutableStateOf<RequestTab?>(null) }
    var selectedPath by remember { mutableStateOf<Path?>(null) }

    // Walking the collections folder touches the disk, so it happens off the
    // UI thread — on first entry and whenever the view is returned to.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            collections.reload()
            history.load()
        }
    }

    val treeWidth = settings.settings.apiTreeWidthDp.dp
    val responseWidth = settings.settings.apiResponseWidthDp.dp
    val responseHeight = settings.settings.apiResponseHeightDp.dp
    // The same setting the traffic inspector follows: horizontal sits the panes
    // side by side, vertical stacks them.
    val horizontal = settings.settings.horizontalLayout

    Row(Modifier.fillMaxSize().background(P.bg)) {
        // --- collections / history ---
        Column(Modifier.width(treeWidth).fillMaxHeight().background(P.panel).rightBorder(P.line)) {
            PaneHeader {
                PillTabs(listOf("Collections", "History"), sideTab) { sideTab = it }
            }

            if (sideTab == "History") {
                HistoryList(
                    entries = history.entries,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpen = { entry ->
                        // Opened as a fresh draft: history is a record of what
                        // was sent, not a file to save back over.
                        state.open(entry.request, null)
                        selectedPath = null
                        notice = null
                    },
                    onRemove = { history.remove(it) },
                )
                return@Column
            }

            // Plugin menu items, translated into the plain label-and-lambda the
            // tree understands. Built here rather than in the tree because this
            // is where the collection store, the notice strip and a scope to
            // reload on all already exist — the three things an action's
            // context is made of.
            val scope = rememberCoroutineScope()
            val actionContext = remember(collections) {
                object : CollectionActionContext {
                    override fun refresh() {
                        scope.launch { withContext(Dispatchers.IO) { collections.reload() } }
                    }

                    override fun notify(message: String) {
                        notice = message
                    }
                }
            }

            CollectionTree(
                nodes = collections.tree,
                selected = selectedPath,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onOpen = { node ->
                    collections.read(node)
                        .onSuccess { state.open(it, node.path); selectedPath = node.path; notice = null }
                        .onFailure { notice = "Could not read ${node.name}: ${it.message}" }
                },
                onSelectFolder = { selectedPath = it.path },
                onMenuItems = { node ->
                    val target = targetOf(node)
                    val plugins = collectionActions
                        .flatMap { plugin -> plugin.actionsFor(target) }
                        // Stable across plugins: a plugin orders its own items
                        // with `order`, and equal orders keep load order, so
                        // installing one cannot reshuffle another's.
                        .sortedBy { it.order }
                        .map { action ->
                            TreeMenuItem(action.label, action.enabled) { action.perform(actionContext) }
                        }
                    // The host's own come first, above whatever plugins add.
                    val host = createItems(node, collections, scope, { notice = it }) { made ->
                        selectedPath = made
                    } + archiveItems(node, collections, window, scope) { notice = it }
                    host + plugins
                },
                onRename = { node, name ->
                    collections.rename(node, name)
                        .onSuccess { moved ->
                            // Keep the open request pointing at its file, and
                            // keep its display name in step with the rename.
                            if (state.openPath == node.path) {
                                state.open(state.request.copy(name = name), moved)
                            }
                            if (selectedPath == node.path) selectedPath = moved
                            notice = null
                        }
                        .onFailure { notice = it.message ?: "Rename failed." }
                },
                onDelete = { node ->
                    collections.delete(node)
                        .onSuccess {
                            if (state.openPath == node.path) state.open(state.request, null)
                            if (selectedPath == node.path) selectedPath = null
                            notice = "Moved '${node.name}' to the collections .trash folder."
                        }
                        .onFailure { notice = it.message ?: "Delete failed." }
                },
            )
            collections.error?.let {
                Box(Modifier.fillMaxWidth().padding(10.dp)) {
                    PzText(it, color = P.err, style = Typo.caption, family = P.Ui)
                }
            }
        }

        VerticalSplitter { delta ->
            settings.update { it.copy(apiTreeWidthDp = (it.apiTreeWidthDp + delta.value).coerceIn(160f, 480f)) }
        }

        // The address bar spans everything right of the sidebar, so the URL is
        // never squeezed by whatever the response is given. Builder and response
        // then follow the same horizontal/vertical setting the traffic
        // inspector uses, since it describes the same choice.
        Column(Modifier.weight(1f).fillMaxHeight()) {
            RequestTabs(state) { tab ->
                // A clean tab just goes. A dirty one is focused first, so the
                // dialog's Save acts on the request it is asking about rather
                // than on whichever tab happened to be in front.
                if (tab.dirty) {
                    state.focus(tab)
                    pendingClose = tab
                } else {
                    state.close(tab)
                }
            }
            RequestBar(state, service, collections, selectedPath) { notice = it }

            // --- request builder ---
            val builder: @Composable (Modifier) -> Unit = { paneModifier ->
                Column(paneModifier) {
                    // The builder is a pane like any other, so it gets a pane
                    // header: title first, then its sections. The send result
                    // belongs to the response and lives in that header instead.
                    PaneHeader(title = "Request") {
                        PillTabs(listOf("Params", "Headers", "Cookies", "Body", "Auth", "Settings"), tab, Modifier.weight(1f)) { tab = it }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            "Params" -> KvTab(state.request.params, "param") { rows ->
                                // Editing a param rewrites the query in place, so
                                // the URL above shows what will actually be sent.
                                // Encoded the way this request will be sent, so
                                // the URL above shows what actually goes out —
                                // switching the encoding rewrites it in place.
                                val encoding = state.request.settings.resolve(settings.settings).urlEncoding
                                state.edit { it.copy(params = rows, url = urlWithParams(it.url, rows, encoding)) }
                            }

                            "Headers" -> KvTab(state.request.headers, "header") { rows ->
                                state.edit { it.copy(headers = rows) }
                            }

                            "Cookies" -> KvTab(state.request.cookies, "cookie") { rows ->
                                state.edit { it.copy(cookies = rows) }
                            }

                            "Auth" -> AuthTab(state, oauth)

                            "Settings" -> RequestSettingsTab(state, settings.settings)

                            else -> BodyTab(state, window) { notice = it }
                        }
                    }

                    notice?.let {
                        Box(Modifier.fillMaxWidth().background(P.panel).topBorder(P.line).padding(10.dp)) {
                            PzText(it, color = P.warn, style = Typo.caption, family = P.Ui)
                        }
                    }
                }
            }

            // --- response, through the ordinary Inspector ---
            val response: @Composable (Modifier) -> Unit = { paneModifier ->
                Box(paneModifier) {
                    val row = state.resultId?.let { store.get(it) }
                    // Response only: the request is authored right beside it.
                    // The Inspector carries its own header, so nothing wraps it —
                    // the send result goes into that header.
                    Inspector(
                        row, service::body, settings, formatters, showRequest = false,
                        responseTrailing = state.status?.let { result ->
                            {
                                PzText(
                                    result,
                                    color = P.faint, style = Typo.caption,
                                    family = P.Ui, softWrap = false,
                                )
                            }
                        },
                    )
                }
            }

            SplitPane(
                horizontal = horizontal,
                secondSize = if (horizontal) responseWidth else responseHeight,
                // Each axis keeps its own remembered size, so switching layouts
                // restores what that layout last had rather than reusing the
                // other one's.
                onResize = { delta ->
                    settings.update {
                        if (horizontal) {
                            it.copy(apiResponseWidthDp = (it.apiResponseWidthDp + delta.value).coerceIn(280f, 1200f))
                        } else {
                            it.copy(apiResponseHeightDp = (it.apiResponseHeightDp + delta.value).coerceIn(120f, 720f))
                        }
                    }
                },
                second = response,
                first = builder,
            )
        }
    }

    // Asked when a tab with unsaved edits is closed. The tab was focused before
    // this opened, so Save writes the request the dialog is naming.
    pendingClose?.let { tab ->
        UnsavedChangesDialog(
            name = tab.title,
            onSave = {
                val failure = save(state, collections, selectedPath)
                notice = failure
                // A failed save leaves the tab open — losing the edits because
                // the write did not land is the one outcome nobody wants.
                if (failure == null) {
                    state.close(tab)
                    pendingClose = null
                }
            },
            onDiscard = { state.close(tab); pendingClose = null },
            onCancel = { pendingClose = null },
        )
    }
}

/**
 * One of the three name/value tabs.
 *
 * Params, headers and cookies are the same editor over a different list — the
 * only thing that varies is what a blank row is called. It fills the pane, so
 * the surface reaches the edges rather than stopping at the last row.
 */
@Composable
private fun KvTab(rows: List<KeyValue>, nameHint: String, onChange: (List<KeyValue>) -> Unit) =
    KvEditor(
        rows = rows,
        nameHint = nameHint,
        valueHint = "value",
        modifier = Modifier.fillMaxSize(),
        onChange = onChange,
    )

/**
 * Making the next thing down: a collection in a project, a request in a
 * collection.
 *
 * On the row that will hold it, rather than only on the toolbar, because that is
 * where you are when you decide you want one — and because the toolbar's New
 * collection has to guess which project you meant, while this one already knows.
 *
 * The new node is selected but not opened. Naming it is the next thing anybody
 * does, and the tree renames inline on a double-click; opening a blank request
 * first would put a tab in the way of that.
 */
private fun createItems(
    node: Node,
    collections: CollectionStore,
    scope: CoroutineScope,
    onNotice: (String) -> Unit,
    onCreated: (Path) -> Unit,
): List<TreeMenuItem> = when (node) {
    is ProjectNode -> listOf(
        TreeMenuItem("New collection") {
            scope.launch {
                withContext(Dispatchers.IO) { collections.createNamedCollection(node.path) }
                    .onSuccess { made -> onCreated(made); onNotice("Created ${made.fileName} in ${node.name}") }
                    .onFailure { onNotice("Could not create a collection: ${it.message}") }
            }
        },
    )

    is CollectionNode -> listOf(
        TreeMenuItem("New request") {
            scope.launch {
                withContext(Dispatchers.IO) { collections.createNamedRequest(node.path) }
                    .onSuccess { made -> onCreated(made); onNotice("Created ${made.fileName} in ${node.name}") }
                    .onFailure { onNotice("Could not create a request: ${it.message}") }
            }
        },
    )

    // Nothing sits below a request, so it offers nothing to make.
    else -> emptyList()
}

/**
 * Export and Import, for a project or a collection.
 *
 * Both dialogs block the event thread while they are open, which is what a modal
 * dialog is; the disk work that follows does not, because zipping a project is
 * more than a frame's worth of it. That split is why each item hands off to
 * [scope] the moment the dialog closes.
 */
private fun archiveItems(
    node: Node,
    collections: CollectionStore,
    window: ComposeWindow,
    scope: CoroutineScope,
    onNotice: (String) -> Unit,
): List<TreeMenuItem> {
    if (node is RequestNode) return emptyList()

    val kind = if (node is ProjectNode) "project" else "collection"
    return listOf(
        TreeMenuItem("Export…") {
            val target = FileDialogs.saveZip(window, "${node.name}.zip") ?: return@TreeMenuItem
            scope.launch {
                val result = withContext(Dispatchers.IO) { collections.exportNode(node, target) }
                result
                    .onSuccess { files ->
                        onNotice("Exported ${node.name} — $files ${plural(files, "request")} to ${target.fileName}")
                        // After the notice, so a file manager that takes a
                        // moment to appear does not delay the confirmation.
                        FileDialogs.revealInFolder(target)
                    }
                    .onFailure { onNotice("Could not export ${node.name}: ${it.message}") }
            }
        },
        TreeMenuItem("Import…") {
            val archive = FileDialogs.openZip(window) ?: return@TreeMenuItem
            scope.launch {
                withContext(Dispatchers.IO) { collections.importInto(node, archive) }
                    .onSuccess { report -> onNotice(importNotice(report, kind)) }
                    .onFailure { onNotice("Could not import ${archive.fileName}: ${it.message}") }
            }
        },
    )
}

/**
 * What an import did, in one line.
 *
 * Skipped entries are counted out loud rather than left implicit: a zip unpacked
 * at the wrong level writes nothing, and an import that says nothing at all
 * would be indistinguishable from one that worked.
 */
private fun importNotice(report: ImportReport, kind: String): String {
    val skipped = report.skipped.size
    return when {
        report.written.isEmpty() && skipped > 0 ->
            "Nothing imported — that zip holds nothing a $kind can contain"

        report.written.isEmpty() -> "That zip is empty."
        skipped > 0 ->
            "Imported ${report.written.size} of ${report.written.size + skipped} — " +
                "$skipped skipped, ${report.files} ${plural(report.files, "request")} written"

        else -> "Imported ${report.written.joinToString(", ")} — " +
            "${report.files} ${plural(report.files, "request")}"
    }
}

private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

/**
 * The tree's own node, as the plugin API describes it.
 *
 * The project and collection a node sits in are read off its path rather than
 * looked up in the store: the hierarchy is exactly three levels deep, so the
 * two names are the parent folders and nothing has to be searched for them.
 */
private fun targetOf(node: Node): CollectionTarget = when (node) {
    is RequestNode -> CollectionTarget(
        kind = CollectionTargetKind.REQUEST,
        name = node.name,
        path = node.path,
        project = nameAbove(node.path, 2),
        collection = nameAbove(node.path, 1),
        method = node.method,
    )

    is CollectionNode -> CollectionTarget(
        kind = CollectionTargetKind.COLLECTION,
        name = node.name,
        path = node.path,
        project = nameAbove(node.path, 1),
        collection = node.name,
    )

    is ProjectNode -> CollectionTarget(
        kind = CollectionTargetKind.PROJECT,
        name = node.name,
        path = node.path,
        project = node.name,
    )
}

/** The name of the folder [levels] above [path]; blank if the path is shorter than that. */
private fun nameAbove(path: Path, levels: Int): String =
    generateSequence(path) { it.parent }.elementAtOrNull(levels)?.fileName?.toString().orEmpty()

/**
 * The open requests, one tab each.
 *
 * Each tab carries its own draft, its own response and its own in-flight send,
 * so switching between them is just looking at a different request — nothing is
 * cancelled and no response is overwritten.
 */
@Composable
private fun RequestTabs(state: ApiClientState, onClose: (RequestTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(P.chrome).bottomBorder(P.line),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Editor tabs: Jewel gives the close affordance, the hover reveal and
        // the overflow scrolling that this strip used to draw itself.
        TabStrip(
            tabs = state.tabs.map { tab ->
                TabData.Editor(
                    selected = tab.id == state.activeId,
                    // Every tab closes. Unsaved edits do not take the cross
                    // away — they make closing ask a question first, which is
                    // the guard that hiding the cross was standing in for.
                    closable = true,
                    onClose = { onClose(tab) },
                    onClick = { state.focus(tab) },
                    content = { _ ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PzText(
                                tab.request.method,
                                color = methodColor(tab.request.method),
                                style = Typo.micro, family = P.Ui, softWrap = false,
                            )
                            Spacer(Modifier.width(6.dp))
                            CellText(
                                tab.title,
                                color = P.text,
                                style = Typo.caption, family = P.Ui,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                            if (tab.dirty) {
                                Spacer(Modifier.width(6.dp))
                                PzText("\u25CF", color = P.warn, style = Typo.micro, family = P.Ui)
                            }
                        }
                    },
                )
            },
            style = JewelTheme.editorTabStyle,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconActionButton(
            key = AllIconsKeys.General.Add,
            contentDescription = "New request",
            onClick = { state.open(org.bittrace.api.ApiRequest(), null) },
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/**
 * Every distinct request that has been sent, newest first.
 *
 * Flat and chronological on purpose — history answers "what did I just run?",
 * which an alphabetical tree would actively get in the way of.
 */
@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    modifier: Modifier = Modifier,
    onOpen: (HistoryEntry) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    PzText("Nothing sent yet", color = P.faint, style = Typo.label, family = P.Ui)
                }
            }
            entries.forEach { entry ->
                HistoryRow(entry, onOpen = { onOpen(entry) }, onRemove = { onRemove(entry) })
            }
        }
        VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val request = entry.request
    val url = runCatching { java.net.URI(request.url) }.getOrNull()
    val label = url?.let { "${it.host.orEmpty()}${it.path.orEmpty()}" }?.ifBlank { request.url } ?: request.url

    Row(
        Modifier.fillMaxWidth().bottomBorder(P.line2)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onOpen() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PzText(request.method, color = P.info, style = Typo.micro, family = P.Ui, softWrap = false)
                Spacer(Modifier.width(6.dp))
                CellText(label, color = P.text, style = Typo.caption)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PzText(dayClockOf(entry.lastUsedMillis), color = P.faint, style = Typo.micro, softWrap = false)
                if (entry.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    PzText("×${entry.count}", color = P.dim, style = Typo.micro, family = P.Ui, softWrap = false)
                }
            }
        }
        // Always laid out, only sometimes visible — adding the button on hover
        // would make the row change size as the pointer crossed it. `enabled` is
        // what gates the click; alpha alone leaves an invisible live button.
        IconActionButton(
            key = AllIconsKeys.General.Delete,
            contentDescription = "Remove from history",
            enabled = hovered,
            onClick = onRemove,
            modifier = Modifier.revealed(hovered),
        )
    }
}

/**
 * The body tab: a content type, a file picker, and the editor.
 *
 * A body is either typed or a file — never both — so choosing a file replaces
 * the text and clearing it hands the editor back. A text file small enough to
 * edit is read into the editor instead of being attached, since editing it is
 * usually the point; anything else stays on disk and is streamed at send time.
 */
@Composable
private fun BodyTab(state: ApiClientState, window: ComposeWindow, onNotice: (String?) -> Unit) {
    val body = state.request.body
    // Not persisted, unlike the inspector's splits: this one is a preference
    // about one tab of one request, and remembering it across restarts would be
    // a settings field earning very little.
    var variablesHeight by remember { mutableStateOf(140.dp) }

    Column(Modifier.fillMaxSize()) {
        // The same chip row the inspector puts above a body, so picking a format
        // looks and works the same whether you are reading one or writing one.
        // The chip sets the content type, which in turn drives highlighting.
        val active = formatOf(body.contentType)
        val formats = bodyFormats(body.contentType)
        FormatPicker(
            formats = formats.map { Format(it.label, it.label) },
            selected = active.label,
            surface = P.input,
            onSelect = { picked ->
                formats.firstOrNull { it.label == picked.id }?.let { format ->
                    state.edit { it.copy(body = it.body.copy(contentType = format.mime)) }
                }
            },
        ) {
            Spacer(Modifier.weight(1f))
            GhostButton(if (body.fromFile) "Remove file" else "File…") {
                if (body.fromFile) {
                    state.edit { it.copy(body = it.body.copy(filePath = "")) }
                    onNotice(null)
                } else {
                    onNotice(attach(state, window))
                }
            }
        }

        when {
            body.fromFile -> FileBody(body.filePath)

            // GraphQL is two documents, so it gets two editors: the operation is
            // GraphQL and the variables are JSON, they highlight differently, and
            // only one of them is worth completing. They are assembled into the
            // JSON envelope at send time — see `ApiBody.payload`.
            body.isGraphQl -> {
                SplitPane(
                    horizontal = false,
                    secondSize = variablesHeight,
                    onResize = { delta ->
                        variablesHeight = (variablesHeight + delta).coerceIn(60.dp, 400.dp)
                    },
                    second = { pane ->
                        Column(pane) {
                            PaneLabel("Variables", "JSON, sent alongside the operation")
                            CodeEditor(
                                value = body.graphqlVariables,
                                onValueChange = { text ->
                                    state.edit { it.copy(body = it.body.copy(graphqlVariables = text)) }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentType = "application/json",
                                placeholder = "{ }",
                            )
                        }
                    },
                    first = { pane ->
                        Column(pane) {
                            PaneLabel("Operation", "query, mutation or subscription")
                            CodeEditor(
                                value = body.text,
                                onValueChange = { text ->
                                    state.edit { it.copy(body = it.body.copy(text = text)) }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentType = body.contentType,
                                // Completion comes from the language now — see
                                // `ui/editor/GraphQlLanguage.kt`, which carries
                                // its own source.
                                placeholder = "query { }",
                            )
                        }
                    },
                )
            }

            // `weight`, not `fillMaxSize`: in a column the latter asks for the
            // whole height including the strip above it, so the editor ran off
            // the bottom instead of taking what was left.
            else -> CodeEditor(
                value = body.text,
                onValueChange = { text -> state.edit { it.copy(body = it.body.copy(text = text)) } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentType = body.contentType,
                placeholder = "Request body — sent as-is",
            )
        }
    }
}

/**
 * A caption over one half of the GraphQL editor.
 *
 * Two editors stacked with no labels are two boxes; the hint is what says which
 * one takes JSON, which is the question anybody looking at them has.
 */
@Composable
private fun PaneLabel(title: String, hint: String) {
    Row(
        Modifier.fillMaxWidth().height(22.dp).background(P.head).bottomBorder(P.line2)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PzText(title, color = P.dim, style = Typo.micro, family = P.Ui, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        PzText(hint, color = P.faint, style = Typo.micro, family = P.Ui, maxLines = 1)
    }
}

/** A body format offered as a chip, and the content type it sends. */
private class BodyFormat(val label: String, val mime: String)

private val STANDARD_FORMATS = listOf(
    BodyFormat("Text", "text/plain"),
    BodyFormat("JSON", "application/json"),
    BodyFormat("XML", "application/xml"),
    BodyFormat("HTML", "text/html"),
    BodyFormat("JS", "application/javascript"),
    BodyFormat("TS", "application/typescript"),
    BodyFormat("GRAPHQL", "application/graphql"),
)

/**
 * The chips to show for [contentType].
 *
 * A content type the standard chips don't cover — `application/vnd.api+json`,
 * say, loaded from a saved request — gets a chip of its own rather than being
 * silently replaced by the nearest match.
 */
private fun bodyFormats(contentType: String): List<BodyFormat> {
    val matched = formatOf(contentType)
    return if (STANDARD_FORMATS.any { it.label == matched.label }) STANDARD_FORMATS
    else STANDARD_FORMATS + matched
}

/** Which chip [contentType] corresponds to; matched loosely, like the formatters do. */
private fun formatOf(contentType: String): BodyFormat {
    val type = contentType.lowercase()
    return when {
        type.isBlank() || type.startsWith("text/plain") -> STANDARD_FORMATS.first()
        type.contains("graphql") -> STANDARD_FORMATS.first { it.label == "GRAPHQL" }
        type.contains("json") && !type.contains("vnd.") -> STANDARD_FORMATS.first { it.label == "JSON" }
        type.contains("html") -> STANDARD_FORMATS.first { it.label == "HTML" }
        type.contains("xml") && !type.contains("vnd.") -> STANDARD_FORMATS.first { it.label == "XML" }
        type.contains("typescript") -> STANDARD_FORMATS.first { it.label == "TS" }
        type.contains("javascript") || type.contains("ecmascript") ->
            STANDARD_FORMATS.first { it.label == "JS" }

        else -> BodyFormat(contentType, contentType)
    }
}

/** Stand-in for the editor when the body is a file on disk. */
@Composable
private fun FileBody(path: String) {
    val file = runCatching { Path.of(path) }.getOrNull()
    val size = file?.let { runCatching { Files.size(it) }.getOrNull() }
    val missing = file != null && !Files.isRegularFile(file)

    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PzText(file?.fileName?.toString() ?: path, color = P.text, style = Typo.body)
            PzText(
                when {
                    missing -> "File is missing — it will be sent as an empty body."
                    size != null -> "${bytesStr(size)} · sent from disk at request time"
                    else -> "sent from disk at request time"
                },
                color = if (missing) P.err else P.faint,
                style = Typo.caption, family = P.Ui,
            )
            PzText(path, color = P.faint, style = Typo.caption)
        }
    }
}

/** Picks a file; small text files are loaded to edit, anything else attached. */
private fun attach(state: ApiClientState, window: ComposeWindow): String? {
    val path = FileDialogs.openAny(window, "Attach request body") ?: return null
    val size = runCatching { Files.size(path) }.getOrNull() ?: return "Could not read that file."
    val guessed = runCatching { Files.probeContentType(path) }.getOrNull().orEmpty()

    val text = if (size <= EDITABLE_LIMIT) {
        runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString()
        }.getOrNull()
    } else {
        null
    }

    state.edit { request ->
        val contentType = request.body.contentType.ifBlank { guessed }
        if (text != null) {
            // Editable text: load it, so the point of picking a file (tweaking
            // it before sending) is available.
            request.copy(body = request.body.copy(contentType = contentType, text = text, filePath = ""))
        } else {
            request.copy(body = request.body.copy(contentType = contentType, text = "", filePath = path.toString()))
        }
    }
    return null
}

/** Method, URL, and the Send/Cancel button. */
@Composable
private fun RequestBar(
    state: ApiClientState,
    service: ProxyService,
    collections: CollectionStore,
    selectedPath: Path?,
    onNotice: (String?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(P.panel).bottomBorder(P.line)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Method and URL are two controls, not one: each carries its own frame
        // and the row's 8dp spacing separates them. They used to share a frame
        // with the method drawn undecorated inside it, which left no room
        // between the chevron and the first character of the URL.
        MethodPicker(
            value = state.request.method,
            options = HTTP_METHODS,
        ) { method -> state.edit { it.copy(method = method) } }

        TextInput(
            value = state.request.url,
            // The URL is authoritative: typing a query re-reads the params
            // table from it, so the two never drift apart.
            onValueChange = { url -> state.edit { it.copy(url = url, params = paramsOf(url)) } },
            placeholder = "https://api.example.com/v1/users",
            modifier = Modifier.weight(1f),
        )


        if (state.busy) {
            GhostButton("Cancel") { state.cancel() }
        } else {
            PrimaryButton("Send", enabled = state.request.url.isNotBlank()) {
                onNotice(null)
                state.send()
            }
        }

        // No dirty marker on the button — the request's own tab already carries
        // one, and the button's enabled state says the same thing again.
        GhostButton("Save", enabled = state.dirty || state.openPath == null) {
            onNotice(save(state, collections, selectedPath))
        }
    }
}

/** Saves over the open file, or into the selected collection when new. */
private fun save(state: ApiClientState, collections: CollectionStore, selectedPath: Path?): String? {
    val existing = state.openPath
    if (existing != null) {
        return collections.save(existing, state.request)
            .fold({ state.markSaved(existing); null }, { "Save failed: ${it.message}" })
    }

    // A project is not somewhere a request can go, so selecting one is not
    // enough — the store answers which collection, if any, the selection means.
    val folder = collections.collectionFor(selectedPath)
        ?: return "Select a collection on the left to save into."
    return collections.pathFor(folder, state.request.name).fold(
        { target ->
            collections.save(target, state.request)
                .fold({ state.markSaved(target); null }, { "Save failed: ${it.message}" })
        },
        { it.message },
    )
}

