package org.bittrace.ui.layouts.settings

import org.bittrace.ui.components.FormField
import org.bittrace.ui.components.FormStyle
import org.bittrace.ui.components.FormTextField
import org.bittrace.ui.layouts.inspector.components.DEFAULT_COLUMN_KEYS
import org.bittrace.ui.layouts.inspector.components.columnCatalog
import androidx.compose.foundation.layout.RowScope
import org.bittrace.ui.Typo
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bittrace.data.SettingsStore
import org.bittrace.data.horizontalLayout
import org.bittrace.plugin.ThemeManager
import org.bittrace.proxy.CertInfo
import org.bittrace.proxy.CertTrust
import org.bittrace.proxy.CertificateAuthority
import org.bittrace.proxy.ProxyService
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.CheckBoxRow
import org.bittrace.ui.components.Dot
import org.bittrace.ui.components.Dropdown
import org.bittrace.ui.components.GhostButton
import org.bittrace.ui.P
import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.PrimaryButton
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.Segment
import org.bittrace.ui.components.SegmentedToggle
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.border1
import org.bittrace.api.HTTP_VERSIONS
import org.bittrace.api.URL_ENCODINGS
import org.bittrace.api.httpVersionLabel
import org.bittrace.api.urlEncodingLabel
import androidx.compose.ui.text.font.FontWeight
import org.bittrace.ui.components.CheckBox
import org.bittrace.ui.leftBorder
import org.bittrace.ui.rightBorder
import org.bittrace.ui.topBorder
import org.jetbrains.jewel.ui.component.SimpleListItem

/** The settings categories, in sidebar order. */
private enum class Category(val label: String, val title: String) {
    PROXY("Proxy", "Proxy"),
    API("Request Forge", "Request Forge defaults"),
    GIT("Git", "Git"),
    APPEARANCE("Appearance", "Appearance"),
    COLUMNS("Flow table", "Flow table columns"),
}

/**
 * Settings screen — a category sidebar on the left, the selected category
 * filling the rest of the window on the right.
 *
 * The detail pane scrolls on its own (with a scrollbar), so a long category
 * like the column checklist stays reachable no matter the window height, and
 * each category starts at the top when selected.
 */
