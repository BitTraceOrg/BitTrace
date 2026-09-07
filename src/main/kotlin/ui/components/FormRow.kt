package org.bittrace.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.bittrace.ui.P

import org.bittrace.ui.Typo

/**
 * The label column and the labelled row, shared by every form in the app.
 *
 * Four screens had grown their own. The Auth tab and the request Settings tab
 * each had a copy of the label, and the Settings *screen* had a third with a
 * different width — plus two composables both called `Field`, in two files,
 * doing the same job with different signatures. Two copies of a measurement is
 * how two forms in the same window come to indent differently; two composables
 * with one name is how you end up editing the wrong one.
 *
 * The gap stays separate from the width rather than being baked into it, so a
 * label that fills the column still cannot touch the control beside it. And the
 * width is generous on purpose: a column narrower than a word does not wrap the
 * label, it breaks the word.
 */
enum class FormStyle(
    val labelWidth: Dp,
    /** Kept out of [labelWidth] so a full-width label cannot touch its control. */
    val gap: Dp,
) {

    /**
     * The request editor's tabs — Auth, Settings — at the label type size.
     *
     * These sit inside a pane beside a response, so the column is as narrow as
     * the longest label allows.
     */
    Compact(labelWidth = 104.dp, gap = 10.dp),

    /**
     * The Settings screen, which reads at body size and so needs more room for
     * the same words.
     *
     * The two widths sit here together rather than one per file precisely
     * because they are a judgement about the same thing: 130 against 104 is a
     * type-size difference, not two people guessing.
     */
    Roomy(labelWidth = 130.dp, gap = 0.dp),
    ;

    /** What a row indented past the label has to skip: both of them. */
    val column: Dp get() = labelWidth + gap

    /** Resolved late, because the type scale is theme-dependent. */
    val labelStyle: TextStyle
        @Composable get() = if (this == Compact) Typo.label else Typo.body
}

/**
 * A form label and the gap that follows it.
 *
 * One composable rather than a width on each call site, because the gap is the
 * part that gets forgotten — and a label butted against its input is the thing
 * that made this worth fixing.
 */
@Composable
fun FormLabel(text: String, enabled: Boolean = true, style: FormStyle = FormStyle.Compact) {
    PzText(
        text,
        color = if (enabled) P.dim else P.faint,
        style = style.labelStyle,
        family = P.Ui,
        modifier = Modifier.width(style.labelWidth),
    )
    if (style.gap > 0.dp) Spacer(Modifier.width(style.gap))
}

/**
 * A labelled row: caption in the shared column, whatever you like beside it.
 *
 * The control is a `RowScope` slot rather than a value so a row can hold a
 * toggle, a field and a unit suffix — which several of them do — without this
 * having to know about any of them.
 */
@Composable
fun FormField(
    label: String,
    style: FormStyle = FormStyle.Compact,
    enabled: Boolean = true,
    control: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FormLabel(label, enabled, style)
        control()
    }
}

/**
 * The commonest form row of all: a label and a text input bound to it.
 *
 * Written out as label-plus-`TextInput` in both form vocabularies, and three
 * times in a row in the git settings pane alone. [width] null means the input
 * takes the space that is left, which is what a field inside a pane wants; a
 * settings screen names a width so its fields line up down the column.
 */
@Composable
fun FormTextField(
    label: String,
    value: String,
    placeholder: String = "",
    style: FormStyle = FormStyle.Compact,
    enabled: Boolean = true,
    width: Dp? = null,
    onChange: (String) -> Unit,
) {
    FormField(label, style, enabled) {
        TextInput(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            enabled = enabled,
            modifier = if (width == null) Modifier.weight(1f) else Modifier.width(width),
        )
    }
}

/**
 * An explanatory line under a row, indented to the control column.
 *
 * @param inset whether the note carries the surrounding form's own padding. The
 *   Auth tab lays its notes out inside a container that already pads them; the
 *   Settings tab does not. That is the only thing the two copies ever disagreed
 *   about, so it is the only thing that became a parameter.
 */
@Composable
fun FormNote(text: String, inset: Boolean = false, style: FormStyle = FormStyle.Compact) {
    Row(if (inset) Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp) else Modifier) {
        Spacer(Modifier.width(style.column))
        PzText(text, color = P.faint, style = Typo.caption, family = P.Ui)
    }
}
