package org.bittrace.ui

import org.jetbrains.jewel.ui.component.styling.SelectableLazyColumnStyle
import org.jetbrains.jewel.ui.component.styling.PopupContainerStyle
import org.jetbrains.jewel.ui.component.styling.PopupContainerColors
import org.jetbrains.jewel.window.styling.TitleBarMetrics
import org.jetbrains.jewel.intui.window.styling.defaults as titleBarDefaults
import androidx.compose.ui.unit.DpSize
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.ui.component.styling.TabMetrics
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.foundation.theme.ThemeColorPalette
import org.bittrace.plugin.theme.Ramp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bittrace.plugin.theme.Palette
import org.jetbrains.jewel.foundation.BorderColors
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.OutlineColors
import org.jetbrains.jewel.foundation.TextColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.ThemeDefinition
import org.jetbrains.jewel.intui.standalone.styling.Default
import org.jetbrains.jewel.intui.standalone.styling.Editor
import org.jetbrains.jewel.intui.standalone.styling.Outlined
import org.jetbrains.jewel.intui.standalone.styling.Undecorated
import org.jetbrains.jewel.intui.standalone.styling.dark
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.styling.tabStrip
import org.jetbrains.jewel.intui.standalone.styling.windowsAndLinuxDark
import org.jetbrains.jewel.intui.standalone.styling.windowsAndLinuxLight
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.light
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark as titleBarDark
import org.jetbrains.jewel.intui.window.styling.light as titleBarLight
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.ButtonColors
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.CheckboxColors
import org.jetbrains.jewel.ui.component.styling.CheckboxStyle
import org.jetbrains.jewel.ui.component.styling.ChipColors
import org.jetbrains.jewel.ui.component.styling.ChipStyle
import org.jetbrains.jewel.ui.component.styling.ComboBoxColors
import org.jetbrains.jewel.ui.component.styling.ComboBoxStyle
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import org.jetbrains.jewel.ui.component.styling.GroupHeaderColors
import org.jetbrains.jewel.ui.component.styling.GroupHeaderStyle
import org.jetbrains.jewel.ui.component.styling.IconButtonColors
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.ui.component.styling.LinkColors
import org.jetbrains.jewel.ui.component.styling.LinkStyle
import org.jetbrains.jewel.ui.component.styling.MenuColors
import org.jetbrains.jewel.ui.component.styling.MenuItemColors
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.component.styling.RadioButtonColors
import org.jetbrains.jewel.ui.component.styling.RadioButtonStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarColors
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.component.styling.SegmentedControlButtonColors
import org.jetbrains.jewel.ui.component.styling.SegmentedControlButtonStyle
import org.jetbrains.jewel.ui.component.styling.SegmentedControlColors
import org.jetbrains.jewel.ui.component.styling.SegmentedControlStyle
import org.jetbrains.jewel.ui.component.styling.SimpleListItemColors
import org.jetbrains.jewel.ui.component.styling.SimpleListItemStyle
import org.jetbrains.jewel.ui.component.styling.TabColors
import org.jetbrains.jewel.ui.component.styling.TabStyle
import org.jetbrains.jewel.ui.component.styling.TextAreaColors
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle
import org.jetbrains.jewel.ui.component.styling.TextFieldColors
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle
import org.jetbrains.jewel.ui.component.styling.TooltipMetrics
import org.jetbrains.jewel.ui.component.styling.TooltipColors
import org.jetbrains.jewel.ui.component.styling.TooltipStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * Renders BitTrace's [Palette] as a Jewel theme.
 *
 * **Metrics are Int UI's, untouched.** Shape, size and spacing come from Jewel's
 * own defaults — 4dp corners, 24dp rows, 28dp controls, 40dp tabs — because a
 * component's metrics are part of its design, and overriding them is how you get
 * bugs like a tab whose top edge stops responding. Nothing in this file sets a
 * corner size, a padding or a height.
 *
 * **Colours are ours, and only because they have to be.** Int UI bakes its
 * component defaults against the static `IntUiDarkTheme`/`IntUiLightTheme`
 * palettes rather than [ThemeDefinition.colorPalette], so a custom palette in
 * the theme definition does not reach them; each style has to be handed its
 * colours explicitly. They are handed the same *roles* Int UI reads — see the
 * semantic accessors on [Palette], where the index choices live — so this file
 * stays a mapping rather than a second set of design decisions.
 *
 * [P] remains the single source of truth and still serves everything Jewel has
 * no component for (the grid, the editor, the visualisations). This is the one
 * place those colours are also handed to Jewel, so there is only ever one theme
 * to edit.
 */
@Composable
fun BitTraceTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = P.palette
    IntUiTheme(
        theme = palette.themeDefinition(dark),
        styling = palette.componentStyling(dark),
        content = content,
    )
}

