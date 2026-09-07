package org.bittrace.ui.layouts.forge.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.Typo
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * What each HTTP method means, as colour.
 *
 * Reads are cool, writes warm, and the one that destroys is the one that is red
 * — so a request says what it will do before its URL is even read. These are
 * palette roles rather than new values, so a theme carries them.
 */
fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> P.info
    "POST" -> P.ok
    "PUT" -> P.warn
    "PATCH" -> P.send
    "DELETE" -> P.err
    "HEAD", "OPTIONS" -> P.key
    else -> P.dim
}

/**
 * A method shortened to fit a narrow column, as the collections tree needs.
 *
 * Only the two that do not fit are abbreviated, and both to the form every API
 * tool uses — a reader who has seen `DEL` anywhere has seen it here.
 */
fun methodTag(method: String): String = when (val upper = method.uppercase()) {
    "DELETE" -> "DEL"
    "OPTIONS" -> "OPT"
    else -> upper.take(6)
}

/**
 * The API client's method picker.
 *
 * A button and a popup rather than the shared [org.bittrace.ui.Dropdown],
 * because this picker colours each option and Jewel's combo box can only do
 * that through an overload marked `@ExperimentalJewelApi`. A menu built from
 * stable parts renders exactly the same list and lets the entries carry their
 * own colour, which is the whole point of the control.
 */
@Composable
fun MethodPicker(
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PzText(
                    value,
                    color = methodColor(value),
                    style = Typo.label, family = P.Ui,
                    weight = FontWeight.Medium, softWrap = false,
                )
                Spacer(Modifier.width(6.dp))
                Icon(key = AllIconsKeys.General.ChevronDown, contentDescription = null, tint = P.dim)
            }
        }
        if (open) {
            PopupMenu(
                onDismissRequest = { open = false; true },
                horizontalAlignment = Alignment.Start,
            ) {
                options.forEach { method ->
                    selectableItem(
                        selected = method == value,
                        onClick = { open = false; onSelect(method) },
                    ) {
                        PzText(
                            method,
                            color = methodColor(method),
                            style = Typo.label, family = P.Ui, softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
