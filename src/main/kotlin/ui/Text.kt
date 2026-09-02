package org.bittrace.ui

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