@Composable
fun SettingsView(settings: SettingsStore, service: ProxyService, themeManager: ThemeManager) {
    var active by remember { mutableStateOf(Category.PROXY) }

    Row(Modifier.fillMaxSize().background(P.bg)) {
        CategoryList(active) { active = it }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            // A fresh scroll position per category — switching categories should
            // land at the top, not wherever the previous one was scrolled to.
            val scroll = remember(active) { ScrollState(0) }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                when (active) {
                    Category.PROXY -> ProxyPane(settings, service)
                    Category.API -> ApiClientPane(settings)
                    Category.GIT -> GitPane(settings)
                    Category.APPEARANCE -> AppearancePane(settings, themeManager)
                    Category.COLUMNS -> ColumnsPane(settings)
                }
                QuipFooter()
            }
            VScrollbar(
                scroll,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/**
 * Sign-off lines for the settings pane, in the spirit of the home greeting:
 * the leading half is rendered dim, the trailing half in the accent.
 */
private val Quips = listOf(
    "With great settings " to "comes great responsibility.",
    "These are not the defaults " to "you're looking for.",
    "Set phasers to " to "verbose.",
    "I am inevitable, " to "said the config file.",
    "Do. Or do not. " to "There is no partial apply.",
    "Resistance is futile, " to "your preferences have been assimilated.",
    "I can do this all day, " to "and so can the proxy.",
    "The Force is strong " to "with this configuration.",
    "Highly illogical, " to "yet you saved it anyway.",
    "Avengers, " to "reassemble the flow table.",
)

/** The quip that closes out the settings pane, picked once per screen entry. */
/**
 * Who commits, and what authenticates a push.
 *
 * The identity is optional here because git already has one: leaving these
 * blank falls back to `~/.gitconfig`, which is where most people have already
 * set it. Blank in both places refuses the commit rather than inventing an
 * identity — a history attributed to `bittrace@localhost` is worse than one
 * that would not start.
 */
@Composable
private fun GitPane(settings: SettingsStore) = Pane(Category.GIT.title) {
    val current = settings.settings

    GroupLabel("Commit identity")
    SettingTextField("Name", current.gitAuthorName, "from ~/.gitconfig") { value ->
        settings.update { it.copy(gitAuthorName = value) }
    }
    SettingTextField("Email", current.gitAuthorEmail, "from ~/.gitconfig") { value ->
        settings.update { it.copy(gitAuthorEmail = value) }
    }
    Hint("Leave both blank to use whatever git is already configured with on this machine.")

    GroupLabel("Remote access")
    SettingTextField("Token", current.gitToken, "personal access token") { value ->
        settings.update { it.copy(gitToken = value) }
    }
    Hint(
        "Used for HTTPS remotes. SSH remotes use your ~/.ssh keys and agent instead, " +
            "and need nothing here.",
    )
    Hint(
        "Stored in plain text in settings.json, like everything else on this screen — " +
            "so scope it to the repositories you actually push to.",
    )
}

@Composable
private fun QuipFooter() {
    val quip = remember { Quips.random() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        PzText(quip.first, color = P.faint, style = Typo.caption, family = P.Ui)
        PzText(quip.second, color = P.accent, style = Typo.caption, family = P.Ui)
    }
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------

@Composable
private fun CategoryList(active: Category, onSelect: (Category) -> Unit) {
    val scroll = remember { ScrollState(0) }
    Box(Modifier.width(172.dp).fillMaxHeight().background(P.panel).rightBorder(P.line)) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            // The shared strip, so this and the pane header opposite it are the
            // same height by construction rather than by matched padding.
            PaneHeader(title = "Settings")
            Spacer(Modifier.height(4.dp))
            Category.entries.forEach { category ->
                val on = category == active
                // Jewel's list item carries the selection background, hover and
                // keyboard affordances; the accent rule down the left edge is
                // ours, and marks the active pane the way the rail does.
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (on) Modifier.leftBorder(P.accent, 2.dp) else Modifier)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onSelect(category) }
                        // Inset the label without moving the accent rule or the
                        // selection fill, both of which belong to the edge.
                        .padding(start = 6.dp),
                ) {
                    // Jewel's list item is presentational — it has no onClick —
                    // so the row keeps the click and Jewel supplies the
                    // selection background. The label is ours: the text overload
                    // draws at Jewel's 13sp default, a size this app uses for
                    // headings rather than for list rows, so it read as
                    // oversized next to every other list in the app.
                    SimpleListItem(
                        selected = on,
                        modifier = Modifier.fillMaxWidth(),
                        height = ROW_HEIGHT,
                    ) {
                        CellText(
                            category.label,
                            color = if (on) P.text else P.dim,
                            style = Typo.label, family = P.Ui,
                        )
                    }
                }
            }
        }
        VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** Category rows, sized like every other list row in the app rather than like a heading. */
private val ROW_HEIGHT = 24.dp

// ---------------------------------------------------------------------------
// Panes
// ---------------------------------------------------------------------------

