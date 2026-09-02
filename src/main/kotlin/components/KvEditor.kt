package org.bittrace.components

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
import org.bittrace.ui.CheckBox
import org.bittrace.ui.P
import org.bittrace.ui.PaneHeader
import org.bittrace.ui.PzText
import org.bittrace.ui.TextInput
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

        rows.forEachIndexed { index, row ->
            KvRowEditor(
                row = row,
                nameHint = nameHint,
                valueHint = valueHint,
                onChange = { updated -> onChange(rows.toMutableList().also { it[index] = updated }) },
                onRemove = { onChange(rows.toMutableList().also { it.removeAt(index) }) },
            )
        }

        // The always-present blank row: typing in it appends a real one.
        KvRowEditor(
            row = KeyValue(),
            nameHint = nameHint,
            valueHint = valueHint,
            placeholder = true,
            // Belt and braces: a row with nothing in it is never worth
            // appending, whatever the field reports.
            onChange = { if (it.name.isNotEmpty() || it.value.isNotEmpty()) onChange(rows + it) },
            onRemove = {},
        )
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
