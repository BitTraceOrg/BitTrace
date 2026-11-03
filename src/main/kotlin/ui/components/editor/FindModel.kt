package org.bittrace.ui.components.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The find bar's state: the query, the matches, which one is current.
 *
 * Matches are start offsets, sorted, all the same length — a plain search has
 * no use for anything richer — so a ten-thousand-match body costs forty
 * kilobytes and the view finds the ones on a line by binary search.
 */
class FindModel {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var matchCase by mutableStateOf(false)

    /** Bumped to ask the find bar's field for focus — on every Mod+F, open or not. */
    var focusRequests by mutableIntStateOf(0)

    /** Bumped when the matches change, so the view knows to redraw. */
    var revision by mutableIntStateOf(0)
        private set

    var matches: IntArray = IntArray(0)
        private set
    var matchLength: Int = 0
        private set

    /** Index into [matches], or -1. */
    var current by mutableIntStateOf(-1)

    /** Whether the search stopped at [MATCH_LIMIT]. */
    var capped: Boolean = false
        private set

    fun setResults(found: IntArray, length: Int, nearOffset: Int) {
        matches = found
        matchLength = length
        capped = found.size >= MATCH_LIMIT
        current = if (found.isEmpty()) -1 else firstAtOrAfter(nearOffset).let { if (it >= found.size) 0 else it }
        revision++
    }

    fun clearResults() = setResults(IntArray(0), 0, 0)

    /** The first match starting at or after [offset]. */
    fun firstAtOrAfter(offset: Int): Int {
        var lo = 0
        var hi = matches.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (matches[mid] < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Moves to the next (or previous) match, wrapping; returns its offset or -1. */
    fun advance(forward: Boolean): Int {
        if (matches.isEmpty()) return -1
        current = when {
            current < 0 -> 0
            forward -> (current + 1) % matches.size
            else -> (current - 1 + matches.size) % matches.size
        }
        return matches[current]
    }

    companion object {
        const val MATCH_LIMIT = 100_000
    }
}

/** Every start offset of [query] in [text], up to [limit]. Pure; called off the UI thread. */
fun findAll(text: String, query: String, matchCase: Boolean, limit: Int = FindModel.MATCH_LIMIT): IntArray {
    if (query.isEmpty()) return IntArray(0)
    val out = IntList()
    var i = text.indexOf(query, 0, ignoreCase = !matchCase)
    while (i >= 0 && out.size < limit) {
        out.add(i)
        i = text.indexOf(query, i + query.length, ignoreCase = !matchCase)
    }
    return out.toArray()
}