/** Category shell: a title strip above content that spans the pane's width. */
@Composable
private fun Pane(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        PaneHeader(title = title.uppercase())
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Hint(text: String) = PzText(text, color = P.faint, style = Typo.caption, family = P.Ui)

@Composable
private fun ProxyPane(settings: SettingsStore, service: ProxyService) = Pane(Category.PROXY.title) {
    var portText by remember(settings.settings.proxyPort) {
        mutableStateOf(settings.settings.proxyPort.toString())
    }
    val valid = portText.toIntOrNull()?.let { it in 1..65535 } == true

    SettingField("Listen port") {
        TextInput(
            value = portText,
            onValueChange = { s -> if (s.all { it.isDigit() } && s.length <= 5) portText = s },
            modifier = Modifier.width(100.dp),
        )
        Spacer(Modifier.width(10.dp))
        PrimaryButton("Apply & restart", enabled = valid) { applyPort(portText, settings, service) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (service.isRunning) P.ok else P.err, 6)
        Spacer(Modifier.width(7.dp))
        val status = if (service.isRunning) {
            "Proxy running on port ${settings.settings.proxyPort} · pid ${service.pid ?: "—"}"
        } else {
            "Proxy stopped"
        }
        PzText(status, color = P.faint, style = Typo.label)
    }

    Hint("Applying restarts the proxy on the new port. Point your client or system proxy at 127.0.0.1:<port>.")

    Spacer(Modifier.height(2.dp))
    CertificateSection()
}

/**
 * Root-certificate status and install.
 *
 * Reading the trust store and running the platform installer both block, so
 * they happen on the IO dispatcher; the pane shows "checking…" until the first
 * result lands and re-checks itself after an install.
 */
@Composable
private fun CertificateSection() {
    var info by remember { mutableStateOf<CertInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkCount by remember { mutableStateOf(0) }

    LaunchedEffect(checkCount) {
        info = null
        info = withContext(Dispatchers.IO) { CertificateAuthority.inspect() }
    }

    val current = info
    val (dotColor, label) = when (current?.trust) {
        null -> P.faint to "checking…"
        CertTrust.TRUSTED -> P.ok to "trusted by this machine"
        CertTrust.NOT_TRUSTED -> P.warn to "not trusted — HTTPS flows will fail"
        CertTrust.MISSING -> P.faint to "not generated yet"
        CertTrust.UNKNOWN -> P.dim to "present, trust state unknown"
    }

    SettingField("Root certificate") {
        Dot(dotColor, 6)
        Spacer(Modifier.width(7.dp))
        PzText(label, color = P.text, style = Typo.label)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(FormStyle.Roomy.column))
        val installable = current?.trust == CertTrust.NOT_TRUSTED && CertificateAuthority.canInstall && !busy
        // No tooltip here: Jewel's Tooltip is stable, but it exposes Compose
        // Foundation's experimental TooltipPlacement, and this migration takes
        // no experimental API. The hint below the row carries the explanation.
        PrimaryButton(
            if (busy) "Installing…" else "Install certificate",
            enabled = installable,
        ) {
            busy = true
            message = null
            thread(isDaemon = true, name = "cert-install") {
                val result = CertificateAuthority.install()
                message = result.exceptionOrNull()?.message ?: "Certificate installed."
                busy = false
                checkCount++
            }
        }
        Spacer(Modifier.width(8.dp))
        GhostButton("Re-check", enabled = !busy) { checkCount++ }
    }

    message?.let { Hint(it) }
    current?.detail?.let { Hint(it) }
    current?.path?.let { Hint("File: $it") }
    current?.expires?.let { Hint("Expires $it") }
    current?.sha256?.let { Hint("SHA-256 $it") }

    Hint(
        "Installing adds the proxy's CA to your user trust store so HTTPS traffic can be decrypted — " +
            "your OS will ask you to confirm. Remove it when you are done debugging.",
    )
}

/**
 * What a request is sent with when it does not say otherwise.
 *
 * Every value here is what the app did before any of it was settable, so the
 * pane starts out changing nothing. A request overrides any of these in its own
 * Settings tab; changing one here moves every request that never did.
 */
@Composable
private fun ApiClientPane(settings: SettingsStore) = Pane(Category.API.title) {
    val current = settings.settings

    GroupLabel("Connection")
    SettingField("HTTP version") {
        SegmentedToggle(
            segments = HTTP_VERSIONS.map { Segment(it, httpVersionLabel(it)) },
            selected = current.apiHttpVersion,
        ) { picked -> settings.update { it.copy(apiHttpVersion = picked) } }
    }
    SettingField("Timeout") {
        NumberField(current.apiTimeoutMs.toString()) { typed ->
            typed.toLongOrNull()?.let { value -> settings.update { it.copy(apiTimeoutMs = value) } }
        }
        Spacer(Modifier.width(8.dp))
        Hint("milliseconds, for the whole exchange")
    }

    GroupLabel("Redirects")
    SettingField("Follow redirects") {
        CheckBox(current.apiFollowRedirects) { on -> settings.update { it.copy(apiFollowRedirects = on) } }
    }
    Hint("Off by default: the Forge should show what the endpoint answered, not where it pointed.")
    SettingField("Maximum") {
        NumberField(current.apiMaxRedirects.toString(), enabled = current.apiFollowRedirects) { typed ->
            typed.toIntOrNull()?.let { value -> settings.update { it.copy(apiMaxRedirects = value) } }
        }
    }

    GroupLabel("URL")
    SettingField("Query encoding") {
        SegmentedToggle(
            segments = URL_ENCODINGS.map { Segment(it, urlEncodingLabel(it)) },
            selected = current.apiUrlEncoding,
        ) { picked -> settings.update { it.copy(apiUrlEncoding = picked) } }
    }
    Hint("How the params table is escaped. A typed URL is always sent as written.")
}

/** A heading inside a pane, for the groups a settings pane divides into. */
/** Wide enough for a token, which is the longest thing anybody types here. */
private val FIELD_WIDTH = 320.dp

@Composable
private fun GroupLabel(text: String) =
    PzText(text, color = P.text, style = Typo.label, family = P.Ui, weight = FontWeight.SemiBold)

/** A bound text row at this screen's label width and field width. */
@Composable
private fun SettingTextField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) =
    FormTextField(label, value, placeholder, FormStyle.Roomy, width = FIELD_WIDTH, onChange = onChange)

