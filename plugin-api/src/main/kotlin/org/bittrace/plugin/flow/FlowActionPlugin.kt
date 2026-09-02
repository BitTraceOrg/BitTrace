package org.bittrace.plugin.flow

import org.bittrace.plugin.Plugin

/** One header on a captured flow. */
class FlowHeader(val name: String, val value: String)

/** Which half of an exchange a body belongs to. */
enum class FlowBodySide { REQUEST, RESPONSE }

/**
 * The captured flow a traffic-grid menu was opened on.
 *
 * A plain record rather than the app's own row: that row is a live snapshot
 * object wired into the capture pipeline, and handing it out would make every
 * internal change to capture a breaking change to plugins.
 *
 * [body] is a function, not a field, for a reason worth knowing: bodies are
 * evicted under memory pressure and a large one is expensive to hold, so it is
 * fetched when an action actually runs rather than when the menu is built.
 * Null means there was no body, or there no longer is one.
 *
 * @property status the response status, or null while the flow is still in
 *   flight — an action that formats a response should handle both.
 */
class FlowTarget(
    val id: String,
    val method: String,
    val url: String,
    val status: Int?,
    val startedDateTime: String,
    val requestHeaders: List<FlowHeader>,
    val responseHeaders: List<FlowHeader>,
    val body: (FlowBodySide) -> ByteArray?,
)

/**
 * What an action can ask of the host while it runs.
 *
 * One method, so it is a `fun interface` — a host wiring this up writes a
 * lambda rather than an object expression, and there is nothing here a second
 * verb would be doing that the grid does not already do for itself.
 */
fun interface FlowActionContext {

    /**
     * Shows a one-line message in the traffic view's notice strip. For a result
     * or a failure — it is not a progress channel, and the last call wins.
     */
    fun notify(message: String)
}

/**
 * One item in a traffic-grid row's context menu.
 *
 * @property enabled a disabled item is still shown. An action that never
 *   applies should not be returned; one that applies but cannot run against
 *   *this* flow — needing a response that has not arrived, say — should be
 *   returned disabled, so the menu does not change shape row to row.
 * @property order lower sorts first. Ties keep plugin load order, so a plugin's
 *   own items stay in the order it listed them.
 * @property perform what the item does. Runs on the UI thread — hand anything
 *   slow to a thread of your own and report back through [FlowActionContext.notify].
 */
class FlowAction(
    val label: String,
    val enabled: Boolean = true,
    val order: Int = 100,
    val perform: (FlowActionContext) -> Unit,
)

/**
 * A plugin category that adds items to a captured flow's context menu.
 *
 * [actionsFor] is called each time a menu opens, and returns however many items
 * that flow deserves — none, one, or several. Returning a list rather than a
 * single action is the point: "copy as fetch, copy as HTTPie, save the body" is
 * one plugin's worth of thought about a flow, not three plugins.
 *
 * The host's own copy actions come first and are not replaceable; plugin items
 * follow, below a separator. Keep [actionsFor] cheap — it runs on every menu
 * open, so decide from the [FlowTarget]'s metadata rather than by reading its
 * body.
 */
interface FlowActionPlugin : Plugin {

    /** The items to offer for [target], or an empty list to add nothing. */
    fun actionsFor(target: FlowTarget): List<FlowAction>
}
