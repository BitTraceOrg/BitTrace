package org.bittrace.ui.layouts.inspector.components

import org.bittrace.ui.hostPath
import org.bittrace.ui.instantOf
import org.bittrace.ui.kindOfRow
import org.bittrace.data.HTTP_METHODS
import org.bittrace.data.TrafficRow
import org.bittrace.proxy.BodySide

/**
 * What the overview band is filtering by.
 *
 * One object, four renderings: the flow table, the idle lanes, the time strip
 * and the status bar all read this and nothing else. That is the whole reason
 * it is a value rather than a pile of state on a composable — four views of one
 * query cannot disagree, and four separate filters always eventually do.
 *
 * @property window milliseconds from the first captured flow, inclusive. Kept
 *   relative rather than absolute because that is what the strip draws and what
 *   the token reads out; absolute instants would have to be converted at every
 *   one of those points.
 * @property isolate one flow id, which overrides everything else. Isolating is
 *   "show me only this", not "and also this".
 */
data class FlowQuery(
    val facets: Map<FacetGroup, Set<String>> = emptyMap(),
    val text: String = "",
    val window: ClosedFloatingPointRange<Double>? = null,
    val isolate: String? = null,
    val joinFacetsWithAnd: Boolean = true,
    /** Which body [text] is searched in. */
    val side: BodySide = BodySide.RESPONSE,
) {
    val isEmpty: Boolean
        get() = facets.values.all { it.isEmpty() } && text.isBlank() && window == null && isolate == null

    /** Ticks or unticks one value, leaving every other group alone. */
    fun toggle(group: FacetGroup, value: String): FlowQuery {
        val current = facets[group].orEmpty()
        val updated = if (value in current) current - value else current + value
        return copy(
            facets = if (updated.isEmpty()) facets - group else facets + (group to updated),
            // Picking a facet is a deliberate act; keeping a flow isolated on
            // top of it would show one row and look like the facet was broken.
            isolate = null,
        )
    }

    fun clearGroup(group: FacetGroup): FlowQuery = copy(facets = facets - group)

    /**
     * The query as the status bar spells it.
     *
     * `facet:value AND facet:value AND text:"…"` — the same shape the token
     * strip shows, written out, so the two are recognisably the same thing.
     */
    fun describe(): String {
        if (isEmpty) return "no query"
        isolate?.let { return "isolate:$it" }
        val joiner = if (joinFacetsWithAnd) " AND " else " OR "
        val parts = buildList {
            facets.entries
                .filter { it.value.isNotEmpty() }
                .sortedBy { it.key.ordinal }
                .forEach { (group, values) ->
                    add("${group.label.lowercase()}:${values.sorted().joinToString(",")}")
                }
            window?.let { add("time:${secondsLabel(it)}") }
            if (text.isNotBlank()) add("""text:"$text"""")
        }
        return parts.joinToString(joiner)
    }
}

/** The six columns the facet grid offers, in the order it shows them. */
enum class FacetGroup(val label: String) {
    STATUS("Status"),
    METHOD("Method"),
    TYPE("Type"),
    HOST("Host"),
    DURATION("Duration"),
    SIZE("Size"),
}

/**
 * Which flow-table column edits which group.
 *
 * Three of the grid's header funnels offer the same ticks as three of the band's
 * facet columns. Naming the correspondence here, next to the groups, is what
 * lets both surfaces write one set instead of keeping two that agree only until
 * somebody uses either.
 */
val FACET_COLUMNS = mapOf(
    "st" to FacetGroup.STATUS,
    "method" to FacetGroup.METHOD,
    "type" to FacetGroup.TYPE,
)

/**
 * Whether [row] belongs in the results.
 *
 * Isolation short-circuits everything, which is what "isolate" means. Otherwise
 * the three kinds of criterion are ANDed: facets among themselves per the
 * joiner, then text, then the window. Free text and the time window are always
 * ANDed on top — an OR between "in this second" and "mentions this host" is not
 * a question anybody asks.
 */
fun FlowQuery.matches(row: TrafficRow, origin: Long?, bodyHits: Set<String>? = null): Boolean {
    isolate?.let { return row.id == it }
    return facetHit(row) && textHit(row, bodyHits) && windowHit(row, origin)
}

private fun FlowQuery.facetHit(row: TrafficRow): Boolean {
    val active = facets.filterValues { it.isNotEmpty() }
    if (active.isEmpty()) return true
    // Within a facet the values are always OR — ticking 4xx and 5xx means
    // either, never both, which no flow could be.
    val hits = active.map { (group, values) -> facetValueOf(group, row) in values }
    return if (joinFacetsWithAnd) hits.all { it } else hits.any { it }
}

/**
 * The body first, then the metadata around it.
 *
 * [bodyHits] is the scan's answer, computed off the UI thread and handed in —
 * this stays a pure predicate so it can be tested and so a filter pass never
 * decodes a payload. Null means no scan has landed yet, which is not the same
 * as "no matches": treating it as a miss would blank the grid on every
 * keystroke and fill it back in a beat later.
 *
 * Metadata still matches on top, because a body search that could not find
 * `/orders` in a URL would be a search that stopped answering the question it
 * used to. Host and path are substrings; method and status are exact, so `404`
 * finds the failures rather than every path containing those digits, and `post`
 * finds POSTs rather than every URL with "post" in it — which is most of a blog.
 */
private fun FlowQuery.textHit(row: TrafficRow, bodyHits: Set<String>?): Boolean {
    if (text.isBlank()) return true
    if (bodyHits != null && row.id in bodyHits) return true
    val needle = text.trim()
    val request = row.request.request
    val (host, path) = hostPath(request.url)
    if ("$host$path".contains(needle, ignoreCase = true)) return true
    if (request.method.equals(needle, ignoreCase = true)) return true
    return row.response?.response?.status?.toString() == needle
}

private fun FlowQuery.windowHit(row: TrafficRow, origin: Long?): Boolean {
    val range = window ?: return true
    val offset = offsetOf(row, origin) ?: return false
    return offset in range
}

/** Milliseconds from the first captured flow, or null when the time is unreadable. */
fun offsetOf(row: TrafficRow, origin: Long?): Double? {
    val start = instantOf(row.request.startedDateTime)?.toEpochMilli() ?: return null
    val base = origin ?: return null
    return (start - base).toDouble()
}

/** When the capture started, which every offset is measured from. */
fun originOf(rows: List<TrafficRow>): Long? =
    rows.mapNotNull { instantOf(it.request.startedDateTime)?.toEpochMilli() }.minOrNull()

// --- facet values -----------------------------------------------------------

/**
 * Which value of [group] a row falls under.
 *
 * The derived groups bucket rather than enumerate: a hundred distinct durations
 * is not a list anybody reads, and "was this slow" is the question. The three
 * literal groups take the value as captured.
 */
fun facetValueOf(group: FacetGroup, row: TrafficRow): String = when (group) {
    FacetGroup.STATUS -> statusBucket(row)
    FacetGroup.METHOD -> row.request.request.method.uppercase()
    FacetGroup.TYPE -> kindOfRow(row)
    FacetGroup.HOST -> hostPath(row.request.request.url).first
    FacetGroup.DURATION -> durationBucket(row.response?.time)
    FacetGroup.SIZE -> sizeBucket(row.response?.response?.bodySize)
}

/**
 * `reset` is not a status class — it is a flow that never got one.
 *
 * @param reset what to call that case. The band says "reset" and the grid's Code
 *   column says "ERR", and those are the labels their respective filter lists
 *   show; the classification behind both is the same and was written twice.
 */
fun statusBucket(row: TrafficRow, reset: String = STATUS_RESET): String {
    val response = row.response ?: return reset
    if (response.error) return reset
    val status = response.response.status
    return if (status in 100..599) "${status / 100}xx" else reset
}

fun durationBucket(millis: Double?): String = when {
    millis == null -> DURATION_BUCKETS.last()
    millis < 50 -> DURATION_BUCKETS[0]
    millis < 250 -> DURATION_BUCKETS[1]
    millis < 500 -> DURATION_BUCKETS[2]
    else -> DURATION_BUCKETS[3]
}

fun sizeBucket(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> SIZE_BUCKETS[0]
    bytes < 1024 -> SIZE_BUCKETS[0]
    bytes < 50 * 1024 -> SIZE_BUCKETS[1]
    else -> SIZE_BUCKETS[2]
}

/**
 * The values [group] offers, in the order the column shows them.
 *
 * The fixed groups are fixed so `5xx` and DELETE can be picked before either has
 * happened — which is when picking them is useful. Methods come from the app's
 * one list, the same one the grid filter and the API client's picker offer, so
 * the three panels cannot disagree about what a method is. Host and type are all
 * that is left to derive, and they come from the capture in arrival order,
 * because that is the order they became interesting.
 */
fun facetValues(group: FacetGroup, rows: List<TrafficRow>): List<String> = when (group) {
    FacetGroup.STATUS -> STATUS_BUCKETS
    FacetGroup.METHOD -> HTTP_METHODS
    FacetGroup.DURATION -> DURATION_BUCKETS
    FacetGroup.SIZE -> SIZE_BUCKETS
    else -> rows.map { facetValueOf(group, it) }.filter { it.isNotBlank() }.distinct()
}

/**
 * How many flows each value of [group] would match if it were ticked.
 *
 * Cross-filtered: every *other* facet, the text and the window are applied, and
 * this group's own selection is ignored. That is what makes the numbers useful —
 * a count that included this column's own selection would read zero for every
 * value you had not picked, which tells you nothing about what picking it does.
 */
fun crossFilteredCounts(
    rows: List<TrafficRow>,
    query: FlowQuery,
    group: FacetGroup,
    origin: Long?,
): Map<String, Int> {
    val without = query.copy(facets = query.facets - group, isolate = null)
    val eligible = rows.filter { without.matches(it, origin) }
    return eligible.groupingBy { facetValueOf(group, it) }.eachCount()
}

/** `0.32–0.71 s`, which is how the time token reads. */
fun secondsLabel(window: ClosedFloatingPointRange<Double>): String =
    "%.2f–%.2f s".format(window.start / 1000, window.endInclusive / 1000)

const val STATUS_RESET = "reset"

val STATUS_BUCKETS = listOf("2xx", "3xx", "4xx", "5xx", STATUS_RESET)

val DURATION_BUCKETS = listOf("< 50 ms", "50–250 ms", "250–500 ms", "≥ 500 ms")

val SIZE_BUCKETS = listOf("< 1 KB", "1–50 KB", "≥ 50 KB")
