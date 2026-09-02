package org.bittrace.ui

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.drop
import org.jetbrains.jewel.ui.component.TextField

/**
 * A single-line text field in the app's style.
 *
 * `bordered = false` maps onto Jewel's `undecorated`, which is what the merged
 * method+URL address control in the API client needs: one frame, two fields.
 *
 * There is no height parameter: the field is as tall as Int UI says a field is,
 * and a caller that squeezed it would only push its own text off centre — which
 * is exactly what the old `height` argument did once the metrics became Jewel's.
 *
 * Callers hold a plain `String`, so the caret lives in a `TextFieldState` here
 * rather than being asked of them — the `String` overload of Jewel's field is
 * experimental, and rebuilding the value from the string on every recomposition
 * would send the caret to the end after each keystroke. Edits flow out through
 * [onValueChange]; the state is only re-seeded when the text changes from the
 * outside, such as a saved request being loaded into a tab.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    bordered: Boolean = true,
) {
    val state = rememberTextFieldState(value)
    val latest by rememberUpdatedState(onValueChange)

    LaunchedEffect(state) {
        // `drop(1)` matters: a snapshotFlow replays the current value the moment
        // it is collected, so without it every field reported an "edit" to the
        // text it was born with. That is invisible on a field bound to a plain
        // property, but the KV editor's blank trailing row treats any edit as
        // "this row is real now" — so merely showing the tab appended an empty
        // row, and every request was marked dirty on first paint. The caller
        // already holds this value; only what follows it is news.
        snapshotFlow { state.text.toString() }.drop(1).collect { edited -> latest(edited) }
    }
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }

    TextField(
        state = state,
        enabled = enabled,
        undecorated = !bordered,
        modifier = modifier,
        placeholder = placeholder.takeIf { it.isNotEmpty() }?.let {
            { PzText(it, color = P.faint, style = Typo.body, softWrap = false) }
        },
    )
}
