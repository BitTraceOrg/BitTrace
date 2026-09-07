package org.bittrace.ui.layouts.forge.components

import androidx.compose.foundation.layout.height
import org.bittrace.ui.Typo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bittrace.api.KeyValue
import org.bittrace.ui.components.CheckBox
import org.bittrace.ui.P
import org.bittrace.ui.components.PaneHeader
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.bottomBorder
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * An editable name/value table for headers and query parameters.
 *
 * Rows carry an enabled flag rather than being deleted, because keeping a
 * header around switched off is how people actually work. A blank trailing row
 * is always present and becomes real as soon as it is typed into — that, plus
 * the × on each row, is the whole interaction.
 *
 * The caller owns the list: every edit produces a new one, so undo/dirty
 * tracking stay the state holder's business.
 */
@Composable
fun KvEditor(
    rows: List<KeyValue>,
    modifier: Modifier = Modifier,
    nameHint: String = "name",
    valueHint: String = "value",
    onChange: (List<KeyValue>) -> Unit,
) {
    // P.input rather than P.bg: the request builder is a form, and a light theme
    // puts a form on white. The two are the same grey in a dark theme.
    Column(modifier.fillMaxWidth().background(P.input)) {
        // Column headings on the shared header strip — this is a table too, and
        // a heading that reads as a different rank in one pane than the other is
        // just noise. The leading gap is the enable column plus the 8dp that
        // follows every cell, so each name sits over the field below it.
        PaneHeader {
            Spacer(Modifier.width(ENABLE_WIDTH + 8.dp))
            PzText("Name", color = P.dim, style = Typo.label, family = P.Ui, modifier = Modifier.width(NAME_WIDTH))
            Spacer(Modifier.width(8.dp))
            PzText("Value", color = P.dim, style = Typo.label, family = P.Ui)
        }

        // Every row plus one blank, from a single loop.
        //
        // The blank used to be a second call site after the loop, and that is
        // what made typing into it append a row per keystroke. A field keeps its
        // caret and its own text buffer in a `TextFieldState` tied to its
        // position in the composition; appending put the new row at a *new*
        // position inside the loop while the caret stayed in the blank field
        // below it, still holding everything typed so far. So "c" appended a row
        // "c", "cl" appended a row "cl", and so on down the screenshot.
        //
        // In one loop the blank sits at index `rows.size`, and appending makes
        // that same position a real row — the field keeps its state, its text
        // now matches the row behind it, and the caret never moves. A fresh
        // blank appears below.
        repeat(rows.size + 1) { index ->
            val blank = index == rows.size
            KvRowEditor(
                row = rows.getOrElse(index) { KeyValue() },
                nameHint = nameHint,
                valueHint = valueHint,
                placeholder = blank,
                onChange = { updated -> kvEdited(rows, index, updated)?.let(onChange) },
                onRemove = { if (!blank) onChange(kvRemoved(rows, index)) },
            )
        }
    }
}

@Composable
private fun KvRowEditor(
    row: KeyValue,
    nameHint: String,
    valueHint: String,
    placeholder: Boolean = false,
    onChange: (KeyValue) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        // A fixed height rather than one measured from the children: the blank
        // trailing row shows neither the checkbox nor the remove button, so a
        // wrap-content row shrank to the bare field and sat visibly shorter
        // than every row above it.
        Modifier.fillMaxWidth().height(ROW_HEIGHT).bottomBorder(P.line2).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wide enough for the checkbox's own footprint. Jewel draws a 16dp box
        // inside a 24dp target (DESIGN.MD §9.15); a 14dp column squeezed it,
        // which is what made it look undersized rather than merely small.
        Box(Modifier.width(ENABLE_WIDTH), contentAlignment = Alignment.Center) {
            // The blank row has nothing to enable yet, so it shows no box. The
            // checkbox owns its own click target now, so the row no longer
            // wraps it in one.
            if (!placeholder) {
                CheckBox(row.enabled) { onChange(row.copy(enabled = it)) }
            }
        }
        Spacer(Modifier.width(8.dp))
        TextInput(
            value = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            placeholder = if (placeholder) nameHint else "",
            bordered = false,
            modifier = Modifier.width(NAME_WIDTH),
        )
        Spacer(Modifier.width(8.dp))
        TextInput(
            value = row.value,
            onValueChange = { onChange(row.copy(value = it)) },
            placeholder = if (placeholder) valueHint else "",
            bordered = false,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            // The blank trailing row has nothing to remove yet.
            if (!placeholder) {
                IconActionButton(
                    key = AllIconsKeys.General.Delete,
                    contentDescription = "Remove this row",
                    onClick = onRemove,
                )
            }
        }
    }
}

/**
 * Every row, filled or blank. The checkbox's 24dp target plus the 3dp of air
 * the row used to get from its padding — so the rows that already looked right
 * are unchanged and only the blank one moves.
 */
private val ROW_HEIGHT = 30.dp

/** Matches the inspector's key column, so tables line up across the app. */
private val NAME_WIDTH = 180.dp

/**
 * The enable column. Sized to the checkbox's own 24dp target rather than to the
 * glyph, so nothing clips it — and shared with the header, whose leading gap is
 * this plus the 8dp that follows every cell.
 */
private val ENABLE_WIDTH = 24.dp

/**
 * The list an edit at [index] produces, or null when there is nothing to do.
 *
 * Split out from the composable because it is the part with a rule in it, and
 * because a Compose test rig is not worth standing up to assert what amounts to
 * two list operations. [index] past the end is the blank trailing row: it
 * appends, but only once there is something to append — an edit that leaves the
 * row empty is a row that was never real, and appending it would put a blank
 * line above the blank line.
 */
internal fun kvEdited(rows: List<KeyValue>, index: Int, updated: KeyValue): List<KeyValue>? = when {
    index in rows.indices -> rows.toMutableList().also { it[index] = updated }
    updated.name.isEmpty() && updated.value.isEmpty() -> null
    else -> rows + updated
}

/**
 * The list with the row at [index] gone.
 *
 * By position, not by value: `rows - row` would take every row equal to it, and
 * two rows with the same name and value are a thing people really do have while
 * they are in the middle of editing one of them.
 */
internal fun kvRemoved(rows: List<KeyValue>, index: Int): List<KeyValue> =
    if (index in rows.indices) rows.toMutableList().also { it.removeAt(index) } else rows
