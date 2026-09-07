package org.bittrace.ui.layouts.inspector.components

import org.bittrace.data.HTTP_METHODS
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData
import org.bittrace.data.TrafficRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the query the overview band builds.
 *
 * This is the one object the flow table, the lanes, the time strip and the
 * status bar all read, so its rules are the app's answer to "what is being
 * shown" in four places at once. The cross-filtered counts especially: a count
 * computed the obvious way reads zero for every value you have not picked,
 * which is exactly when it needed to tell you something.
 */
class FlowQueryTest {

    private var clock = 1_000_000L

    private fun row(
        method: String = "GET",
        url: String = "https://api.example.com/users",
        status: Int = 200,
        error: Boolean = false,
        millis: Double = 100.0,
        size: Long = 2048,
        atMillis: Long = 0,
        id: String = "flow-${clock++}",
    ): TrafficRow {
        val started = java.time.Instant.ofEpochMilli(ORIGIN + atMillis).toString()
        val request = InitialRequestData(
            id = id,
            startedDateTime = started,
            request = InitialRequestData.RequestHead(
                method = method,
                url = url,
                httpVersion = "HTTP/2",
                headersSize = 100,
                bodySize = 0,
            ),
            tls = "TLS 1.3",
        )
        return TrafficRow(1, request).apply {
            response = InitialResponseData(
                id = id,
                serverIPAddress = "203.0.113.1",
                connection = "443",
                timings = org.bittrace.data.HarTimings(0.0, 0.0, 0.0, 0.0, millis, 0.0, 0.0),
                time = millis,
                response = InitialResponseData.ResponseHead(
                    status = status,
                    statusText = "",
                    httpVersion = "HTTP/2",
                    headersSize = 100,
                    bodySize = size,
                    redirectURL = "",
                ),
                error = error,
            )
        }
    }

    private val rows by lazy {
        listOf(
            row(method = "GET", url = "https://api.example.com/users", status = 200, millis = 30.0, size = 500),
            row(method = "POST", url = "https://api.example.com/orders", status = 404, millis = 300.0, size = 20_000),
            row(method = "GET", url = "https://cdn.other.com/app.js", status = 200, millis = 800.0, size = 90_000),
            row(method = "GET", url = "https://api.example.com/health", status = 500, millis = 60.0, size = 100),
        )
    }
    private val origin get() = ORIGIN

    private fun shown(query: FlowQuery) = rows.filter { query.matches(it, origin) }

    // --- the empty query ----------------------------------------------------

    @Test
    fun `an empty query matches everything and says so`() {
        val query = FlowQuery()

        assertTrue(query.isEmpty)
        assertEquals(rows.size, shown(query).size)
        assertEquals("no query", query.describe())
    }

    // --- facets -------------------------------------------------------------

    @Test
    fun `values within one facet are ORed`() {
        val query = FlowQuery(facets = mapOf(FacetGroup.STATUS to setOf("4xx", "5xx")))

        // Never both — no flow is a 404 and a 500 at once, so AND here would
        // always be empty.
        assertEquals(2, shown(query).size)
    }

    @Test
    fun `facet groups are ANDed by default`() {
        val query = FlowQuery(
            facets = mapOf(
                FacetGroup.METHOD to setOf("GET"),
                FacetGroup.STATUS to setOf("5xx"),
            ),
        )

        assertEquals(1, shown(query).size)
    }

    @Test
    fun `the joiner switches groups to OR`() {
        val query = FlowQuery(
            facets = mapOf(
                FacetGroup.METHOD to setOf("POST"),
                FacetGroup.STATUS to setOf("5xx"),
            ),
            joinFacetsWithAnd = false,
        )

        // The POST and the 500 — one each, neither being both.
        assertEquals(2, shown(query).size)
    }

    @Test
    fun `toggling removes a group once its last value goes`() {
        val query = FlowQuery().toggle(FacetGroup.METHOD, "GET").toggle(FacetGroup.METHOD, "GET")

        assertTrue(query.isEmpty)
    }

    @Test
    fun `picking a facet clears an isolate`() {
        // Isolation shows one flow; leaving it on under a new facet would show
        // one row and look like the facet was broken.
        val query = FlowQuery(isolate = "x").toggle(FacetGroup.METHOD, "GET")

        assertEquals(null, query.isolate)
    }

    // --- buckets ------------------------------------------------------------

    @Test
    fun `status buckets by class, and a reset is not a class`() {
        assertEquals("2xx", statusBucket(row(status = 204)))
        assertEquals("4xx", statusBucket(row(status = 404)))
        assertEquals(STATUS_RESET, statusBucket(row(error = true)))
    }

    @Test
    fun `the grid's status class is the band's bucket under another name`() {
        // Two copies of this classification existed, identical bar the word for
        // "never got a status". The grid's Code filter shows ERR, the band shows
        // reset, and only that word may differ.
        listOf(row(status = 100), row(status = 204), row(status = 302), row(status = 404), row(status = 503))
            .forEach { assertEquals(statusBucket(it), statusClassOf(it)) }

        assertEquals(STATUS_RESET, statusBucket(row(error = true)))
        assertEquals("ERR", statusClassOf(row(error = true)))
        assertEquals("ERR", statusClassOf(row(status = 999)))
    }

    @Test
    fun `duration and size fall in the documented buckets`() {
        assertEquals("< 50 ms", durationBucket(49.0))
        assertEquals("50–250 ms", durationBucket(50.0))
        assertEquals("250–500 ms", durationBucket(499.0))
        assertEquals("≥ 500 ms", durationBucket(500.0))

        assertEquals("< 1 KB", sizeBucket(1023))
        assertEquals("1–50 KB", sizeBucket(1024))
        assertEquals("≥ 50 KB", sizeBucket(50 * 1024L))
    }

