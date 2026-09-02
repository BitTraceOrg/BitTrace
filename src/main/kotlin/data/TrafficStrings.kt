package org.bittrace.data

import java.util.concurrent.ConcurrentHashMap

/**
 * The pool of shared strings behind captured traffic.
 *
 * Every flow repeats the same handful of header names (`content-type`,
 * `server`, …) and the same handful of predictable values (`gzip`,
 * `keep-alive`, `no-cache`, …). Over thousands of flows that is thousands of
 * equal `String` objects — and, for a header that repeats verbatim, thousands
 * of equal [NameValuePair] wrappers too. The interning serializers hand every
 * decoded header and cookie through here, so what the store retains is one
 * shared instance per distinct string rather than one per flow.
 *
 * Only *predictable* values are pooled. High-cardinality values (cookie values,
 * dates, etags, request ids) are passed through: pooling them would grow the
 * pool without ever getting a second hit, costing memory rather than saving it.
 *
 * Decoding runs on the sidecar reader thread while the UI reads on the event
 * thread, so the maps are concurrent. Entries live for the session — that is
 * the point, they are the shared instances — and both maps are capped so a
 * capture full of unique header names cannot turn this into a leak.
 */
object TrafficStrings {

    /** Ceiling per map; past it the pool stops growing and passes values through. */
    private const val MAX_ENTRIES = 4096

    private val strings = ConcurrentHashMap<String, String>()
    private val pairs = ConcurrentHashMap<NameValuePair, NameValuePair>()

    /** The shared instance equal to [value], pooling it if there's room. */
    fun intern(value: String): String {
        if (value.isEmpty()) return ""
        strings[value]?.let { return it }
        if (strings.size >= MAX_ENTRIES) return value
        return strings.putIfAbsent(value, value) ?: value
    }

    /**
     * A pair with a pooled name, plus a pooled value when that header's values
     * come from a small vocabulary. A fully pooled pair is itself pooled, so
     * repeats of e.g. `Connection: keep-alive` share one object across flows.
     */
    fun pair(name: String, value: String): NameValuePair {
        val pooledName = intern(name)
        if (!hasPooledValues(name)) return NameValuePair(pooledName, value)

        val pair = NameValuePair(pooledName, intern(value))
        pairs[pair]?.let { return it }
        if (pairs.size >= MAX_ENTRIES) return pair
        return pairs.putIfAbsent(pair, pair) ?: pair
    }

    private fun hasPooledValues(name: String): Boolean = name.lowercase() in POOLED_VALUE_HEADERS

    /**
     * Headers whose values come from a small vocabulary. Deliberately excludes
     * the per-flow ones (`date`, `etag`, `set-cookie`, `location`,
     * `content-length`, auth tokens, request ids).
     */
    private val POOLED_VALUE_HEADERS = setOf(
        "accept", "accept-charset", "accept-encoding", "accept-language", "accept-ranges",
        "access-control-allow-credentials", "access-control-allow-headers",
        "access-control-allow-methods", "access-control-allow-origin", "alt-svc",
        "cache-control", "cf-cache-status", "connection", "content-encoding", "content-language",
        "content-type", "cross-origin-opener-policy", "cross-origin-resource-policy",
        "dnt", "expect", "pragma", "referrer-policy", "sec-fetch-dest", "sec-fetch-mode",
        "sec-fetch-site", "sec-fetch-user", "server", "strict-transport-security", "te",
        "transfer-encoding", "upgrade", "upgrade-insecure-requests", "user-agent", "vary", "via",
        "x-cache", "x-content-type-options", "x-frame-options", "x-powered-by", "x-xss-protection",
    )
}
