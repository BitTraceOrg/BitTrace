package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A small filled square — status dots, colour swatches, the brand glyph.
 *
 * Jewel has no equivalent: its status affordances are icons, and these are
 * deliberately not icons. A 5px square reads as a state at sizes where an icon
 * would be mush, which is what the status bar and the waterfall legend need.
 */
@Composable
fun Dot(color: Color, s: Int = 5) = Box(Modifier.size(s.dp).background(color))
