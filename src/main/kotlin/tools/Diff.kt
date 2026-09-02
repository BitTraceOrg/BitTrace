package org.bittrace.tools

/** What happened to one line between the two sides. */
enum class DiffKind { SAME, ADDED, REMOVED, CHANGED }

/**
 * One row of a side-by-side diff.
 *
 * A row, not a line: [SAME][DiffKind.SAME] and [CHANGED][DiffKind.CHANGED] rows
 * carry both sides, an insertion carries only the right and a deletion only the
 * left. Line numbers are per side and are null where that side has no line, so
 * the two gutters keep counting their own files rather than the rows.
 */
class DiffRow(
    val kind: DiffKind,
    val left: String?,
    val right: String?,
    val leftNumber: Int?,
    val rightNumber: Int?,
)

/**
 * A line-by-line diff of [left] against [right].
 *
 * Longest-common-subsequence, after trimming the identical head and tail — which
 * is what makes it affordable in practice, since two captures of the same
 * endpoint usually differ in a handful of lines buried in identical ones.
 *
 * The table is O(n×m), so it is capped. Past [CELL_LIMIT] the middle is reported
 * as one block replaced by another instead of being aligned line by line: a
 * coarse answer computed in milliseconds, rather than an exact one that hangs
 * the window. [truncated] says which you got, so the view can say so too.
 */
fun diffLines(left: List<String>, right: List<String>): DiffResult {
    var head = 0
    while (head < left.size && head < right.size && left[head] == right[head]) head++

    var tail = 0
    while (
        tail < left.size - head &&
        tail < right.size - head &&
        left[left.size - 1 - tail] == right[right.size - 1 - tail]
    ) {
        tail++
    }

    val leftMiddle = left.subList(head, left.size - tail)
    val rightMiddle = right.subList(head, right.size - tail)

    val rows = mutableListOf<DiffRow>()
    for (i in 0 until head) {
        rows += DiffRow(DiffKind.SAME, left[i], right[i], i + 1, i + 1)
    }

    val truncated = leftMiddle.size.toLong() * rightMiddle.size > CELL_LIMIT
    val middle = if (truncated) {
        blockReplacement(leftMiddle, rightMiddle, head)
    } else {
        align(leftMiddle, rightMiddle, head)
    }
    rows += middle

    for (i in 0 until tail) {
        val leftIndex = left.size - tail + i
        val rightIndex = right.size - tail + i
        rows += DiffRow(DiffKind.SAME, left[leftIndex], right[rightIndex], leftIndex + 1, rightIndex + 1)
    }

    return DiffResult(rows, truncated)
}

/** The rows, and whether the middle had to be reported coarsely. */
class DiffResult(val rows: List<DiffRow>, val truncated: Boolean)

/**
 * Pairs a deletion run with the insertion run that follows it.
 *
 * Without this a changed line shows as a deleted row and then an inserted row,
 * and the two halves of one edit sit on different lines of the view — which is
 * exactly the shape a side-by-side diff exists to avoid.
 */
private fun align(left: List<String>, right: List<String>, offset: Int): List<DiffRow> {
    val lengths = lcsTable(left, right)
    val removed = mutableListOf<Pair<Int, String>>()
    val added = mutableListOf<Pair<Int, String>>()
    val rows = mutableListOf<DiffRow>()

    fun flush() {
        val pairs = minOf(removed.size, added.size)
        for (i in 0 until pairs) {
            rows += DiffRow(
                DiffKind.CHANGED,
                removed[i].second, added[i].second,
                removed[i].first + offset + 1, added[i].first + offset + 1,
            )
        }
        for (i in pairs until removed.size) {
            rows += DiffRow(DiffKind.REMOVED, removed[i].second, null, removed[i].first + offset + 1, null)
        }
        for (i in pairs until added.size) {
            rows += DiffRow(DiffKind.ADDED, null, added[i].second, null, added[i].first + offset + 1)
        }
        removed.clear()
        added.clear()
    }

    var i = 0
    var j = 0
    while (i < left.size || j < right.size) {
        when {
            i < left.size && j < right.size && left[i] == right[j] -> {
                flush()
                rows += DiffRow(DiffKind.SAME, left[i], right[j], i + offset + 1, j + offset + 1)
                i++
                j++
            }

            j < right.size && (i == left.size || lengths[i][j + 1] >= lengths[i + 1][j]) -> {
                added += j to right[j]
                j++
            }

            else -> {
                removed += i to left[i]
                i++
            }
        }
    }
    flush()
    return rows
}

/** Classic LCS lengths, indexed from the end so the walk above reads forwards. */
private fun lcsTable(left: List<String>, right: List<String>): Array<IntArray> {
    val table = Array(left.size + 1) { IntArray(right.size + 1) }
    for (i in left.indices.reversed()) {
        for (j in right.indices.reversed()) {
            table[i][j] = if (left[i] == right[j]) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
    }
    return table
}

/** The whole differing middle as one removal followed by one insertion. */
private fun blockReplacement(left: List<String>, right: List<String>, offset: Int): List<DiffRow> {
    val rows = mutableListOf<DiffRow>()
    val paired = minOf(left.size, right.size)
    for (i in 0 until paired) {
        rows += DiffRow(DiffKind.CHANGED, left[i], right[i], i + offset + 1, i + offset + 1)
    }
    for (i in paired until left.size) {
        rows += DiffRow(DiffKind.REMOVED, left[i], null, i + offset + 1, null)
    }
    for (i in paired until right.size) {
        rows += DiffRow(DiffKind.ADDED, null, right[i], null, i + offset + 1)
    }
    return rows
}

/**
 * The largest LCS table worth building: 4 million cells, so 16 MB of `int`.
 *
 * Reached only by two large bodies that share almost nothing — the head and tail
 * trim removes the common case long before this.
 */
private const val CELL_LIMIT = 4_000_000L
