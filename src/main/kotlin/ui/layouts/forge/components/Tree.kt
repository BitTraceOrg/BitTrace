package org.bittrace.ui.layouts.forge.components

import org.bittrace.ui.components.dropdownHeight
import androidx.compose.foundation.layout.heightIn
import org.bittrace.ui.components.DirtyDot
import org.bittrace.ui.components.EmptyState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
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
import org.bittrace.api.VariablesNode
import org.bittrace.ui.components.CellText
import org.bittrace.ui.components.Dropdown
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.components.VScrollbar
import org.bittrace.ui.leftBorder
import org.bittrace.ui.revealed
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.OutlineColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.PainterHint
import org.jetbrains.jewel.ui.painter.hints.Stroke

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
    onOpenVariables: (VariablesNode) -> Unit = {},
    onRename: (Node, String) -> Unit = { _, _ -> },
    onDelete: (Node) -> Unit = {},
    /**
     * Items contributed by plugins, asked for each time a menu opens. The tree
     * does not know what they do or who supplied them — it renders a label and
     * calls a lambda, which is what keeps this file free of the plugin API.
     */
    onMenuItems: (Node) -> List<TreeMenuItem> = { emptyList() },
    /** What to show at the right of a project row, if anything. */
    onBadge: (Node) -> TreeBadge? = { null },
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
        // Armed only while a rename is open, because it cannot tell what it is
        // hearing. `detectTapGestures` takes its first down with
        // `requireUnconsumed = false`, so this fires for *every* click in the
        // tree — a row, a combo box, an overflow button — not just one that
        // landed on the background, whatever the layout suggests. Left running,
        // it dropped focus on every click, which shut the branch picker on the
        // frame it opened. With no rename in progress there is nothing here to
        // do, so the cheapest fix is also the correct one.
        modifier.pointerInput(editing) {
            if (editing != null) detectTapGestures { focusManager.clearFocus() }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (nodes.isEmpty()) {
                EmptyState("No projects yet")
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
                            is VariablesNode -> onOpenVariables(node)
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
                    badge = onBadge(line.node),
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
/**
 * One entry in a row's context menu.
 *
 * @param startsGroup draws a rule above this item. The menu is assembled from
 *   several sources that arrive as one flat list — creating things, archiving
 *   them, git, then whatever plugins contribute — and without a divider they
 *   read as one long undifferentiated run where Export sits next to Pull.
 *   Whoever supplies the items knows where the seams are; the tree does not.
 */
class TreeMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val startsGroup: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The column holding a request's method.
 *
 * Wide enough for the longest tag [methodTag] produces, so the requests in a
 * folder start their names at the same place whatever verb they use.
 */
/**
 * The method column.
 *
 * Wide enough for the tags actually seen — `PATCH` is the longest of the common
 * ones — rather than for `PROPPATCH`, which `methodTag` shortens anyway. The
 * column stays fixed rather than sizing to its content so request names share
 * one left edge; the point is a list you can scan down, and ragged names cost
 * more than the few pixels a fixed column spends.
 */
private val METHOD_WIDTH = 29.dp

/** Between the method and the name. Small: they read as one label, not two. */
private val NAME_GAP = 6.dp

/**
 * The height every row's leading column occupies, whatever is in it.
 *
 * Without it a folder row is as tall as its 16dp icon and a request row only as
 * tall as its 10sp method text, so the tree came out unevenly spaced and the
 * method sat high against the name beside it. Fixing the slot makes every row
 * the same height and centres what sits in it.
 */
private val ICON_SLOT = 16.dp

/** Above and below a row's content. Part of the row's height, so named once. */
private val ROW_PADDING = 4.dp

/**
 * How tall every row is, whether or not it currently has a badge.
 *
 * A project row grows a branch picker once git has finished reading the
 * repository, and that picker is taller than the 16dp icon slot beside it — so
 * without a floor here the whole tree stepped down a few pixels the moment the
 * read landed, which is the one frame the user is most likely to be looking at
 * it. Reserving the height is the same answer the `More` button below already
 * uses for the same problem, and it is derived from the picker's own metrics
 * rather than guessed, so the two cannot drift apart.
 */
private val rowHeight: Dp
    @Composable get() = maxOf(ICON_SLOT, dropdownHeight) + ROW_PADDING * 2

/** Where the first guide sits: the centre of a top-level row's chevron. */
private val GUIDE_START = 16.dp

/** One indent, so a guide lands on the chevron of the level it belongs to. */
private val GUIDE_STEP = 14.dp

/**
 * A choice at the right of a row: what it is set to, and what else it could be.
 *
 * Deliberately not git-shaped. The tree renders a value and a list of strings
 * and calls a lambda, and knows no more about what it is showing than it does
 * about who supplied the context-menu items above it.
 *
 * @param dot a small mark before the control — there is unsaved work behind it.
 */
class TreeBadge(
    val value: String,
    val options: List<String> = emptyList(),
    val dot: Boolean = false,
    val onSelect: (String) -> Unit = {},
)

/**
 * The badge, always laid out when the row has one.
 *
 * Never hidden and revealed on hover, unlike the `More` button beside it: this
 * is what the row is *telling* you, and a value that appeared only under the
 * pointer would mean scrubbing the tree to find out where you are.
 *
 * A combo box rather than a label that opens something. Picking from a list is
 * what this control does, so it should look like the app's other list pickers
 * and behave like them — one click to open, one to choose — instead of a chip
 * that turns out to be a button that turns out to open a dialog.
 */
@Composable
private fun Badge(badge: TreeBadge) {
    Row(
        Modifier.padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge.dot) {
            DirtyDot()
            Spacer(Modifier.width(4.dp))
        }
        // Shown whenever there is anything to show, including a lone value.
        // Hiding it at one option was wrong: a project that has only `main` then
        // renders a plain label, so the control people are told to use is
        // invisible in exactly the case where every new project starts.
        if (badge.options.isNotEmpty()) {
            // Undecorated, and narrow. A bordered combo on every project row
            // turns a tree into a stack of form fields: three projects meant
            // three boxes of chrome competing with the names beside them, and
            // the thing being browsed came second to the thing being set. At
            // rest this is the branch name and a chevron; Jewel's own hover and
            // pressed states are what say it can be clicked.
            //
            // The focus ring is switched off here and nowhere else. It does not
            // come from the combo's own colours — `ComboBoxColors.Undecorated`
            // has no border at all — but from the theme's global focus outline,
            // so the only way to drop it for one control is to hand that control
            // a palette with the outline cleared. Worth doing exactly here: the
            // row already draws a selection highlight, and clicking the picker
            // put a second box inside the first.
            val colors = JewelTheme.globalColors
            CompositionLocalProvider(
                LocalGlobalColors provides GlobalColors(
                    borders = colors.borders,
                    outlines = OutlineColors(
                        focused = Color.Transparent,
                        focusedWarning = colors.outlines.focusedWarning,
                        focusedError = colors.outlines.focusedError,
                        warning = colors.outlines.warning,
                        error = colors.outlines.error,
                    ),
                    text = colors.text,
                    panelBackground = colors.panelBackground,
                    toolwindowBackground = Color.Unspecified
                ),
            ) {
                Dropdown(
                    value = badge.value,
                    options = badge.options,
                    width = BADGE_WIDTH,
                    bordered = false,
                    onSelect = badge.onSelect,
                )
            }
        } else {
            // Nothing to pick from at all — a detached HEAD, or a repo whose
            // state has not been read yet. A readout, not a dead control.
            PzText(badge.value, color = P.dim, style = Typo.micro, family = P.Ui, softWrap = false)
        }
    }
}

/**
 * Enough for a short branch name and its chevron.
 *
 * Deliberately mean: the row's own name is what the tree is for, and every
 * pixel here is taken from it. A long branch name truncates, which is the right
 * trade — you can see the whole list the moment you open it.
 */
private val BADGE_WIDTH = 92.dp

private fun flatten(nodes: List<Node>, expanded: Set<Path>, depth: Int = 0): List<Line> =
    nodes.flatMap { node ->
        val self = listOf(Line(node, depth))
        if (node !is FolderNode || node.path !in expanded) {
            self
        } else {
            // A project's variables sit above its collections rather than among
            // them. Emitted here rather than carried in `children`, so that
            // everything walking the tree for requests keeps meaning what it
            // says instead of filtering this row back out.
            val leading = if (node is ProjectNode) listOf(Line(node.variables, depth + 1)) else emptyList()
            self + leading + flatten(node.children, expanded, depth + 1)
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
    badge: TreeBadge?,
) {
    val folder = line.node is FolderNode
    // Read here rather than inside `drawBehind`: a draw scope is not a
    // composition, so a palette read in there would not resubscribe on a theme
    // change and the guides would keep the old theme's colour until something
    // else redrew them.
    // `line`, not `line2`. In the dark palette line2 is gray(5) — *darker* than
    // the gray(7) used for an ordinary divider — so guides drawn with it all but
    // vanished against the panel. A structural hairline wants divider weight,
    // and reading it as a role keeps a theme plugin in control of both themes.
    val guide = P.line
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
            .heightIn(if (line.node is ProjectNode) 20.dp else 15.dp)
            .background(if (selected) P.sel else Color.Transparent)
            .then(if (selected) Modifier.leftBorder(P.accent, 2.dp) else Modifier)
            .hoverable(interaction)
            // One guide per level above this row, drawn behind everything and
            // full height, so consecutive rows join into a continuous line. It
            // goes before the padding below: that padding is the indent, and
            // drawing after it would leave nothing to measure from.
            .drawBehind {
                val step = GUIDE_STEP.toPx()
                val first = GUIDE_START.toPx()
                repeat(line.depth) { level ->
                    val x = first + level * step
                    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1f)
                }
            }
            .padding(
                start = (10 + line.depth * 14).dp,
                end = 10.dp,
                top = ROW_PADDING,
                bottom = ROW_PADDING,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tap handler covers the label and nothing else.
        //
        // It used to sit on the whole row, which broke every control at the
        // right-hand end: `detectTapGestures` takes its first down with
        // `requireUnconsumed = false`, so it fires even when a child has already
        // handled the press. Clicking the branch combo therefore also ran the
        // row's own tap, and that calls `clearFocus()` — closing the popup on
        // the frame it opened, and toggling the folder on the way past.
        Row(
            Modifier.weight(1f)
                .pointerHoverIcon(PointerIcon.Hand)
                // Tap gestures rather than `clickable`, so a double-tap can start
                // a rename without the first tap also toggling the folder.
                .pointerInput(line.node.path, editing) {
                    if (!editing) {
                        // A variables row has no name of its own to change, so a
                        // double-tap there is just a tap.
                        val renamable = line.node !is VariablesNode
                        detectTapGestures(
                            onTap = { onClick() },
                            onDoubleTap = { if (renamable) onStartRename() },
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chevron, then the node's own icon. A request has no chevron, so its
            // icon sits where a folder's would — which is what keeps names on one
            // left edge whatever depth they are at.
            Box(Modifier.width(12.dp).height(ICON_SLOT), contentAlignment = Alignment.Center) {
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
                if (line.node is ProjectNode)
                    Icon(
                        key = AllIconsKeys.Toolwindows.ToolWindowProject,
                        contentDescription = "Project",
                        tint = P.accent,
                    )
                else
                    Icon(
                        key = AllIconsKeys.Toolwindows.ToolWindowProject,
                        contentDescription = "Collection",
                        tint = P.warn,
                    )
            } else if (line.node is VariablesNode) {
                Icon(
                    key = AllIconsKeys.Debugger.VariablesTab,
                    contentDescription = "Variables",
                    tint = P.key,
                )
            } else {
                val method = (line.node as? RequestNode)?.method.orEmpty()
                PzText(
                    methodTag(method),
                    color = methodColor(method),
                    style = Typo.micro, family = P.Ui, weight = FontWeight.SemiBold,
                    softWrap = false,
                )
            }
            Spacer(Modifier.width(NAME_GAP))

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
                                Key.Enter, Key.NumPadEnter -> {
                                    onCommitRename(); true
                                }

                                Key.Escape -> {
                                    onCancelRename(); true
                                }

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
            }
        }

        // Outside the tap area, and hidden while renaming: the field takes the
        // whole row then, and a combo beside it would be a second place for the
        // keyboard to go.
        if (!editing) {
            badge?.let { Badge(it) }

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
                        // Not offered on a variables row: the store refuses both,
                        // and a verb that always fails is worse than an absent one.
                        if (line.node !is VariablesNode) {
                            selectableItem(
                                selected = false,
                                onClick = { menuOpen = false; onStartRename() },
                            ) {
                                PzText("Rename", color = P.text, style = Typo.label, family = P.Ui)
                            }
                            selectableItem(selected = false, onClick = { menuOpen = false; onDelete() }) {
                                PzText("Delete", color = P.text, style = Typo.label, family = P.Ui)
                            }
                        }
                        // Asked for on open, not on every recomposition of the
                        // row: a menu that is shut costs nothing, and an item's
                        // enablement is then read at the moment it is shown.
                        val extras = menuItems()
                        if (extras.isNotEmpty()) {
                            separator()
                            extras.forEachIndexed { index, item ->
                                // Not on the first: a rule immediately under the
                                // one already drawn above the extras would be two
                                // lines with nothing between them.
                                if (item.startsGroup && index > 0) separator()
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