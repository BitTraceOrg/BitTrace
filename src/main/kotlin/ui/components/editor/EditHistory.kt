package org.bittrace.ui.components.editor

/** An anchor and a head; the head is where the caret is drawn. */
data class Selection(val anchor: Int, val head: Int = anchor) {
    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
    val isEmpty: Boolean get() = anchor == head
}

/** What kind of edit an entry is, which decides what it may merge with. */
enum class EditKind { TYPE, DELETE_BACKWARD, DELETE_FORWARD, OTHER }

/**
 * Undo and redo.
 *
 * Each entry is the edit itself — where, what went, what came — plus the
 * selections either side of it, so undoing puts the caret back where it was
 * rather than wherever the inverse edit happens to leave it.
 *
 * Typing merges: a run of characters typed one after another, within
 * [mergeWindowMs] of each other, undoes as one word rather than one letter at
 * a time. A line break ends a run, as does any other kind of edit. Backspace
 * and Delete runs merge the same way.
 *
 * Bounded by entry count and by the chars it retains, oldest first: a history
 * exists to hold text, and a long session of pasting large bodies would
 * otherwise keep every one of them.
 */
class EditHistory(
    private val maxEntries: Int = 500,
    private val maxChars: Long = 8_000_000,
    private val mergeWindowMs: Long = 600,
) {
    class Entry(
        var from: Int,
        var removed: String,
        var inserted: String,
        val selectionBefore: Selection,
        var selectionAfter: Selection,
        var time: Long,
        val kind: EditKind,
    ) {
        val weight: Long get() = (removed.length + inserted.length).toLong()
    }

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var retained = 0L

    /** Set after undo or redo so the next edit never merges into a restored entry. */
    private var sealed = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(change: Change, kind: EditKind, before: Selection, after: Selection, now: Long) {
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (!sealed && last != null && now - last.time <= mergeWindowMs && merge(last, change, kind)) {
            last.selectionAfter = after
            last.time = now
            retained += change.removed.length + change.inserted.length
        } else {
            undoStack.addLast(Entry(change.from, change.removed, change.inserted, before, after, now, kind))
            retained += change.removed.length + change.inserted.length
        }
        sealed = false
        trim()
    }

    private fun merge(last: Entry, change: Change, kind: EditKind): Boolean {
        if (kind != last.kind) return false
        return when (kind) {
            EditKind.TYPE -> {
                val ok = change.removed.isEmpty() &&
                    '\n' !in change.inserted &&
                    !last.inserted.endsWith('\n') &&
                    change.from == last.from + last.inserted.length
                if (ok) last.inserted += change.inserted
                ok
            }

            EditKind.DELETE_BACKWARD -> {
                val ok = change.inserted.isEmpty() && change.from + change.removed.length == last.from
                if (ok) {
                    last.from = change.from
                    last.removed = change.removed + last.removed
                }
                ok
            }

            EditKind.DELETE_FORWARD -> {
                val ok = change.inserted.isEmpty() && change.from == last.from
                if (ok) last.removed += change.removed
                ok
            }

            EditKind.OTHER -> false
        }
    }

    /** The entry to invert, moved onto the redo stack. */
    fun undo(): Entry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        sealed = true
        return entry
    }

    /** The entry to re-apply, moved back onto the undo stack. */
    fun redo(): Entry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        sealed = true
        return entry
    }

    /** Ends the current typing run, so the next edit starts an entry of its own. */
    fun seal() {
        sealed = true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        retained = 0
        sealed = false
    }

    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > maxEntries || retained > maxChars)) {
            retained -= undoStack.removeFirst().weight
        }
    }
}
