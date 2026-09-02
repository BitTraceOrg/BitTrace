package org.bittrace.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.separator
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import org.bittrace.api.CollectionNode
import org.bittrace.api.FolderNode
import org.bittrace.api.Node
import org.bittrace.api.ProjectNode
import org.bittrace.api.RequestNode
import org.bittrace.ui.CellText
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.TextInput
import org.bittrace.ui.VScrollbar
import org.bittrace.ui.leftBorder
import org.bittrace.ui.revealed
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The collections tree — projects and collections that expand, requests that open.
 *
 * A flatten pass into a plain scrolling column rather than a lazy list:
 * collections run to hundreds of entries, not thousands, and flattening keeps
 * indentation and expansion trivially correct.
 *
 * Expansion is keyed by [Path] rather than by index, so reloading from disk
 * leaves open folders open.
 */
@Composable
fun CollectionTree(
    nodes: List<Node>,
    selected: Path?,
    modifier: Modifier = Modifier,
    onOpen: (RequestNode) -> Unit,
    /** A project or a collection was clicked; the caller decides what selecting one means. */
    onSelectFolder: (FolderNode) -> Unit = {},
    onRename: (Node, String) -> Unit = { _, _ -> },
    onDelete: (Node) -> Unit = {},
    /**
     * Items contributed by plugins, asked for each time a menu opens. The tree
     * does not know what they do or who supplied them — it renders a label and
     * calls a lambda, which is what keeps this file free of the plugin API.
     */
    onMenuItems: (Node) -> List<TreeMenuItem> = { emptyList() },
) {
    val expanded = remember { mutableStateMapOf<Path, Unit>() }
    val scroll = rememberScrollState()
    // Clicking a plain surface does not move focus on its own, so the rename
    // field's commit-on-blur never fired for a click that landed anywhere but
    // another focusable control — the editor just stayed open. Taking focus
    // away explicitly is what turns "clicked elsewhere" into a blur.
    val focusManager = LocalFocusManager.current
    // Which row is being renamed, and the text typed so far. Keyed by path, so
    // a reload mid-edit cannot retarget the rename at a different node.
    var editing by remember { mutableStateOf<Path?>(null) }
    var draft by remember { mutableStateOf("") }

    Box(
        // Empty space below the last row. A row that was tapped consumes the
        // press itself, so this only ever sees a click on the tree's own
        // background — which is exactly the click that should end a rename.
        modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (nodes.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    PzText("No projects yet", color = P.faint, style = Typo.label, family = P.Ui)
                }
            }
            flatten(nodes, expanded.keys).forEach { line ->
                TreeRow(
                    line = line,
                    selected = line.node.path == selected,
                    isOpen = line.node.path in expanded,
                    editing = editing == line.node.path,
                    draft = draft,
                    onDraft = { draft = it },
                    onClick = {
                        // A click on another row is also a click away from the
                        // one being renamed.
                        focusManager.clearFocus()
                        when (val node = line.node) {
                            is FolderNode -> {
                                if (node.path in expanded) expanded.remove(node.path)
                                else expanded[node.path] = Unit
                                onSelectFolder(node)
                            }

                            is RequestNode -> onOpen(node)
                        }
                    },
                    onStartRename = {
                        editing = line.node.path
                        draft = line.node.name
                    },
                    onCommitRename = {
                        val name = draft.trim()
                        editing = null
                        if (name.isNotEmpty() && name != line.node.name) onRename(line.node, name)
                    },
                    onCancelRename = { editing = null },
                    onDelete = { onDelete(line.node) },
                    menuItems = { onMenuItems(line.node) },
                )
            }
        }
        VScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private class Line(val node: Node, val depth: Int)

/**
 * One menu item the tree was handed rather than one it owns.
 *
 * A label and a lambda, deliberately: the tree renders the collections menu but
 * has no business knowing that plugins exist, so whoever wired it up does the
 * translating. Rename and Delete stay the tree's own — an item that could
 * remove them could make a collection uneditable.
 */