@Composable
private fun Palette.themeDefinition(dark: Boolean): ThemeDefinition {
    val colors = GlobalColors(
        borders = BorderColors(normal = line, focused = accent, disabled = line2),
        outlines = OutlineColors(
            focused = accent,
            focusedWarning = warn,
            focusedError = err,
            warning = warn,
            error = err,
        ),
        text = TextColors(
            normal = text,
            selected = text,
            disabled = faint,
            disabledSelected = faint,
            info = dim,
            error = err,
            warning = warn,
        ),
        panelBackground = panel,
        toolwindowBackground = chrome,
    )
    // Inter for chrome, JetBrains Mono for payload (DESIGN.MD §3). Both ship
    // with Jewel, so these come from its own factories rather than from whatever
    // the platform happens to call sans and mono — and `P.Ui` / `P.Mono` read
    // back off these, so there is one answer for each family.
    val ui = JewelTheme.createDefaultTextStyle(fontSize = 13.sp, color = text)
    val mono = JewelTheme.createEditorTextStyle(fontSize = 12.5.sp, color = text)
    return if (dark) {
        JewelTheme.darkThemeDefinition(
            colors = colors,
            palette = colorPalette(),
            defaultTextStyle = ui, editorTextStyle = mono, consoleTextStyle = mono,
            contentColor = text,
        )
    } else {
        JewelTheme.lightThemeDefinition(
            colors = colors,
            palette = colorPalette(),
            defaultTextStyle = ui, editorTextStyle = mono, consoleTextStyle = mono,
            contentColor = text,
        )
    }
}

/**
 * The ramps, as Jewel sees them.
 *
 * Int UI's *component* defaults do not read this, but its **icons** do. An SVG
 * like the checkbox tick carries palette keys rather than literal colours, and
 * Jewel resolves them through [ThemeColorPalette.rawMap] — so leaving that map
 * empty makes every such icon fall back to whatever is baked into the SVG,
 * which is a different palette from the one around it. Jewel says so out loud:
 * `color key Checkbox.Background.Default has invalid value: 'Gray14'`.
 *
 * The keys are `Gray1`…`Gray14`, `Blue1`…, one per shade, which is exactly what
 * a [Ramp] is.
 */
private fun Palette.colorPalette(): ThemeColorPalette =
    ThemeColorPalette(
        gray = gray.toList(),
        blue = blue.toList(),
        green = green.toList(),
        red = red.toList(),
        yellow = yellow.toList(),
        orange = orange.toList(),
        purple = purple.toList(),
        teal = teal.toList(),
        rawMap = buildMap {
            putAll(gray.keyed("Gray"))
            putAll(blue.keyed("Blue"))
            putAll(green.keyed("Green"))
            putAll(red.keyed("Red"))
            putAll(yellow.keyed("Yellow"))
            putAll(orange.keyed("Orange"))
            putAll(purple.keyed("Purple"))
            putAll(teal.keyed("Teal"))
        },
        isIslands = false,
    )

private fun Ramp.toList(): List<Color> = List(size) { this(it + 1) }

/** This ramp as `Name1`…`NameN`, the form Jewel resolves icon colours through. */
private fun Ramp.keyed(name: String): Map<String, Color> =
    List(size) { "$name${it + 1}" to this(it + 1) }.toMap()

/**
 * Each style is overridden twice — once per base. The arguments are identical;
 * what differs is the base the *unlisted* parameters fall back to, above all the
 * icon sets (tick marks, chevrons), which Int UI ships per theme.
 */
@Composable
private fun Palette.componentStyling(dark: Boolean): ComponentStyling {
    val base = if (dark) {
        ComponentStyling.dark(
            checkboxStyle = checkboxStyle(true),
            chipStyle = chipStyle(true),
            comboBoxStyle = comboBoxStyle(true),
            defaultButtonStyle = defaultButtonStyle(true),
            defaultTabStyle = defaultTabStyle(true),
            dividerStyle = dividerStyle(true),
            editorTabStyle = editorTabStyle(true),
            groupHeaderStyle = groupHeaderStyle(true),
            iconButtonStyle = iconButtonStyle(true),
            linkStyle = linkStyle(true),
            menuStyle = menuStyle(true),
            outlinedButtonStyle = outlinedButtonStyle(true),
            radioButtonStyle = radioButtonStyle(true),
            scrollbarStyle = scrollbarStyle(true),
            segmentedControlButtonStyle = segmentedControlButtonStyle(true),
            segmentedControlStyle = segmentedControlStyle(true),
            popupContainerStyle = popupContainerStyle(true),
            selectableLazyColumnStyle = selectableLazyColumnStyle(true),
            simpleListItemStyle = simpleListItemStyle(true),
            textAreaStyle = textAreaStyle(true),
            textFieldStyle = textFieldStyle(true),
            tooltipStyle = tooltipStyle(true),
        )
    } else {
        ComponentStyling.light(
            checkboxStyle = checkboxStyle(false),
            chipStyle = chipStyle(false),
            comboBoxStyle = comboBoxStyle(false),
            defaultButtonStyle = defaultButtonStyle(false),
            defaultTabStyle = defaultTabStyle(false),
            dividerStyle = dividerStyle(false),
            editorTabStyle = editorTabStyle(false),
            groupHeaderStyle = groupHeaderStyle(false),
            iconButtonStyle = iconButtonStyle(false),
            linkStyle = linkStyle(false),
            menuStyle = menuStyle(false),
            outlinedButtonStyle = outlinedButtonStyle(false),
            radioButtonStyle = radioButtonStyle(false),
            scrollbarStyle = scrollbarStyle(false),
            segmentedControlButtonStyle = segmentedControlButtonStyle(false),
            segmentedControlStyle = segmentedControlStyle(false),
            popupContainerStyle = popupContainerStyle(false),
            selectableLazyColumnStyle = selectableLazyColumnStyle(false),
            simpleListItemStyle = simpleListItemStyle(false),
            textAreaStyle = textAreaStyle(false),
            textFieldStyle = textFieldStyle(false),
            tooltipStyle = tooltipStyle(false),
        )
    }
    return base.decoratedWindow(titleBarStyle = titleBarStyle(dark))
}

