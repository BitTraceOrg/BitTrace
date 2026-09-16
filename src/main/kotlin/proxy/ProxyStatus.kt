package org.bittrace.proxy

import kotlinx.serialization.Serializable

/**
 * The sidecar's own account of itself: one frame at startup, one once the
 * listen port is bound, then one per [intervalMs] for as long as it lives.
 *
 * Every other frame is a reaction to traffic, so silence on the stream says
 * nothing — an idle proxy and a dead one look identical, and the difference
 * matters most at exactly the moment nothing is arriving. This is the only
 * frame on a schedule, and the sidecar writes it straight to stdout rather than
 * through its queue, so it keeps coming while a large download has that queue
 * backed up.
 *
 * Unknown fields decode as their defaults: the extras below [mitmproxyVersion]
 * are supplied by the addon and are absent from the `starting` frame, which is
 * sent before there is an addon to ask.
 */
@Serializable
data class ProxyStatus(
    /** [STARTING], [RUNNING] or [STOPPED]. */
    val state: String = "",
    /** The worker's pid — the process that owns the listen port, as on the PID frame. */
    val pid: Long = -1,
    /** The port that was *asked for*; [listenAddrs] is what was actually bound. */
    val port: Int = -1,
    val startedDateTime: String = "",
    val uptimeMs: Long = -1,
    /** How often this frame repeats. Read it rather than assuming 60s. */
    val intervalMs: Long = 60_000,
    val mitmproxyVersion: String = "",
    /**
     * The addresses actually bound, `host:port` each. Empty on a `starting`
     * frame — and empty on a `running` one means the proxy never got its port,
     * which is the one failure that otherwise looks exactly like success.
     */
    val listenAddrs: List<String> = emptyList(),
    /** Open client connections, or `-1` before the addon can count them. */
    val connections: Int = -1,
    val queue: QueueDepth = QueueDepth(),
    /** Flows whose body is mid-capture. */
    val openBodies: Int = 0,
    val counters: Counters = Counters(),
    /** The addon's extras could not be collected; the rest of the frame stands. */
    val statsError: String? = null,
) {

    /**
     * The sidecar's outbound queue. A depth pinned near [maxSize] means this
     * consumer is not draining stdout fast enough and frames are about to be
     * dropped — [Counters.droppedFrames] says whether they already were.
     */
    @Serializable
    data class QueueDepth(val depth: Int = 0, val maxSize: Int = 0)

    /** Cumulative since the sidecar started, not per interval. */
    @Serializable
    data class Counters(
        val requests: Long = 0,
        val responses: Long = 0,
        val errors: Long = 0,
        val connects: Long = 0,
        /**
         * Metadata frames the sidecar discarded because its queue stayed full.
         * Non-zero means entries held here are missing parts that will never
         * arrive. Dropped *body* chunks are counted separately, per body, on
         * [BodyEndMessage].
         */
        val droppedFrames: Long = 0,
    )

    /** Serving traffic: up, and holding a port it can actually be reached on. */
    val bound: Boolean get() = state == RUNNING && listenAddrs.isNotEmpty()

    /**
     * Up but with nothing bound — the failure worth shouting about, because the
     * app looks healthy and captures nothing.
     */
    val portLost: Boolean get() = state == RUNNING && listenAddrs.isEmpty()

    /** How full the sidecar's queue is, 0..1, or 0 when it has not said. */
    val pressure: Float
        get() = if (queue.maxSize <= 0) 0f else queue.depth.toFloat() / queue.maxSize

    /** Where it is listening, for display; falls back to the port it asked for. */
    val address: String get() = listenAddrs.firstOrNull() ?: port.takeIf { it > 0 }?.toString() ?: "—"

    companion object {
        const val STARTING = "starting"
        const val RUNNING = "running"
        const val STOPPED = "stopped"

        /**
         * How many intervals of silence before the sidecar counts as gone.
         *
         * Two rather than one: a single interval leaves no room for a frame
         * delayed behind a busy event loop, and calling a working proxy dead is
         * the more expensive mistake — it is the claim that sends someone
         * restarting something that was fine.
         */
        const val MISSED_INTERVALS = 2
    }
}
