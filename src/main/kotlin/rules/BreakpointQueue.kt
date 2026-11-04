package org.bittrace.rules

import androidx.compose.runtime.mutableStateListOf
import java.awt.EventQueue
import kotlinx.serialization.json.Json
import org.bittrace.proxy.BreakpointHitData
import org.bittrace.proxy.BreakpointReleasedData
import org.bittrace.proxy.BreakpointResume
import org.bittrace.proxy.Commands

/** One flow held at a breakpoint, with the body as it stood when it paused. */
class PausedFlow(
    val hit: BreakpointHitData,
    val body: ByteArray,
    /** When it arrived here, for the countdown to the sidecar's own timeout. */
    val receivedAtMillis: Long = System.currentTimeMillis(),
) {
    val key: Pair<String, String> get() = hit.id to hit.side
    val isResponse: Boolean get() = hit.side == "response"
    val deadlineMillis: Long get() = receivedAtMillis + hit.timeoutMs
}

/**
 * Flows the sidecar is holding at a breakpoint, oldest first.
 *
 * The sidecar is the authority on what is paused: a flow joins on
 * `BreakpointHit` and leaves on `BreakpointReleased`, whatever released it —
 * this end, the timeout, the client hanging up. [resume] takes the flow off
 * the list straight away as well, so a second click cannot answer it twice;
 * the release that follows then finds nothing to remove.
 *
 * [onHit] lets the app bring the breakpoint window forward: a pause nobody
 * notices just looks like a request that hangs until its timeout.
 */
class BreakpointQueue(
    private val send: (tag: Int, json: String, body: ByteArray) -> Unit,
) {
    val paused = mutableStateListOf<PausedFlow>()

    var onHit: (PausedFlow) -> Unit = {}

    fun onBreakpointHit(data: BreakpointHitData, body: ByteArray) = onUi {
        val flow = PausedFlow(data, body)
        paused.removeAll { it.key == flow.key }
        paused += flow
        onHit(flow)
    }

    fun onBreakpointReleased(data: BreakpointReleasedData) = onUi {
        paused.removeAll { it.key == (data.id to data.side) }
    }

    /** The sidecar is gone; whatever it held went with it. */
    fun clear() = onUi { paused.clear() }

    fun resume(flow: PausedFlow, answer: BreakpointResume, body: ByteArray = ByteArray(0)) {
        paused.removeAll { it.key == flow.key }
        send(Commands.BREAKPOINT_RESUME, json.encodeToString(BreakpointResume.serializer(), answer), body)
    }

    fun continueAll() {
        paused.toList().forEach { resume(it, BreakpointResume(it.hit.id, it.hit.side, "continue")) }
    }

    private companion object {
        val json = Json { encodeDefaults = false; explicitNulls = false }

        inline fun onUi(crossinline block: () -> Unit) {
            if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
        }
    }
}