// --- Buttons ---------------------------------------------------------------

@Composable
private fun Palette.defaultButtonStyle(dark: Boolean): ButtonStyle {
    // The filled button is the accent; its label is whatever reads on the accent.
    val onAccent = if (dark) bg else Color.White
    val fill: Brush = SolidColor(accent)
    val colors = if (dark) {
        ButtonColors.Default.dark(
            background = fill, backgroundDisabled = SolidColor(line),
            backgroundFocused = fill, backgroundPressed = fill, backgroundHovered = fill,
            content = onAccent, contentDisabled = faint,
            contentFocused = onAccent, contentPressed = onAccent, contentHovered = onAccent,
            border = fill, borderDisabled = SolidColor(line),
            borderFocused = SolidColor(text), borderPressed = fill, borderHovered = fill,
        )
    } else {
        ButtonColors.Default.light(
            background = fill, backgroundDisabled = SolidColor(line),
            backgroundFocused = fill, backgroundPressed = fill, backgroundHovered = fill,
            content = onAccent, contentDisabled = faint,
            contentFocused = onAccent, contentPressed = onAccent, contentHovered = onAccent,
            border = fill, borderDisabled = SolidColor(line),
            borderFocused = SolidColor(text), borderPressed = fill, borderHovered = fill,
        )
    }
    return if (dark) ButtonStyle.Default.dark(colors = colors)
    else ButtonStyle.Default.light(colors = colors)
}

@Composable
private fun Palette.outlinedButtonStyle(dark: Boolean): ButtonStyle {
    val clear: Brush = SolidColor(Color.Transparent)
    val wash: Brush = SolidColor(accentFill)
    val colors = if (dark) {
        ButtonColors.Outlined.dark(
            background = clear, backgroundDisabled = clear,
            backgroundFocused = clear, backgroundPressed = wash, backgroundHovered = wash,
            content = text, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            border = SolidColor(line), borderDisabled = SolidColor(line2),
            borderFocused = SolidColor(accent), borderPressed = SolidColor(accent),
            borderHovered = SolidColor(accent),
        )
    } else {
        ButtonColors.Outlined.light(
            background = clear, backgroundDisabled = clear,
            backgroundFocused = clear, backgroundPressed = wash, backgroundHovered = wash,
            content = text, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            border = SolidColor(line), borderDisabled = SolidColor(line2),
            borderFocused = SolidColor(accent), borderPressed = SolidColor(accent),
            borderHovered = SolidColor(accent),
        )
    }
    return if (dark) ButtonStyle.Outlined.dark(colors = colors)
    else ButtonStyle.Outlined.light(colors = colors)
}

// --- Text input ------------------------------------------------------------

@Composable
private fun Palette.textFieldStyle(dark: Boolean): TextFieldStyle {
    // Inputs sit *into* the surface: darker than the panel on dark themes,
    // lighter on light ones — the same inversion the old TextInput drew.
    val colors = if (dark) {
        TextFieldColors.dark(
            background = input, backgroundDisabled = panel,
            content = text, contentDisabled = faint,
            border = line, borderDisabled = line2, borderFocused = accent,
            caret = text, placeholder = faint,
        )
    } else {
        TextFieldColors.light(
            background = input, backgroundDisabled = head,
            content = text, contentDisabled = faint,
            border = line, borderDisabled = line2, borderFocused = accent,
            caret = text, placeholder = faint,
        )
    }
    return if (dark) TextFieldStyle.dark(colors = colors)
    else TextFieldStyle.light(colors = colors)
}

@Composable
private fun Palette.textAreaStyle(dark: Boolean): TextAreaStyle {
    val colors = if (dark) {
        TextAreaColors.dark(
            background = input, backgroundDisabled = panel,
            content = text, contentDisabled = faint,
            border = line, borderDisabled = line2, borderFocused = accent,
            caret = text, placeholder = faint,
        )
    } else {
        TextAreaColors.light(
            background = input, backgroundDisabled = head,
            content = text, contentDisabled = faint,
            border = line, borderDisabled = line2, borderFocused = accent,
            caret = text, placeholder = faint,
        )
    }
    return if (dark) TextAreaStyle.dark(colors = colors)
    else TextAreaStyle.light(colors = colors)
}

// --- Toggles ---------------------------------------------------------------

@Composable
private fun Palette.checkboxStyle(dark: Boolean): CheckboxStyle {
    val colors = if (dark) CheckboxColors.dark(content = text, contentDisabled = faint, contentSelected = text)
    else CheckboxColors.light(content = text, contentDisabled = faint, contentSelected = text)
    return if (dark) CheckboxStyle.dark(colors = colors) else CheckboxStyle.light(colors = colors)
}

