package org.bittrace.ui.layouts.inspector.components

import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide

/**
 * Which of [rows] carry [needle] in the given body.
 *
 * Kept apart from the query model on purpose: this is the one part of searching
 * that does I/O and cannot be a pure predicate, since it decodes every cached
 * body it is given. That is cheap for a hundred rows and not for ten thousand,
 * so it runs off the UI thread behind a debounce and hands back a set the
 * filter pass can test in constant time.
 *
 * A body that has been evicted simply does not match. A hit you cannot then
 * open in the inspector is worse than a miss — it sends you looking for
 * something the app no longer has.
 */
fun matchingBodies(
    rows: List<TrafficRow>,
    needle: String,
    side: BodySide,
    body: (String, BodySide) -> ByteArray?,
): Set<String> {
    if (needle.isBlank()) return emptySet()
    return rows.mapNotNullTo(mutableSetOf()) { row ->
        val bytes = body(row.id, side) ?: return@mapNotNullTo null
        row.id.takeIf { bytes.decodeToString().contains(needle, ignoreCase = true) }
    }
}
