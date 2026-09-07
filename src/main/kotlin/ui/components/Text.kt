package org.bittrace.ui.components

import org.bittrace.ui.P
import org.bittrace.ui.Typo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.jewel.ui.component.Text

/**
 * The app's text atom, over Jewel's [Text].
 *
 * It survives as a wrapper for one reason: this app defaults to monospace,
 * because captured data is the common case and chrome labels are the exception
 * that passes `family = P.Ui`. Jewel's `Text` defaults the other way. Keeping
 * that inversion in one place is worth a few lines; spelling it out at ~120 call
 * sites is not.
 *
 * Size comes from [Typo], never from a number.
 */

/** Shared text atom. */
@Composable
fun PzText(
    text: String,
    color: Color = P.text,
    style: TextStyle = Typo.body,
    family: FontFamily = P.Mono,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    /** Reports the measured layout — what a line-number gutter aligns itself to. */
    onTextLayout: (TextLayoutResult) -> Unit = {},
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    fontWeight = weight,
    fontFamily = family,
    maxLines = maxLines,
    overflow = overflow,
    softWrap = softWrap,
    onTextLayout = onTextLayout,
    style = style,
)

/**
 * One line of text in a table cell or a list row: ellipsised at the cell's
 * edge and never wrapped, which is the shape every grid, tree and tab title in
 * the app wants and each had been spelling out as the same three arguments.
 */
@Composable
fun CellText(
    text: String,
    color: Color = P.text,
    style: TextStyle = Typo.body,
    family: FontFamily = P.Mono,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) = PzText(
    text = text,
    color = color,
    style = style,
    family = family,
    weight = weight,
    modifier = modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    softWrap = false,
)

/** [CellText] over pre-styled text — a cell with something marked inside it. */
@Composable
fun CellText(
    text: AnnotatedString,
    color: Color = P.text,
    style: TextStyle = Typo.body,
    family: FontFamily = P.Mono,
    modifier: Modifier = Modifier,
) = PzText(
    text = text,
    color = color,
    style = style,
    family = family,
    modifier = modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    softWrap = false,
)

/** [PzText] over pre-styled text (syntax-highlighted bodies). */
@Composable
fun PzText(
    text: AnnotatedString,
    color: Color = P.text,
    style: TextStyle = Typo.body,
    family: FontFamily = P.Mono,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    /** Reports the measured layout — what a line-number gutter aligns itself to. */
    onTextLayout: (TextLayoutResult) -> Unit = {},
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    fontWeight = weight,
    fontFamily = family,
    maxLines = maxLines,
    overflow = overflow,
    softWrap = softWrap,
    onTextLayout = onTextLayout,
    style = style,
)

/**
 * "Nothing here", in the one place that decides how that looks.
 *
 * Eight screens had written this Box-and-faint-text pair themselves and drifted
 * to two paddings, two alignments and, in one case, the mono family where every
 * sibling used the UI one. A message saying a list is empty is not the place for
 * a screen to have an opinion.
 *
 * @param centred for a surface where the message is the whole content — an
 *   empty grid — rather than the head of a list that may yet fill.
 */
@Composable
fun EmptyState(text: String, centred: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier.then(if (centred) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(14.dp),
        contentAlignment = if (centred) Alignment.Center else Alignment.TopStart,
    ) {
        PzText(text, color = P.faint, style = Typo.label, family = P.Ui)
    }
}

/**
 * The mark that says "there is work here that is not written down".
 *
 * A tab wears it and so does the tree row above it, and the tree's comment
 * already said it was "the same mark an unsaved request tab carries" — which is
 * a comment doing a shared component's job. The two copies had drifted to
 * different type families, so the glyph rendered at two different widths.
 */
@Composable
fun DirtyDot() = PzText("\u25CF", color = P.warn, style = Typo.micro, family = P.Ui)