@Composable
private fun Palette.radioButtonStyle(dark: Boolean): RadioButtonStyle {
    val colors = if (dark) {
        RadioButtonColors.dark(
            content = text, contentHovered = text, contentDisabled = faint,
            contentSelected = text, contentSelectedHovered = text, contentSelectedDisabled = faint,
        )
    } else {
        RadioButtonColors.light(
            content = text, contentHovered = text, contentDisabled = faint,
            contentSelected = text, contentSelectedHovered = text, contentSelectedDisabled = faint,
        )
    }
    return if (dark) RadioButtonStyle.dark(colors = colors) else RadioButtonStyle.light(colors = colors)
}

@Composable
private fun Palette.segmentedControlStyle(dark: Boolean): SegmentedControlStyle {
    val colors = if (dark) {
        SegmentedControlColors.dark(
            border = SolidColor(line), borderDisabled = SolidColor(line2),
            borderFocused = SolidColor(accent), borderPressed = SolidColor(accent),
            borderHovered = SolidColor(accent),
        )
    } else {
        SegmentedControlColors.light(
            border = SolidColor(line), borderDisabled = SolidColor(line2),
            borderFocused = SolidColor(accent), borderPressed = SolidColor(accent),
            borderHovered = SolidColor(accent),
        )
    }
    return if (dark) SegmentedControlStyle.dark(colors = colors) else SegmentedControlStyle.light(colors = colors)
}

@Composable
private fun Palette.segmentedControlButtonStyle(dark: Boolean): SegmentedControlButtonStyle {
    // The selected segment gets a real surface, not a translucent tint: at 14%
    // alpha the accent wash was invisible on a light theme, which left the
    // focus ring reading as the selection.
    val clear: Brush = SolidColor(Color.Transparent)
    val wash: Brush = SolidColor(accentFill)
    val selected: Brush = SolidColor(sel)
    val colors = if (dark) {
        SegmentedControlButtonColors.dark(
            background = clear, backgroundPressed = wash, backgroundHovered = SolidColor(rowHover),
            backgroundSelected = selected, backgroundSelectedFocused = selected,
            content = dim, contentDisabled = faint,
            border = clear, borderSelected = SolidColor(accent),
            borderSelectedDisabled = SolidColor(line), borderSelectedFocused = SolidColor(accent),
        )
    } else {
        SegmentedControlButtonColors.light(
            background = clear, backgroundPressed = wash, backgroundHovered = SolidColor(rowHover),
            backgroundSelected = selected, backgroundSelectedFocused = selected,
            content = dim, contentDisabled = faint,
            border = clear, borderSelected = SolidColor(accent),
            borderSelectedDisabled = SolidColor(line), borderSelectedFocused = SolidColor(accent),
        )
    }
    return if (dark) SegmentedControlButtonStyle.dark(colors = colors)
    else SegmentedControlButtonStyle.light(colors = colors)
}

// --- Pickers and lists -----------------------------------------------------

/**
 * Format chips — the row above a body that picks how to render it.
 *
 * Int UI's chip is a 28dp pill with a 100% corner radius; these are dense
 * square tags sitting flush in a 24dp strip, so both the shape and the size
 * come from here rather than the default.
 */
@Composable
private fun Palette.chipStyle(dark: Boolean): ChipStyle {
    val idle: Brush = SolidColor(head)
    val wash: Brush = SolidColor(accentFill)
    val on: Brush = SolidColor(accent)
    val colors = if (dark) {
        ChipColors.dark(
            background = idle, backgroundDisabled = idle,
            backgroundFocused = idle, backgroundPressed = wash, backgroundHovered = wash,
            backgroundSelected = on, backgroundSelectedDisabled = SolidColor(line),
            backgroundSelectedFocused = on, backgroundSelectedPressed = on, backgroundSelectedHovered = on,
            content = dim, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            contentSelected = bg, contentSelectedDisabled = faint,
            contentSelectedFocused = bg, contentSelectedPressed = bg, contentSelectedHovered = bg,
            border = line2, borderDisabled = line2,
            borderFocused = accent, borderPressed = accent, borderHovered = line,
            borderSelected = accent, borderSelectedDisabled = line,
            borderSelectedFocused = accent, borderSelectedPressed = accent, borderSelectedHovered = accent,
        )
    } else {
        ChipColors.light(
            background = idle, backgroundDisabled = idle,
            backgroundFocused = idle, backgroundPressed = wash, backgroundHovered = wash,
            backgroundSelected = on, backgroundSelectedDisabled = SolidColor(line),
            backgroundSelectedFocused = on, backgroundSelectedPressed = on, backgroundSelectedHovered = on,
            content = dim, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            contentSelected = Color.White, contentSelectedDisabled = faint,
            contentSelectedFocused = Color.White, contentSelectedPressed = Color.White,
            contentSelectedHovered = Color.White,
            border = line2, borderDisabled = line2,
            borderFocused = accent, borderPressed = accent, borderHovered = line,
            borderSelected = accent, borderSelectedDisabled = line,
            borderSelectedFocused = accent, borderSelectedPressed = accent, borderSelectedHovered = accent,
        )
    }
    return if (dark) ChipStyle.dark(colors = colors)
    else ChipStyle.light(colors = colors)
}

