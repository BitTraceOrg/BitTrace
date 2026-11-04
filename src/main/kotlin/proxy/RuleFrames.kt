package org.bittrace.proxy

import kotlinx.serialization.Serializable
import org.bittrace.data.NameValuePair

/**
 * The frames traffic rules add to the protocol, in both directions. The wire
 * shapes are pinned in MITMConnect's PROTOCOL.md, "Traffic rules"; what each
 * one means to the app is on [org.bittrace.rules.RuleStore] and
 * [org.bittrace.rules.BreakpointQueue].
 */

/**
 * One load attempt, for a rule ([id] is its id) or for the manifest ([id] is
 * `"rules"`). A failed attempt leaves the previously loaded version in force,
 * so `ok = false` means "this file is not what is running", not "nothing is".
 */
@Serializable
data class RuleStatusData(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
    val removed: Boolean = false,
    val loadedAt: Double = 0.0,
)

/** A flow paused at a breakpoint. The frame's body segment is that side's decoded body. */
@Serializable
data class BreakpointHitData(
    val id: String,
    val side: String,
    val ruleId: String = "",
    val ruleName: String = "",
    val pausedAt: String = "",
    val timeoutMs: Long = 0,
    val request: PausedRequest,
    val response: PausedResponse? = null,
) {
    @Serializable
    data class PausedRequest(
        val method: String,
        val url: String,
        val httpVersion: String = "",
        val headers: List<NameValuePair> = emptyList(),
    )

    @Serializable
    data class PausedResponse(
        val status: Int,
        val statusText: String = "",
        val httpVersion: String = "",
        val headers: List<NameValuePair> = emptyList(),
    )
}

/** [reason] is `resumed`, `timeout`, `clientGone` or `appGone`. */
@Serializable
data class BreakpointReleasedData(
    val id: String,
    val side: String,
    val reason: String = "",
    val action: String = "continue",
)

/**
 * The answer to a [BreakpointHitData]. [action] is `continue`, `abort` or
 * `edit`; with `edit`, the non-null parts of [request] / [response] replace
 * what was paused, and a [replaceBody] flag says the frame's body segment is
 * the new body (decoded — the sidecar reapplies any `Content-Encoding`).
 */
@Serializable
data class BreakpointResume(
    val id: String,
    val side: String,
    val action: String,
    val request: RequestEdit? = null,
    val response: ResponseEdit? = null,
    val replaceBody: Boolean = false,
) {
    @Serializable
    data class RequestEdit(
        val method: String? = null,
        val url: String? = null,
        val headers: List<NameValuePair>? = null,
    )

    @Serializable
    data class ResponseEdit(
        val status: Int? = null,
        val statusText: String? = null,
        val headers: List<NameValuePair>? = null,
    )
}