class TreeMenuItem(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The column holding a request's method.
 *
 * Wide enough for the longest tag [methodTag] produces, so the requests in a
 * folder start their names at the same place whatever verb they use.
 */
private val METHOD_WIDTH = 34.dp

private fun flatten(nodes: List<Node>, expanded: Set<Path>, depth: Int = 0): List<Line> =
    nodes.flatMap { node ->
        val self = listOf(Line(node, depth))
        if (node is FolderNode && node.path in expanded) {
            self + flatten(node.children, expanded, depth + 1)
        } else {
            self
        }
    }

@Composable
private fun TreeRow(
    line: Line,
    selected: Boolean,
    isOpen: Boolean,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onClick: () -> Unit,
    onStartRename: () -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onDelete: () -> Unit,
    menuItems: () -> List<TreeMenuItem>,
) {
    val folder = line.node is FolderNode
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()

    // The field only exists while renaming, so focus is requested when it appears.
    LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }

    // Whether the rename field has ever held focus. Without this, "lost focus"
    // and "has not been given focus yet" are the same state, and the field
    // reports unfocused the moment its modifier attaches — before the effect
    // above has run — so the commit-on-blur below fired instantly and closed
    // the editor on the same frame it opened. Reset per rename.
    var everFocused by remember(editing) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) P.sel else Color.Transparent)
            .then(if (selected) Modifier.leftBorder(P.accent, 2.dp) else Modifier)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            // Tap gestures rather than `clickable`, so a double-tap can start a
            // rename without the first tap also toggling the folder.
            .pointerInput(line.node.path, editing) {
                if (!editing) {
                    detectTapGestures(onTap = { onClick() }, onDoubleTap = { onStartRename() })
                }
            }
            .padding(start = (10 + line.depth * 14).dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron, then the node's own icon. A request has no chevron, so its
        // icon sits where a folder's would — which is what keeps names on one
        // left edge whatever depth they are at.
        Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
            if (folder) {
                Icon(
                    key = if (isOpen) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                    contentDescription = if (isOpen) "Collapse" else "Expand",
                    tint = P.dim,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        // A request is labelled with its method rather than a file icon: every
        // row in a collection is the same kind of file, so the icon said nothing
        // the folder it sits in had not already said, whereas the method is the
        // one thing you scan a collection for. Folders keep their icon, and both
        // sit in a column of the same width so names share one left edge.
        if (folder) {
            // Its natural width, not the method column's: a folder icon padded
            // out to the width of `PATCH` left a gap wide enough to read as a
            // missing glyph. Folders align with folders, requests with
            // requests, and a folder's own requests are a depth deeper anyway,
            // so nothing that sits together comes out ragged.
            //
            // A project and a collection both expand, so the chevron says
            // nothing about which one a row is; the glyph is what distinguishes
            // them, and it has to work at a glance because the two sit one
            // indent apart in the same column.
            if (line.node is ProjectNode) ProjectOutline(P.accent) else FolderOutline(P.warn)
        } else {
            val method = (line.node as? RequestNode)?.method.orEmpty()
            Box(Modifier.width(METHOD_WIDTH), contentAlignment = Alignment.CenterStart) {
                PzText(
                    methodTag(method),
                    color = methodColor(method),
                    style = Typo.micro, family = P.Ui, weight = FontWeight.SemiBold,
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.width(6.dp))

        if (editing) {
            TextInput(
                value = draft,
                onValueChange = onDraft,
                bordered = true,
                modifier = Modifier.weight(1f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { onCommitRename(); true }
                            Key.Escape -> { onCancelRename(); true }
                            else -> false
                        }
                    }
                    // Clicking away is a commit, matching how file managers behave.
                    // Only a real *loss* of focus counts, which is what
                    // [everFocused] distinguishes.
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) everFocused = true
                        else if (everFocused && editing) onCommitRename()
                    },
            )
        } else {
            CellText(
                line.node.name,
                color = if (selected) P.text else if (folder) P.text else P.dim,
                style = Typo.label,
                family = P.Ui,
                // Weight tracks the hierarchy: a project reads as the heading
                // its collections sit under, and a request as an entry in one.
                weight = when (line.node) {
                    is ProjectNode -> FontWeight.SemiBold
                    is CollectionNode -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                modifier = Modifier.weight(1f),
            )
            // One overflow button rather than a row of loose icons: collections
            // and requests take the same actions, so they take the same
            // affordance, and the tree does not grow a new icon per verb.
            // Revealed on hover, or while its own menu is open — otherwise
            // moving the pointer onto the menu would dismiss the button under it.
            var menuOpen by remember { mutableStateOf(false) }
            val showActions = isHovered || menuOpen
            Box {
                // Always laid out, only sometimes visible. Adding the button on
                // hover made the row taller than it was at rest, so every row
                // jumped as the pointer crossed it; keeping the same node in the
                // layout and hiding it means the two heights cannot disagree.
                // `enabled` is what actually gates the click — alpha alone would
                // leave an invisible button that still responds.
                IconActionButton(
                    key = AllIconsKeys.Actions.More,
                    contentDescription = "More actions",
                    enabled = showActions,
                    onClick = { menuOpen = true },
                    modifier = Modifier.revealed(showActions),
                )
                if (menuOpen) {
                    PopupMenu(
                        onDismissRequest = { menuOpen = false; true },
                        horizontalAlignment = Alignment.End,
                    ) {
                        selectableItem(selected = false, onClick = { menuOpen = false; onStartRename() }) {
                            PzText("Rename", color = P.text, style = Typo.label, family = P.Ui)
                        }
                        selectableItem(selected = false, onClick = { menuOpen = false; onDelete() }) {
                            PzText("Delete", color = P.text, style = Typo.label, family = P.Ui)
                        }
                        // Asked for on open, not on every recomposition of the
                        // row: a menu that is shut costs nothing, and an item's
                        // enablement is then read at the moment it is shown.
                        val extras = menuItems()
                        if (extras.isNotEmpty()) {
                            separator()
                            extras.forEach { item ->
                                selectableItem(
                                    selected = false,
                                    enabled = item.enabled,
                                    onClick = { menuOpen = false; item.onClick() },
                                ) {
                                    PzText(
                                        item.label,
                                        color = if (item.enabled) P.text else P.faint,
                                        style = Typo.label, family = P.Ui,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A folder drawn as an outline rather than a filled shape.
 *
 * DESIGN.MD §11 makes every icon stroke-rendered — `no fill, currentColor
 * stroke, round caps and joins` — but the icons Jewel ships are IntelliJ's,
 * which are solid. There is no hollow folder among them, so this one is drawn:
 * six points, closed along the bottom, stroked and never filled.
 */
@Composable
private fun FolderOutline(tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        // The path is authored against a 16-unit box and scaled, so the shape
        // holds if the size ever changes.
        val u = this.size.minDimension / 16f
        val path = GraphicsPath().apply {
            moveTo(1.8f * u, 13.1f * u)
            lineTo(1.8f * u, 3.6f * u)
            lineTo(6.2f * u, 3.6f * u)
            lineTo(7.8f * u, 5.7f * u)
            lineTo(14.2f * u, 5.7f * u)
            lineTo(14.2f * u, 13.1f * u)
            close()
        }
        drawPath(
            path,
            color = tint,
            style = Stroke(width = 1.25f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * A project drawn as a stroked card with a rule below its head.
 *
 * Deliberately not a second folder in another colour — the two rungs have to be
 * told apart by shape, since a tint alone disappears for anyone who cannot
 * separate the two hues, and the rows sit only one indent apart. Authored
 * against the same 16-unit box as [FolderOutline] so the two share a baseline.
 */
@Composable
private fun ProjectOutline(tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 16f
        val stroke = Stroke(width = 1.25f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = GraphicsPath().apply {
            moveTo(2.4f * u, 2.9f * u)
            lineTo(13.6f * u, 2.9f * u)
            lineTo(13.6f * u, 13.1f * u)
            lineTo(2.4f * u, 13.1f * u)
            close()
        }
        drawPath(body, color = tint, style = stroke)
        val rule = GraphicsPath().apply {
            moveTo(2.4f * u, 6.2f * u)
            lineTo(13.6f * u, 6.2f * u)
        }
        drawPath(rule, color = tint, style = stroke)
    }
}