@Composable
private fun Palette.comboBoxStyle(dark: Boolean): ComboBoxStyle {
    val colors = if (dark) {
        ComboBoxColors.Default.dark(
            background = input, backgroundDisabled = panel,
            backgroundFocused = input, backgroundPressed = input, backgroundHovered = input,
            nonEditableBackground = input,
            content = text, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            border = line, borderDisabled = line2, borderFocused = accent,
            borderPressed = accent, borderHovered = line,
        )
    } else {
        ComboBoxColors.Default.light(
            background = input, backgroundDisabled = head,
            backgroundFocused = input, backgroundPressed = input, backgroundHovered = input,
            nonEditableBackground = input,
            content = text, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            border = line, borderDisabled = line2, borderFocused = accent,
            borderPressed = accent, borderHovered = line,
        )
    }
    return if (dark) ComboBoxStyle.Default.dark(colors = colors)
    else ComboBoxStyle.Default.light(colors = colors)
}

/**
 * The borderless combo box, for a picker that shares a frame with the control
 * beside it — the API client's method + URL bar is one box holding two fields.
 *
 * Not part of [ComponentStyling]: Jewel carries one combo box style at a time,
 * so this is handed to the call site that needs it.
 */
@Composable
internal fun undecoratedComboBoxStyle(): ComboBoxStyle {
    val palette = P.palette
    val dark = palette.isDark
    return with(palette) {
        val colors = if (dark) {
            ComboBoxColors.Undecorated.dark(
                background = Color.Transparent, backgroundDisabled = Color.Transparent,
                backgroundFocused = Color.Transparent, backgroundPressed = accentFill,
                backgroundHovered = rowHover, nonEditableBackground = Color.Transparent,
                content = accent, contentDisabled = faint,
                contentFocused = accent, contentPressed = accent, contentHovered = accent,
            )
        } else {
            ComboBoxColors.Undecorated.light(
                background = Color.Transparent, backgroundDisabled = Color.Transparent,
                backgroundFocused = Color.Transparent, backgroundPressed = accentFill,
                backgroundHovered = rowHover, nonEditableBackground = Color.Transparent,
                content = accent, contentDisabled = faint,
                contentFocused = accent, contentPressed = accent, contentHovered = accent,
            )
        }
        if (dark) ComboBoxStyle.Undecorated.dark(colors = colors)
        else ComboBoxStyle.Undecorated.light(colors = colors)
    }
}

/**
 * One row, one set of colours.
 *
 * Every list in the app renders through this: the standalone item, the rows
 * inside a combo box popup, and anything else built on a selectable column.
 * They are shared rather than repeated because Jewel does *not* share them —
 * `SelectableLazyColumnStyle` defaults its item colours to Int UI's own, so a
 * popup left unstyled paints its rows from a different palette than the surface
 * they sit on, and one element ends up a different colour from the rest.
 *
 * **`active` means the list has focus, not that the row is hovered.** Giving it
 * a fill tints every row the moment the popup opens, so an open dropdown shows
 * one blue row and the rest grey — three backgrounds across a list that should
 * read as one surface. Unselected rows are transparent in both states; the only
 * row that differs is the selected one, because a picker that cannot show its
 * current value is not worth the uniformity.
 */
@Composable
private fun Palette.listItemColors(dark: Boolean): SimpleListItemColors =
    if (dark) {
        SimpleListItemColors.dark(
            background = Color.Transparent, backgroundActive = Color.Transparent,
            backgroundSelected = sel, backgroundSelectedActive = sel,
            content = text, contentActive = text,
            contentSelected = text, contentSelectedActive = text,
        )
    } else {
        SimpleListItemColors.light(
            background = Color.Transparent, backgroundActive = Color.Transparent,
            backgroundSelected = sel, backgroundSelectedActive = sel,
            content = text, contentActive = text,
            contentSelected = text, contentSelectedActive = text,
        )
    }

@Composable
private fun Palette.simpleListItemStyle(dark: Boolean): SimpleListItemStyle {
    val colors = listItemColors(dark)
    return if (dark) SimpleListItemStyle.dark(colors = colors) else SimpleListItemStyle.light(colors = colors)
}

/** The rows inside a popup — the same rows, so the same colours. */
@Composable
private fun Palette.selectableLazyColumnStyle(dark: Boolean): SelectableLazyColumnStyle {
    val colors = listItemColors(dark)
    return if (dark) SelectableLazyColumnStyle.dark(itemColors = colors)
    else SelectableLazyColumnStyle.light(itemColors = colors)
}

/**
 * The surface a popup is drawn on: menus, combo box lists, the filter popup.
 *
 * Left unstyled this comes from Int UI's static palette, which is a different
 * grey from ours and gives the popup a border and background that no other
 * surface in the app uses.
 */
@Composable
private fun Palette.popupContainerStyle(dark: Boolean): PopupContainerStyle {
    val colors = if (dark) PopupContainerColors.dark(background = panel, border = line)
    else PopupContainerColors.light(background = panel, border = line)
    return if (dark) PopupContainerStyle.dark(colors = colors) else PopupContainerStyle.light(colors = colors)
}

