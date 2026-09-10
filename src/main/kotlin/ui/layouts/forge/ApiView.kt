package org.bittrace.ui.layouts.forge

import org.bittrace.ui.bytesStr
import org.bittrace.ui.dayClockOf
import org.bittrace.ui.layouts.forge.components.AuthTab
import org.bittrace.ui.layouts.forge.components.CollectionTree
import org.bittrace.ui.layouts.forge.components.CommitDialog
import org.bittrace.ui.layouts.forge.components.GitPrompts
import org.bittrace.ui.layouts.forge.components.KvEditor
import org.bittrace.ui.layouts.forge.components.MethodPicker
import org.bittrace.ui.layouts.forge.components.NewBranchDialog
import org.bittrace.ui.layouts.forge.components.PendingChangesDialog
import org.bittrace.ui.layouts.forge.components.PendingGit
import org.bittrace.ui.layouts.forge.components.ProjectToolbar
import org.bittrace.ui.layouts.forge.components.PublishDialog
import org.bittrace.ui.layouts.forge.components.RequestHistoryTab
import org.bittrace.ui.layouts.forge.components.RequestSettingsTab
import org.bittrace.ui.layouts.forge.components.TreeBadge
import org.bittrace.ui.layouts.forge.components.TreeMenuItem
import org.bittrace.ui.layouts.forge.components.UnsavedChangesDialog
import org.bittrace.ui.layouts.forge.components.VariablesPane
import org.bittrace.ui.layouts.forge.components.gitItems
import org.bittrace.ui.layouts.forge.components.methodColor
import org.bittrace.ui.layouts.inspector.Inspector
import org.bittrace.api.walk
import org.bittrace.ui.components.DirtyDot
import org.bittrace.ui.components.EmptyState
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
import org.bittrace.api.ProjectVariables
import org.bittrace.api.VariablesNode
import org.bittrace.api.VariablesTab
import org.bittrace.api.EditorTab
import org.bittrace.api.CollectionStore
import org.bittrace.api.oauth.OAuthService
import org.bittrace.api.ImportReport
import org.bittrace.data.HTTP_METHODS
import org.bittrace.api.HistoryEntry
import org.bittrace.api.HistoryStore
import org.bittrace.api.KeyValue
import org.bittrace.api.paramsOf
import org.bittrace.api.resolve
import org.bittrace.api.urlWithParams
import org.bittrace.data.SessionStore
import org.bittrace.git.GitStore
import org.bittrace.data.horizontalLayout
import org.bittrace.data.SettingsStore
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.Format
import org.bittrace.ui.components.FormatPicker
import org.bittrace.ui.components.CodeEditor
import org.bittrace.ui.components.EDITABLE_LIMIT
import org.bittrace.ui.FileDialogs
import org.bittrace.ui.components.GhostButton
import org.bittrace.ui.P
import org.bittrace.ui.components.TabContent
import org.bittrace.ui.components.TabContentSwitcher
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.SplitPane
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.components.VerticalSplitter
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
    /** Per-project repository state, for the tree's branch chips and git menu. */
    git: GitStore,
    /** Plugins contributing items to the collections tree's context menu. */
    collectionActions: List<CollectionActionPlugin>,
    /** Owner for the file picker; the API menu and rail both route through App. */
    window: ComposeWindow,
    /**
     * Where the notice strip's messages also go.
     *
     * The strip holds one line and the next action overwrites it, so a failed
     * save or a rename that was refused left no record at all once you clicked
     * anything else. Everything the strip says now goes to the log too, at the
     * level the site chooses — failures as errors, confirmations as info, so the
     * log reads as what happened rather than only what went wrong.
     */
    onLog: (String, String) -> Unit = { _, _ -> },
) {
    // One service for the view, holding the token store the client state owns.
    // Built here rather than in the state because it needs the proxy's port and
    // whether it is running — the same two things a send reads.
    val oauth = remember(state, service) {
        OAuthService(state.tokens, proxyPort = { settings.settings.proxyPort }, viaProxy = { service.isRunning })
    }

    var notice by remember { mutableStateOf<String?>(null) }

    /**
     * Says something once on the strip and once in the log.
     *
     * A single funnel rather than a logging call beside each assignment: there
     * are sixteen of those, and the one that gets forgotten is always the
     * failure path nobody exercises.
     */
    fun report(message: String?, level: String = "error") {
        notice = message
        if (message != null) onLog(level, message)
    }

    // The tree renders this as a strip of its own, which is a state and not an
    // event — so it is logged from an effect rather than from composition,
    // where it would be written again on every frame.
    LaunchedEffect(collections.error) {
        collections.error?.let { onLog("error", "collections could not be read: $it") }
    }
    // The tab waiting on an answer to "save before closing?".
    var pendingClose by remember { mutableStateOf<EditorTab?>(null) }
    var selectedPath by remember { mutableStateOf<Path?>(null) }

    // An unsaved draft resolves its `{{name}}`s against the project it would be
    // saved into, which is this selection — the same path `save` reads below.
    // Kept in step here rather than passed to `send`, because the menu bar and
    // the Auth tab start sends and authorisations of their own and would each
    // have had to remember to supply it.
    LaunchedEffect(selectedPath) { state.draftHome = selectedPath }
    // Which git dialog is open, and on which project. Nullable paths rather than
    // booleans, so a dialog cannot outlive knowing what it applies to.
    var branchFor by remember { mutableStateOf<Path?>(null) }
    var commitFor by remember { mutableStateOf<Path?>(null) }
    var publishFor by remember { mutableStateOf<Path?>(null) }
    var commitChanges by remember { mutableStateOf<List<org.bittrace.git.FileChange>>(emptyList()) }
    // The operation waiting on unsaved tabs being saved.
    var pendingGit by remember { mutableStateOf<PendingGit?>(null) }
    // The tree column has its own scope further down, declared where the menu
    // items are built. Git work outlives that lambda, so it gets one here.
    val viewScope = rememberCoroutineScope()

    // Walking the collections folder touches the disk, so it happens off the
    // UI thread — on first entry and whenever the view is returned to.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            collections.reload()
            history.load()
        }
        // Only after the reload: `adoptLegacyLayout` may still move whole
        // collections, and a repo created before that runs would record the
        // move as a delete-and-add rather than as the layout it found.
        git.adopt(collections.tree.map { it.path })
    }

    /**
     * Refuses an operation that would rewrite files a tab is still editing.
     *
     * Letting git overwrite them and reloading afterwards would discard the
     * edits silently, and a save-then-continue is one click either way — so the
     * refusal costs nothing and the alternative costs work.
     */
    fun guarded(project: Path, action: String, run: () -> Unit) {
        val dirty = state.dirtyTabsUnder(project)
        if (dirty.isEmpty()) run() else pendingGit = PendingGit(project, action, dirty.map { it.title }, run)
    }

    /** Puts open tabs back in step with what git just wrote. */
    fun reconcile(project: Path, branch: String) {
        viewScope.launch {
            withContext(Dispatchers.IO) { collections.reload() }
            val report = state.reconcile(project, branch) { path ->
                collections.tree.walk()
                    .filterIsInstance<RequestNode>()
                    .firstOrNull { it.path == path }
                    ?.let(collections::read)
                    ?: Result.failure(IllegalStateException("no longer in the tree"))
            }
            if (selectedPath?.let { !Files.exists(it) } == true) selectedPath = null
            val detail = report.summary()
            // Only the reconcile half goes to the log. The checkout itself is
            // already logged by the git store, and two "Switched to main" lines
            // from two sources is one line of news and one of noise.
            notice = if (detail.isBlank()) "Switched to $branch." else "Switched to $branch — $detail."
            if (detail.isNotBlank()) onLog("info", "open tabs after $branch: $detail")
        }
    }

    /**
     * The selected node, for the toolbar.
     *
     * Variables rows are excluded on purpose. The toolbar enables Delete for
     * anything non-null, and a variables file is not the user's to delete —
     * it is regenerated the moment the project is read again. Reporting the row
     * as "nothing selected" is what the toolbar already did before the walk was
     * shared, when it reached variables by not looking for them.
     */
    val selectedNode = remember(collections.tree, selectedPath) {
        selectedPath?.let { path ->
            collections.tree.walk().firstOrNull { it.path == path && it !is VariablesNode }
        }
    }

    /**
     * Makes whatever belongs one level under [node].
     *
     * The same rule the context menu follows, so the toolbar button and the
     * menu entry cannot disagree about what "new" means where you are standing.
     */
    fun createUnder(node: Node?) {
        viewScope.launch {
            val made = withContext(Dispatchers.IO) {
                when (node) {
                    is ProjectNode -> collections.createNamedCollection(node.path)
                    is CollectionNode -> collections.createNamedRequest(node.path)
                    is RequestNode -> node.path.parent?.let { collections.createNamedRequest(it) }
                    else -> collections.createNamedProject()
                }
            }
            made?.onSuccess { path -> selectedPath = path; report("Created ${path.fileName}", "info") }
                ?.onFailure { report("Could not create that: ${it.message}") }
        }
    }

    fun deleteNode(node: Node) {
        collections.delete(node)
            .onSuccess {
                if (state.openPath == node.path) state.open(state.request, null)
                if (selectedPath == node.path) selectedPath = null
                report("Moved '${node.name}' to the .trash folder.", "info")
            }
            .onFailure { report(it.message ?: "Delete failed.") }
    }

    /** Switches to [branch], guarded and then reconciled. */
    fun switchTo(project: Path, branch: String) {
        guarded(project, "Switching branch") {
            git.run(project, "Checkout") { checkout(project, branch).map { "Switched to ${it.branch}." } }
            reconcile(project, branch.substringAfter("origin/", branch))
        }
    }

    val prompts = remember(state, collections) {
        GitPrompts(
            newBranch = { project -> branchFor = project },
            commit = { project -> commitFor = project },
            publish = { project -> publishFor = project },
            guarded = ::guarded,
        )
    }

    val treeWidth = settings.settings.apiTreeWidthDp.dp
    val responseWidth = settings.settings.apiResponseWidthDp.dp
    val responseHeight = settings.settings.apiResponseHeightDp.dp
    // The same setting the traffic inspector follows: horizontal sits the panes
    // side by side, vertical stacks them.
    val horizontal = settings.settings.horizontalLayout

    Row(Modifier.fillMaxSize().background(P.bg)) {
        // --- projects / history ---
        TabContentSwitcher(
            tabs = listOf(
                TabContent("Projects") {
                    Column(Modifier.fillMaxSize()) {
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
                                report(message, "info")
                            }
                        }
                    }

                    ProjectToolbar(
                        selected = selectedNode,
                        collections = collections,
                        git = git,
                        prompts = prompts,
                        onNew = { node -> createUnder(node) },
                        onDelete = { node -> deleteNode(node) },
                    )

                    CollectionTree(
                        nodes = collections.tree,
                        selected = selectedPath,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onOpen = { node ->
                            collections.read(node)
                                .onSuccess { state.open(it, node.path); selectedPath = node.path; notice = null }
                                .onFailure { report("Could not read ${node.name}: ${it.message}") }
                        },
                        onOpenVariables = { node ->
                            collections.projectOf(node.path)?.let { project ->
                                state.openVariables(project)
                                selectedPath = node.path
                                notice = null
                            }
                        },
                        onSelectFolder = { selectedPath = it.path },
                        onBadge = { node ->
                            // Only projects carry one, and only once the repo is known.
                            (node as? ProjectNode)?.let { project ->
                                val state = git.stateOf(project.path)
                                state.label?.let { label ->
                                    TreeBadge(
                                        value = label,
                                        // A detached HEAD has no branch to be selected,
                                        // so it is a readout until one is created.
                                        options = if (state.detached) emptyList() else state.branches,
                                        dot = !state.clean,
                                        onSelect = { picked -> switchTo(project.path, picked) },
                                    )
                                }
                            }
                        },
                        onMenuItems = { node ->
                            // Before `targetOf`, which has no kind for this and no
                            // business gaining one: a fourth `CollectionTargetKind`
                            // would be a source-incompatible change for every plugin
                            // with an exhaustive `when` over it.
                            if (node is VariablesNode) return@CollectionTree emptyList()
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
                            val host = createItems(node, collections, scope, ::report) { made ->
                                selectedPath = made
                            } + archiveItems(node, collections, window, scope, ::report) +
                                gitItems(node, git, prompts)
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
                                .onFailure { report(it.message ?: "Rename failed.") }
                        },
                        onDelete = { node -> deleteNode(node) },
                    )
                        collections.error?.let {
                            Box(Modifier.fillMaxWidth().padding(10.dp)) {
                                PzText(it, color = P.err, style = Typo.caption, family = P.Ui)
                            }
                        }
                    }
                },
                TabContent("History") {
                    HistoryList(
                        entries = history.entries,
                        modifier = Modifier.fillMaxSize(),
                        onOpen = { entry ->
                            // Opened as a fresh draft: history is a record of what
                            // was sent, not a file to save back over.
                            state.open(entry.request, null)
                            selectedPath = null
                            notice = null
                        },
                        onRemove = { history.remove(it) },
                    )
                },
            ),
            modifier = Modifier.width(treeWidth).fillMaxHeight().background(P.panel).rightBorder(P.line),
        )

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
            // The one branch the compiler cannot force, because the pane below
            // was unconditional before there was anything else to show. Made
            // exhaustive over the sealed tab so a third kind fails here rather
            // than silently rendering a request builder over it.
            val open = state.active
            if (open is VariablesTab) {
                VariablesPane(open) { report(saveVariables(open)) }
                return@Column
            }

            RequestBar(state, service, collections, selectedPath, ::report)

            // --- request builder ---
            val builder: @Composable (Modifier) -> Unit = { paneModifier ->
                Column(paneModifier) {
                    // One list, so the strip and the content cannot disagree.
                    // The switch used to be a `when` on the tab name with the
                    // body editor as its `else`, which meant a tab added after
                    // it silently rendered the body — a hazard its own comment
                    // recorded rather than removed. Pairing each name with what
                    // it draws makes an unnamed tab impossible to write.
                    val pages = listOf(
                        TabContent("Params") {
                            KvTab(state.request.params, "param") { rows ->
                                // Encoded the way this request will be sent, so
                                // the URL above shows what actually goes out —
                                // switching the encoding rewrites it in place.
                                val encoding = state.request.settings.resolve(settings.settings).urlEncoding
                                state.edit { it.copy(params = rows, url = urlWithParams(it.url, rows, encoding)) }
                            }
                        },
                        TabContent("Headers") {
                            KvTab(state.request.headers, "header") { rows ->
                                state.edit { it.copy(headers = rows) }
                            }
                        },
                        TabContent("Cookies") {
                            KvTab(state.request.cookies, "cookie") { rows ->
                                state.edit { it.copy(cookies = rows) }
                            }
                        },
                        TabContent("Body") { BodyTab(state, window, ::report) },
                        TabContent("Auth") { AuthTab(state, oauth) },
                        TabContent("Settings") { RequestSettingsTab(state, settings.settings) },
                        TabContent("History") { RequestHistoryTab(state, collections, git) },
                    )

                    // The builder is a pane like any other, so it gets a pane
                    // header: title first, then its sections. The send result
                    // belongs to the response and lives in that header instead.
                    TabContentSwitcher(pages, Modifier.weight(1f), title = "Request")

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
                report(failure)
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

    branchFor?.let { project ->
        NewBranchDialog(
            current = git.stateOf(project).branch,
            onDismiss = { branchFor = null },
            onCreate = { name ->
                branchFor = null
                guarded(project, "Switching branch") {
                    git.run(project, "New branch") {
                        createBranch(project, name).map { "Created and switched to ${it.name}." }
                    }
                    reconcile(project, name)
                }
            },
        )
    }

    commitFor?.let { project ->
        CommitDialog(
            project = project.fileName.toString(),
            changes = commitChanges,
            onDismiss = { commitFor = null },
            onCommit = { paths, message ->
                commitFor = null
                git.run(project, "Commit") {
                    commit(project, paths, message).map { "Committed ${paths.size} file(s) as ${it.short}." }
                }
            },
        )
    }

    // Loaded when the dialog opens rather than held live: a working-tree walk
    // on every frame the tree is visible is not a thing to do for a dialog that
    // is usually shut.
    LaunchedEffect(commitFor) {
        val project = commitFor ?: return@LaunchedEffect
        commitChanges = git.service.status(project).getOrDefault(org.bittrace.git.GitStatus(emptyList())).changes
    }

    publishFor?.let { project ->
        PublishDialog(
            project = project.fileName.toString(),
            branch = git.stateOf(project).branch,
            onDismiss = { publishFor = null },
            onPublish = { url ->
                publishFor = null
                git.run(project, "Publish") {
                    // Adding the remote and pushing are one act from where the
                    // user is standing, so the remote is rolled back if the push
                    // behind it fails. Otherwise a mistyped URL leaves the
                    // project pointing at nothing, and — because the menu offers
                    // Publish only while there is no remote — no way to correct
                    // it from the app at all.
                    addRemote(project, url).fold(
                        onSuccess = { remote ->
                            push(project, setUpstream = true)
                                .map { "Published to ${remote.url}." }
                                .onFailure {
                                    removeRemote(project) }
                        },
                        onFailure = {
                            Result.failure(it) },
                    )
                }
            },
        )
    }

    pendingGit?.let { pending ->
        PendingChangesDialog(
            action = pending.action,
            names = pending.names,
            onDismiss = { pendingGit = null },
            onSaveAll = {
                val failure = saveAll(state, collections, pending.project)
                report(failure)
                // Only proceed if every save landed. Running the git operation
                // after a partial save is exactly the overwrite this dialog
                // exists to prevent.
                if (failure == null) {
                    pendingGit = null
                    pending.run()
                }
            },
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
    /** Message first, then level, so the common `report(text)` reads plainly. */
    onNotice: (String, String) -> Unit,
    onCreated: (Path) -> Unit,
): List<TreeMenuItem> = when (node) {
    is ProjectNode -> listOf(
        TreeMenuItem("New collection") {
            scope.launch {
                withContext(Dispatchers.IO) { collections.createNamedCollection(node.path) }
                    .onSuccess { made -> onCreated(made); onNotice("Created ${made.fileName} in ${node.name}", "info") }
                    .onFailure { onNotice("Could not create a collection: ${it.message}", "error") }
            }
        },
    )

    is CollectionNode -> listOf(
        TreeMenuItem("New request") {
            scope.launch {
                withContext(Dispatchers.IO) { collections.createNamedRequest(node.path) }
                    .onSuccess { made -> onCreated(made); onNotice("Created ${made.fileName} in ${node.name}", "info") }
                    .onFailure { onNotice("Could not create a request: ${it.message}", "error") }
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
    onNotice: (String, String) -> Unit,
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
                        onNotice(
                            "Exported ${node.name} — $files requests to ${target.fileName}",
                            "info",
                        )
                        // After the notice, so a file manager that takes a
                        // moment to appear does not delay the confirmation.
                        FileDialogs.revealInFolder(target)
                    }
                    .onFailure { onNotice("Could not export ${node.name}: ${it.message}", "error") }
            }
        },
        TreeMenuItem("Import…") {
            val archive = FileDialogs.openZip(window) ?: return@TreeMenuItem
            scope.launch {
                withContext(Dispatchers.IO) { collections.importInto(node, archive) }
                    .onSuccess { report -> onNotice(importNotice(report, kind), "info") }
                    .onFailure { onNotice("Could not import ${archive.fileName}: ${it.message}", "error") }
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
                "$skipped skipped, ${report.files} requests written"

        else -> "Imported ${report.written.joinToString(", ")} — " +
            "${report.files} requests"
    }
}

/**
 * The tree's own node, as the plugin API describes it.
 *
 * The project and collection a node sits in are read off its path rather than
 * looked up in the store: the hierarchy is exactly three levels deep, so the
 * two names are the parent folders and nothing has to be searched for them.
 */
private fun targetOf(node: Node): CollectionTarget = when (node) {
    // Unreachable: `onMenuItems` returns before calling this for a variables
    // row. Spelled out rather than left to an `else`, so adding another node
    // kind fails here instead of quietly becoming a project target.
    is VariablesNode -> error("A variables row contributes no plugin actions.")

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
private fun RequestTabs(state: ApiClientState, onClose: (EditorTab) -> Unit) {
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
                            // The method for a request; the syntax it teaches for
                            // the variables table, which has no method to show.
                            val lead = (tab as? RequestTab)?.request?.method
                            PzText(
                                lead ?: "{{ }}",
                                color = lead?.let(::methodColor) ?: P.key,
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
                                DirtyDot()
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
                EmptyState("Nothing sent yet")
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
private fun BodyTab(state: ApiClientState, window: ComposeWindow, onNotice: (String?, String) -> Unit) {
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
                    onNotice(null, "error")
                } else {
                    onNotice(attach(state, window), "error")
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
    onNotice: (String?, String) -> Unit,
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
                onNotice(null, "error")
                state.send()
            }
        }

        // No dirty marker on the button — the request's own tab already carries
        // one, and the button's enabled state says the same thing again.
        GhostButton("Save", enabled = state.dirty || state.openPath == null) {
            onNotice(save(state, collections, selectedPath), "error")
        }
    }
}

/** Saves over the open file, or into the selected collection when new. */
/**
 * Writes every unsaved tab under [project], stopping at the first failure.
 *
 * Stopping matters: the caller runs a checkout once this returns null, and a
 * checkout after a half-finished save is precisely the overwrite the guard
 * exists to prevent. A partial save leaves the rest dirty, so the dialog simply
 * comes back with fewer names in it.
 */
private fun saveAll(state: ApiClientState, collections: CollectionStore, project: Path): String? {
    state.dirtyTabsUnder(project).forEach { tab ->
        val failure = when (tab) {
            is VariablesTab -> saveVariables(tab)
            is RequestTab -> {
                val path = tab.openPath
                    ?: return "${tab.title} has no file yet — save it into a collection first."
                collections.save(path, tab.request).exceptionOrNull()?.message
            }
        }
        if (failure != null) return "Could not save ${tab.title}: $failure"
        tab.dirty = false
    }
    return null
}

/**
 * Writes a variables table.
 *
 * Deliberately not routed through `CollectionStore.save`, which is shaped for a
 * request and now refuses any path that is not one — a project's variables are
 * a different file with different rules, and pretending otherwise is how a
 * placeholder ends up written over something that matters.
 */
private fun saveVariables(tab: VariablesTab): String? =
    runCatching { ProjectVariables.write(tab.project, tab.rows) }
        .fold({ tab.dirty = false; null }, { it.message ?: "Save failed." })

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