/** A settings row at this screen's label width. */
@Composable
private fun SettingField(label: String, control: @Composable RowScope.() -> Unit) =
    FormField(label, FormStyle.Roomy, control = control)

/** A digits-only field. Ignores anything that is not a number rather than clearing. */
@Composable
private fun NumberField(value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    TextInput(
        value = value,
        onValueChange = { typed -> if (typed.all { it.isDigit() } && typed.isNotBlank()) onChange(typed) },
        enabled = enabled,
        modifier = Modifier.width(110.dp),
    )
}

@Composable
private fun AppearancePane(settings: SettingsStore, themeManager: ThemeManager) =
    Pane(Category.APPEARANCE.title) {
        SettingField("Theme") { ThemeToggle(settings, themeManager) }
        SettingField("Inspector") { InspectorDockToggle(settings) }
        Hint(
            "Horizontal puts the inspector beside the flow table, with request above response. " +
                "Vertical puts it underneath, side by side. Applies immediately.",
        )
    }

@Composable
private fun ColumnsPane(settings: SettingsStore) = Pane(Category.COLUMNS.title) {
    Hint("Columns are opt-in. Applies next time you open Traffic.")
    ColumnChecklist(settings)
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

@Composable
private fun ThemeToggle(settings: SettingsStore, themeManager: ThemeManager) {
    // Themes come from the plugin registry — bundled Precision plus any external
    // theme plugins appear here automatically. A dropdown rather than a row of
    // buttons, since installing plugins can make that list arbitrarily long.
    val active = themeManager.activeId(settings.settings.theme)
    val themes = themeManager.availableThemes
    val current = themes.firstOrNull { it.id == active }?.name ?: active

    Dropdown(
        value = current,
        options = themes.map { it.name },
        width = 200.dp,
    ) { name ->
        themes.firstOrNull { it.name == name }?.let { themeManager.setActiveThemeId(it.id, settings) }
    }
}

/** Where the inspector docks — and, with it, how its two panes are arranged. */
@Composable
private fun InspectorDockToggle(settings: SettingsStore) {
    // Named for how the panes sit rather than where the inspector goes:
    // horizontal puts table and inspector side by side, vertical stacks them.
    // The stored ids stay "bottom"/"right" so existing settings load; see
    // Settings.horizontalLayout, the one place that translates them.
    val horizontal = settings.settings.horizontalLayout
    SegmentedToggle(
        segments = listOf(Segment("bottom", "Vertical"), Segment("right", "Horizontal")),
        selected = if (horizontal) "right" else "bottom",
    ) { id -> settings.update { s -> s.copy(inspectorDock = id) } }
}

/**
 * Every available column with a checkbox. Columns are opt-in, so the stored
 * list is exactly what the table shows — except that unchecking the last one is
 * refused, since a table with no columns has no way back.
 */
@Composable
private fun ColumnChecklist(settings: SettingsStore) {
    val catalog = remember { columnCatalog() }
    val enabled = settings.settings.tableColumns.ifEmpty { DEFAULT_COLUMN_KEYS }.toSet()

    Column(Modifier.fillMaxWidth().border1(P.line)) {
        catalog.forEachIndexed { i, col ->
            val on = col.key in enabled
            Row(
                Modifier.fillMaxWidth()
                    .then(if (i > 0) Modifier.topBorder(P.line2) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBoxRow(col.label, checked = on) {
                    toggleColumn(settings, catalog.map { it.key }, enabled, col.key)
                }
                Spacer(Modifier.weight(1f))
                if (col.key in DEFAULT_COLUMN_KEYS) PzText("default", color = P.faint, style = Typo.micro, family = P.Ui)
            }
        }
    }
}

/** Flips one column, keeping the stored list in catalog order. */
private fun toggleColumn(
    settings: SettingsStore,
    catalogKeys: List<String>,
    enabled: Set<String>,
    key: String,
) {
    val on = key in enabled
    if (on && enabled.size == 1) return // never leave the table columnless
    val next = if (on) enabled - key else enabled + key
    settings.update { s -> s.copy(tableColumns = catalogKeys.filter { it in next }) }
}

/** Persists the new port, then restarts the sidecar off the UI thread. */
private fun applyPort(text: String, settings: SettingsStore, service: ProxyService) {
    val port = text.toIntOrNull() ?: return
    if (port !in 1..65535) return
    settings.update { it.copy(proxyPort = port) }
    thread(isDaemon = true, name = "proxy-restart") {
        runCatching {
            service.stop()
            Thread.sleep(300) // let the OS release the old port before rebinding
            service.start(port)
        }.onFailure { System.err.println("[proxy] restart failed: $it") }
    }
}