// --- Chrome ----------------------------------------------------------------

@Composable
private fun Palette.menuStyle(dark: Boolean): MenuStyle {
    val items = if (dark) {
        MenuItemColors.dark(
            background = Color.Transparent, backgroundDisabled = Color.Transparent,
            backgroundFocused = accentFill, backgroundPressed = accentFill, backgroundHovered = accentFill,
            content = text, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            iconTint = dim, iconTintDisabled = faint,
            iconTintFocused = text, iconTintPressed = text, iconTintHovered = text,
            keybindingTint = faint, keybindingTintDisabled = faint,
            keybindingTintFocused = dim, keybindingTintPressed = dim, keybindingTintHovered = dim,
            separator = line2,
        )
    } else {
        MenuItemColors.light(
            background = Color.Transparent, backgroundDisabled = Color.Transparent,
            backgroundFocused = accentFill, backgroundPressed = accentFill, backgroundHovered = accentFill,
            content = text, contentDisabled = faint,
            contentFocused = text, contentPressed = text, contentHovered = text,
            iconTint = dim, iconTintDisabled = faint,
            iconTintFocused = text, iconTintPressed = text, iconTintHovered = text,
            keybindingTint = faint, keybindingTintDisabled = faint,
            keybindingTintFocused = dim, keybindingTintPressed = dim, keybindingTintHovered = dim,
            separator = line2,
        )
    }
    // No drop shadow: the Precision UI separates surfaces with a 1px line.
    val colors = if (dark) MenuColors.dark(background = chrome, border = line, shadow = Color.Transparent, itemColors = items)
    else MenuColors.light(background = chrome, border = line, shadow = Color.Transparent, itemColors = items)
    return if (dark) MenuStyle.dark(colors = colors) else MenuStyle.light(colors = colors)
}

@Composable
private fun Palette.dividerStyle(dark: Boolean): DividerStyle =
    if (dark) DividerStyle.dark(color = line) else DividerStyle.light(color = line)

@Composable
private fun Palette.defaultTabStyle(dark: Boolean): TabStyle {
    val colors = if (dark) {
        TabColors.Default.dark(
            background = Color.Transparent, backgroundHovered = rowHover,
            backgroundPressed = accentFill, backgroundSelected = Color.Transparent,
            backgroundDisabled = Color.Transparent,
            content = dim, contentHovered = text, contentDisabled = faint,
            contentPressed = text, contentSelected = text,
            underline = Color.Transparent, underlineHovered = Color.Transparent,
            underlineDisabled = Color.Transparent, underlinePressed = accent,
            underlineSelected = accent,
        )
    } else {
        TabColors.Default.light(
            background = Color.Transparent, backgroundHovered = rowHover,
            backgroundPressed = accentFill, backgroundSelected = Color.Transparent,
            backgroundDisabled = Color.Transparent,
            content = dim, contentHovered = text, contentDisabled = faint,
            contentPressed = text, contentSelected = text,
            underline = Color.Transparent, underlineHovered = Color.Transparent,
            underlineDisabled = Color.Transparent, underlinePressed = accent,
            underlineSelected = accent,
        )
    }
    return if (dark) {
        TabStyle.Default.dark(colors = colors, metrics = compactTabMetrics(), scrollbarStyle = tabScrollbarStyle(true))
    } else {
        TabStyle.Default.light(colors = colors, metrics = compactTabMetrics(), scrollbarStyle = tabScrollbarStyle(false))
    }
}

/**
 * Compact tabs, for the section strips inside a pane.
 *
 * The one place the app overrules Int UI on size, and for a reason Int UI itself
 * acts on: it runs editor tabs and tool-window tabs at different densities
 * because they sit in different kinds of space. The inspector is a dense data
 * pane whose tab strip is a subdivision of a subdivision, and a 40dp strip there
 * costs a row of captured traffic for no gain. Editor tabs — the open requests —
 * keep Int UI's own metrics, because those *are* a top-level strip.
 */
private fun compactTabMetrics(): TabMetrics =
    TabMetrics.defaults(
        tabHeight = 28.dp,
        tabPadding = PaddingValues(horizontal = 8.dp),
        tabContentSpacing = 4.dp,
    )

@Composable
private fun Palette.editorTabStyle(dark: Boolean): TabStyle {
    // Editor tabs (the API client's request tabs) get a lifted surface when
    // selected, matching the header strips the rest of the app uses.
    val colors = if (dark) {
        TabColors.Editor.dark(
            background = Color.Transparent, backgroundHovered = rowHover,
            backgroundPressed = accentFill, backgroundSelected = head,
            backgroundDisabled = Color.Transparent,
            content = dim, contentHovered = text, contentDisabled = faint,
            contentPressed = text, contentSelected = text,
            underline = Color.Transparent, underlineHovered = Color.Transparent,
            underlineDisabled = Color.Transparent, underlinePressed = accent,
            underlineSelected = accent,
        )
    } else {
        TabColors.Editor.light(
            background = Color.Transparent, backgroundHovered = rowHover,
            backgroundPressed = accentFill, backgroundSelected = head,
            backgroundDisabled = Color.Transparent,
            content = dim, contentHovered = text, contentDisabled = faint,
            contentPressed = text, contentSelected = text,
            underline = Color.Transparent, underlineHovered = Color.Transparent,
            underlineDisabled = Color.Transparent, underlinePressed = accent,
            underlineSelected = accent,
        )
    }
    return if (dark) {
        TabStyle.Editor.dark(colors = colors, metrics = editorTabMetrics(), scrollbarStyle = tabScrollbarStyle(true))
    } else {
        TabStyle.Editor.light(colors = colors, metrics = editorTabMetrics(), scrollbarStyle = tabScrollbarStyle(false))
    }
}