    @Test
    fun `derived facets offer every bucket before one has happened`() {
        // The point of a fixed list: 5xx is pickable before a 5xx arrives,
        // which is when picking it is useful.
        assertEquals(STATUS_BUCKETS, facetValues(FacetGroup.STATUS, emptyList()))
        assertTrue(facetValues(FacetGroup.HOST, emptyList()).isEmpty())
    }

    @Test
    fun `literal facets come from the capture, without duplicates`() {
        assertEquals(listOf("api.example.com", "cdn.other.com"), facetValues(FacetGroup.HOST, rows))
    }

    @Test
    fun `methods come from the app's one list, not from the capture`() {
        // Three panels used to answer "which methods are there?" differently.
        // The band's answer was the worst of them: derived from the capture, it
        // could not offer DELETE until a DELETE had happened.
        assertEquals(HTTP_METHODS, facetValues(FacetGroup.METHOD, rows))
        assertEquals(HTTP_METHODS, facetValues(FacetGroup.METHOD, emptyList()))
    }

    // --- text ---------------------------------------------------------------

    @Test
    fun `text is a substring of host and path`() {
        assertEquals(3, shown(FlowQuery(text = "api.example.com")).size)
        assertEquals(1, shown(FlowQuery(text = "/orders")).size)
        assertEquals(1, shown(FlowQuery(text = "APP.JS")).size, "case-insensitive")
    }

    @Test
    fun `a status code matches exactly, not as a substring`() {
        assertEquals(1, shown(FlowQuery(text = "404")).size)
        // And not every flow whose path happens to contain those digits.
        assertEquals(0, shown(FlowQuery(text = "40")).size)
    }

    @Test
    fun `a method matches exactly`() {
        assertEquals(1, shown(FlowQuery(text = "post")).size)
    }

    @Test
    fun `a body hit matches even when nothing about the row does`() {
        val target = rows[2]
        val query = FlowQuery(text = "deserialization failed")

        assertEquals(0, shown(query).size, "nothing in the metadata says this")
        assertEquals(
            listOf(target.id),
            rows.filter { query.matches(it, origin, bodyHits = setOf(target.id)) }.map { it.id },
        )
    }

    @Test
    fun `a pending scan is not a miss`() {
        // Null means the scan has not landed. Read as "no matches" it would
        // blank the grid on every keystroke and fill it back in a beat later.
        val query = FlowQuery(text = "api.example.com")

        assertEquals(3, rows.count { query.matches(it, origin, bodyHits = null) })
        assertEquals(3, rows.count { query.matches(it, origin, bodyHits = emptySet()) })
    }

    // --- window and isolate -------------------------------------------------

    @Test
    fun `the window keeps flows inside it`() {
        val early = row(atMillis = 0)
        val late = row(atMillis = 5_000)
        val query = FlowQuery(window = 0.0..1_000.0)

        assertTrue(query.matches(early, origin))
        assertFalse(query.matches(late, origin))
    }

    @Test
    fun `isolate overrides every other criterion`() {
        val target = rows[1]
        // A query that would otherwise exclude it entirely.
        val query = FlowQuery(
            facets = mapOf(FacetGroup.STATUS to setOf("2xx")),
            text = "nothing-matches-this",
            isolate = target.id,
        )

        assertEquals(listOf(target.id), shown(query).map { it.id })
    }

    // --- cross-filtered counts ----------------------------------------------

    @Test
    fun `a facet's own selection is ignored in its own counts`() {
        val query = FlowQuery(facets = mapOf(FacetGroup.METHOD to setOf("POST")))
        val counts = crossFilteredCounts(rows, query, FacetGroup.METHOD, origin)

        // Computed the obvious way, GET would read 0 — and a count that says
        // zero for everything you have not picked tells you nothing about what
        // picking it would do.
        assertEquals(3, counts["GET"])
        assertEquals(1, counts["POST"])
    }

    @Test
    fun `every other facet does narrow the counts`() {
        val query = FlowQuery(facets = mapOf(FacetGroup.HOST to setOf("api.example.com")))
        val counts = crossFilteredCounts(rows, query, FacetGroup.STATUS, origin)

        assertEquals(1, counts["2xx"])
        assertEquals(1, counts["4xx"])
        assertEquals(1, counts["5xx"])
        assertEquals(null, counts["3xx"])
    }

    @Test
    fun `text and window narrow the counts too`() {
        val counts = crossFilteredCounts(rows, FlowQuery(text = "cdn.other.com"), FacetGroup.METHOD, origin)

        assertEquals(1, counts["GET"])
        assertEquals(null, counts["POST"])
    }

    // --- how it reads -------------------------------------------------------

    @Test
    fun `the description is the token strip, written out`() {
        val query = FlowQuery(
            facets = mapOf(FacetGroup.STATUS to setOf("4xx")),
            text = "orders",
        )

        assertEquals("""status:4xx AND text:"orders"""", query.describe())
    }

    @Test
    fun `the joiner shows in the description`() {
        val query = FlowQuery(
            facets = mapOf(
                FacetGroup.STATUS to setOf("4xx"),
                FacetGroup.METHOD to setOf("GET"),
            ),
            joinFacetsWithAnd = false,
        )

        assertEquals("status:4xx OR method:GET", query.describe())
    }

    @Test
    fun `the time window reads in seconds, to two places`() {
        assertEquals("0.32–0.71 s", secondsLabel(320.0..710.0))
    }

    private companion object {
        const val ORIGIN = 1_700_000_000_000L
    }
}
