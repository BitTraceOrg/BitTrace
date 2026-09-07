package org.bittrace.plugin.builtin

import org.bittrace.plugin.PluginHost
import org.bittrace.plugin.flow.FlowAction
import org.bittrace.plugin.flow.FlowActionContext
import org.bittrace.plugin.flow.FlowActionPlugin
import org.bittrace.plugin.flow.FlowBodySide
import org.bittrace.plugin.flow.FlowTarget

/**
 * The bundled traffic-grid actions.
 *
 * These ship with the app but go through the same extension point an external
 * JAR would, which is the point: the seam is exercised by the app itself rather
 * than existing only on paper, so it cannot quietly rot.
 *
 * The grid's own Copy URL / cURL / HAR stay above the separator because they
 * are built from the full captured message — cookies, timings, HTTP version,
 * byte sizes — none of which a plugin-facing target carries, and shrinking the
 * HAR export to fit one would be a real loss for a made-up symmetry.
 */
class BundledFlowActions : FlowActionPlugin {
    override val id = "bittrace.flow-actions"
    override val name = "Flow actions"

    /**
     * The host, kept from [init] for its clipboard.
     *
     * The loader inits a plugin before registering it, so this is set long
     * before any menu can be opened; the null branch below is what a host that
     * skipped the contract would get, not a state this app reaches.
     */
    private var host: PluginHost? = null

    override fun init(host: PluginHost) {
        this.host = host
    }

    /** Copies through the host, and says which way it went. */
    private fun copy(text: String, copied: String, context: FlowActionContext) {
        val host = host
        when {
            host == null -> context.notify("No clipboard available.")
            host.copyToClipboard(text) -> context.notify(copied)
            else -> context.notify("Could not copy to the clipboard.")
        }
    }

    override fun actionsFor(target: FlowTarget): List<FlowAction> = listOf(
        FlowAction(label = "Copy as fetch()", order = 10) { context ->
            copy(fetchOf(target), "Copied a fetch() call for ${target.method} ${target.url}", context)
        },
        FlowAction(
            label = "Copy response body",
            // Shown but dead when there is nothing to copy: a menu whose items
            // move as you go down the grid is worse than one with a grey row.
            enabled = target.body(FlowBodySide.RESPONSE)?.isNotEmpty() == true,
            order = 20,
        ) { context ->
            val body = target.body(FlowBodySide.RESPONSE)
            if (body == null || body.isEmpty()) {
                context.notify("That response body is no longer held in memory.")
            } else {
                copy(body.decodeToString(), "Copied ${body.size} bytes", context)
            }
        },
    )
}

/**
 * The flow as a browser `fetch()` call.
 *
 * Backtick-quoted nothing: every string is a single-quoted JS literal, so the
 * only escaping rule is the quote and the backslash — the same reason the cURL
 * exporter quotes the way it does.
 */
private fun fetchOf(target: FlowTarget): String {
    val headers = target.requestHeaders
        // The browser sets these itself from the request it is about to make;
        // passing them back produces a call the browser will contradict, and
        // some of them it refuses outright.
        .filterNot { it.name.lowercase() in BROWSER_MANAGED }
        .joinToString(",\n    ") { "${jsString(it.name)}: ${jsString(it.value)}" }

    val body = target.body(FlowBodySide.REQUEST)?.takeIf { it.isNotEmpty() }?.decodeToString()

    return buildString {
        append("await fetch(").append(jsString(target.url)).append(", {\n")
        append("  method: ").append(jsString(target.method)).append(",\n")
        if (headers.isNotEmpty()) append("  headers: {\n    ").append(headers).append(",\n  },\n")
        if (body != null) append("  body: ").append(jsString(body)).append(",\n")
        append("});")
    }
}

/** A JS single-quoted literal. Newlines stay escaped so the call is one line. */
private fun jsString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    return "'$escaped'"
}

private val BROWSER_MANAGED = setOf("host", "content-length", "connection")