/**
 * Editor tabs, at DESIGN.MD §12's 34dp rather than Int UI's 40.
 *
 * Taller than the section tabs below them, which is right — these are the
 * top-level strip and those are a subdivision — but not so tall that the strip
 * outweighs the request it names.
 */
private fun editorTabMetrics(): TabMetrics =
    TabMetrics.defaults(
        tabHeight = 34.dp,
        tabPadding = PaddingValues(horizontal = 10.dp),
        tabContentSpacing = 6.dp,
    )

/**
 * A tab strip with no scrollbar of its own.
 *
 * `TabStrip` lays its horizontal scrollbar as a bare child of the strip's Box —
 * no alignment — so it sits at the top edge *over* the tabs, and it carries
 * `scrollable` and `hoverable` unconditionally, whether or not there is anything
 * to scroll. That band swallows clicks aimed at the top of a tab. This is a
 * defect rather than a design choice, which is why it is the one metric this
 * file still touches.
 *
 * Zero thickness removes the band. Nothing is lost: the row still scrolls by
 * wheel and drag, and `TabStrip` already scrolls the selected tab into view.
 */
@Composable
private fun Palette.tabScrollbarStyle(dark: Boolean): ScrollbarStyle {
    val base = scrollbarStyle(dark)
    return ScrollbarStyle(
        colors = base.colors,
        metrics = base.metrics,
        trackClickBehavior = base.trackClickBehavior,
        scrollbarVisibility = ScrollbarVisibility.WhenScrolling.tabStrip(
            trackThickness = 0.dp,
            trackThicknessExpanded = 0.dp,
            trackPadding = PaddingValues(0.dp),
            trackPaddingWithBorder = PaddingValues(0.dp),
        ),
    )
}

@Composable
private fun Palette.tooltipStyle(dark: Boolean): TooltipStyle {
    val colors = if (dark) {
        TooltipColors.dark(backgroundColor = head, contentColor = text, borderColor = line, shadow = Color.Transparent)
    } else {
        TooltipColors.light(backgroundColor = head, contentColor = text, borderColor = line, shadow = Color.Transparent)
    }

    return if (dark) {
        TooltipStyle.dark(intUiTooltipColors = colors)
    } else {
        TooltipStyle.light(intUiTooltipColors = colors)
    }
}

@Composable
private fun Palette.groupHeaderStyle(dark: Boolean): GroupHeaderStyle {
    val colors = if (dark) GroupHeaderColors.dark(divider = line2) else GroupHeaderColors.light(divider = line2)
    return if (dark) GroupHeaderStyle.dark(colors = colors) else GroupHeaderStyle.light(colors = colors)
}

@Composable
private fun Palette.linkStyle(dark: Boolean): LinkStyle {
    val colors = if (dark) {
        LinkColors.dark(
            content = accent, contentDisabled = faint, contentFocused = accent,
            contentPressed = accent, contentHovered = accent, contentVisited = accent,
        )
    } else {
        LinkColors.light(
            content = accent, contentDisabled = faint, contentFocused = accent,
            contentPressed = accent, contentHovered = accent, contentVisited = accent,
        )
    }
    return if (dark) LinkStyle.dark(colors = colors) else LinkStyle.light(colors = colors)
}

@Composable
private fun Palette.iconButtonStyle(dark: Boolean): IconButtonStyle {
    val colors = if (dark) {
        IconButtonColors.dark(
            background = Color.Transparent, backgroundDisabled = Color.Transparent,
            // The rail is the only selected icon button, so this is the tool
            // stripe's active fill: a solid neutral step (DESIGN.MD §8/§10),
            // not a 16%-alpha accent wash — that was all but invisible against
            // a light chrome.
            backgroundSelected = stripeOn, backgroundSelectedActivated = stripeOn,
            // Pressed matches hovered and focus adds nothing, so clicking an
            // icon button does not flash a fill under the pointer. Hover still
            // reads (§6.4); the toggled-on fill still marks the rail's view.
            backgroundPressed = rowHover, backgroundHovered = rowHover,
            backgroundFocused = Color.Transparent,
            // No border in any state. DESIGN.MD §6.4: an icon button is a
            // square with a radius and a fill — hover and toggled-on are carried
            // by the background, never by an outline drawn round the glyph.
            border = Color.Transparent, borderDisabled = Color.Transparent,
            borderSelected = Color.Transparent, borderSelectedActivated = Color.Transparent,
            borderFocused = Color.Transparent, borderPressed = Color.Transparent,
            borderHovered = Color.Transparent,
        )
    } else {
        IconButtonColors.light(
            background = Color.Transparent, backgroundDisabled = Color.Transparent,
            // The rail is the only selected icon button, so this is the tool
            // stripe's active fill: a solid neutral step (DESIGN.MD §8/§10),
            // not a 16%-alpha accent wash — that was all but invisible against
            // a light chrome.
            backgroundSelected = stripeOn, backgroundSelectedActivated = stripeOn,
            // Pressed matches hovered and focus adds nothing, so clicking an
            // icon button does not flash a fill under the pointer. Hover still
            // reads (§6.4); the toggled-on fill still marks the rail's view.
            backgroundPressed = rowHover, backgroundHovered = rowHover,
            backgroundFocused = Color.Transparent,
            // No border in any state. DESIGN.MD §6.4: an icon button is a
            // square with a radius and a fill — hover and toggled-on are carried
            // by the background, never by an outline drawn round the glyph.
            border = Color.Transparent, borderDisabled = Color.Transparent,
            borderSelected = Color.Transparent, borderSelectedActivated = Color.Transparent,
            borderFocused = Color.Transparent, borderPressed = Color.Transparent,
            borderHovered = Color.Transparent,
        )
    }
    return if (dark) IconButtonStyle.dark(colors = colors)
    else IconButtonStyle.light(colors = colors)
}

