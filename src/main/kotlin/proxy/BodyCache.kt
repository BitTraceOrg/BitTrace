package org.bittrace.proxy

/** Which side of a flow a body belongs to. */
enum class BodySide {
    REQUEST,
    RESPONSE;

    companion object {
        /** Parses the `side` argument used by the `get_body` command. */
        fun fromString(value: String): BodySide? = when (value.lowercase()) {
            "request" -> REQUEST
            "response" -> RESPONSE
            else -> null
        }
    }
}

/**
 * Raw request/response bodies, kept out of the metadata events and fetched on
 * demand (which is why the metadata carries only sizes and mime types).
 *
 * Bounded by flow id: once [capacity] ids are held, the oldest id is evicted
 * with both of its bodies. Safe to use from the reader thread and the UI thread.
 */
class BodyCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private class Entry {
        var request: ByteArray? = null
        var response: ByteArray? = null
    }

    /**
     * Bytes currently held, kept as a running total.
     *
     * Maintained on every put and eviction rather than summed on demand: the
     * only caller is a readout that can be asked for it at any moment, and
     * walking ten thousand arrays to answer would make looking at the number
     * cost more than holding them does.
     */
    private var byteCount = 0L

    /** Access-independent insertion order, so eviction is FIFO by first sight. */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size <= capacity) return false
            // The eviction is decided here, so this is the one place that knows
            // the bytes are about to leave.
            byteCount -= (eldest.value.request?.size ?: 0) + (eldest.value.response?.size ?: 0)
            return true
        }
    }

    val size: Int get() = synchronized(entries) { entries.size }

    /** How much body data is held right now, in bytes. */
    val bytes: Long get() = synchronized(entries) { byteCount }

    /** Stores a raw body for a flow id + side. */
    fun put(id: String, side: BodySide, body: ByteArray) = synchronized(entries) {
        val entry = entries.getOrPut(id) { Entry() }
        // A side that is written twice replaces its array, so the total has to
        // lose the old one — otherwise a re-sent flow inflates the count forever.
        val previous = when (side) {
            BodySide.REQUEST -> entry.request
            BodySide.RESPONSE -> entry.response
        }
        byteCount += body.size - (previous?.size ?: 0)
        when (side) {
            BodySide.REQUEST -> entry.request = body
            BodySide.RESPONSE -> entry.response = body
        }
    }

    /** The cached body for a flow id + side, or null if it was never seen or was evicted. */
    fun get(id: String, side: BodySide): ByteArray? = synchronized(entries) {
        val entry = entries[id] ?: return null
        when (side) {
            BodySide.REQUEST -> entry.request
            BodySide.RESPONSE -> entry.response
        }
    }

    /** Drops all cached bodies (e.g. when the user clears captured traffic). */
    fun clear() = synchronized(entries) { entries.clear(); byteCount = 0L }

    private companion object {
        /**
         * Maximum number of flow ids whose bodies are retained in memory.
         * Oldest are evicted first.
         */
        const val DEFAULT_CAPACITY = 5_000
    }
}