/**
 * Windows/Linux scrollbars on every platform: BitTrace ships for Windows, and
 * the macOS overlay behaviour would hide the scrollbar the grid relies on for
 * position feedback. Metrics are Int UI's; only the thumb colour is ours.
 */
@Composable
private fun Palette.scrollbarStyle(dark: Boolean): ScrollbarStyle {
    val colors = if (dark) {
        ScrollbarColors.windowsAndLinuxDark(
            thumbBackground = line2, thumbBackgroundActive = accent,
            thumbOpaqueBackground = line2, thumbOpaqueBackgroundHovered = accent,
            thumbBorder = Color.Transparent, thumbBorderActive = Color.Transparent,
            thumbOpaqueBorder = Color.Transparent, thumbOpaqueBorderHovered = Color.Transparent,
            trackBackground = Color.Transparent, trackBackgroundHovered = Color.Transparent,
            trackOpaqueBackground = Color.Transparent, trackOpaqueBackgroundHovered = Color.Transparent,
        )
    } else {
        ScrollbarColors.windowsAndLinuxLight(
            thumbBackground = line2, thumbBackgroundActive = accent,
            thumbOpaqueBackground = line2, thumbOpaqueBackgroundHovered = accent,
            thumbBorder = Color.Transparent, thumbBorderActive = Color.Transparent,
            thumbOpaqueBorder = Color.Transparent, thumbOpaqueBorderHovered = Color.Transparent,
            trackBackground = Color.Transparent, trackBackgroundHovered = Color.Transparent,
            trackOpaqueBackground = Color.Transparent, trackOpaqueBackgroundHovered = Color.Transparent,
        )
    }
    return if (dark) ScrollbarStyle.windowsAndLinuxDark(colors = colors)
    else ScrollbarStyle.windowsAndLinuxLight(colors = colors)
}

@Composable
private fun Palette.titleBarStyle(dark: Boolean): TitleBarStyle {
    val colors = if (dark) {
        TitleBarColors.titleBarDark(
            backgroundColor = chrome, inactiveBackground = chrome,
            contentColor = text, borderColor = line,
            titlePaneButtonHoveredBackground = rowHover, titlePaneButtonPressedBackground = accentFill,
            titlePaneCloseButtonHoveredBackground = err, titlePaneCloseButtonPressedBackground = err,
            iconButtonHoveredBackground = rowHover, iconButtonPressedBackground = accentFill,
            dropdownHoveredBackground = rowHover, dropdownPressedBackground = accentFill,
        )
    } else {
        TitleBarColors.titleBarLight(
            backgroundColor = chrome, inactiveBackground = chrome,
            contentColor = text, borderColor = line,
            titlePaneButtonHoveredBackground = rowHover, titlePaneButtonPressedBackground = accentFill,
            titlePaneCloseButtonHoveredBackground = err, titlePaneCloseButtonPressedBackground = err,
            iconButtonHoveredBackground = rowHover, iconButtonPressedBackground = accentFill,
            dropdownHoveredBackground = rowHover, dropdownPressedBackground = accentFill,
        )
    }
    // 30dp rather than Int UI's 40. DESIGN.MD §12 puts the main toolbar at 40,
    // but that assumes the toolbar the spec describes — a project chip, a
    // capture widget, a transport cluster. Ours carries a menu strip and an
    // endpoint, and at 40 the band reads as empty. 30 also matches the
    // tool-window header height everything below it uses (§5.2).
    val metrics = TitleBarMetrics.titleBarDefaults(
        height = 30.dp,
        // The caption buttons have to come down with the bar, or Windows draws
        // minimise/maximise/close taller than the strip that holds them.
        titlePaneButtonSize = DpSize(44.dp, 30.dp),
    )
    return if (dark) TitleBarStyle.titleBarDark(colors = colors, metrics = metrics)
    else TitleBarStyle.titleBarLight(colors = colors, metrics = metrics)
}
