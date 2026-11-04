package org.bittrace.rules

import java.net.URI
import org.bittrace.data.TrafficRow

/** The rule a captured flow's row menu can start. */
enum class RuleSeed(val label: String) {
    MOCK_RESPONSE("Map local — serve this response"),
    MAP_REMOTE("Map remote…"),
    REWRITE("Rewrite…"),
    BREAKPOINT("Breakpoint on this request"),
}

/**
 * Rules pre-filled from a captured flow: matching its method, host and exact
 * path, which is the narrowest rule that still catches the next identical
 * request. Widening it is one edit in the Rules tool, where it opens; a rule
 * that started broad and caught more than intended is the harder mistake to
 * notice.
 */
object FlowRules {

    fun matchFor(row: TrafficRow): RuleMatch {
        val uri = runCatching { URI(row.request.request.url) }.getOrNull()
        return RuleMatch(
            methods = listOf(row.request.request.method),
            host = uri?.host.orEmpty(),
            path = uri?.rawPath?.ifEmpty { "/" } ?: "",
        )
    }

    fun nameFor(row: TrafficRow): String {
        val uri = runCatching { URI(row.request.request.url) }.getOrNull()
        val where = uri?.let { (it.host ?: "") + (it.rawPath ?: "") } ?: row.request.request.url
        return "${row.request.request.method} $where"
    }

    /**
     * The rule for [seed], or null for [RuleSeed.MOCK_RESPONSE] — that one owns
     * a body on disk, so [RuleStore.saveMock] builds it.
     */
    fun ruleFor(row: TrafficRow, seed: RuleSeed): RuleFile? {
        val match = matchFor(row)
        val name = nameFor(row)
        return when (seed) {
            RuleSeed.MOCK_RESPONSE -> null
            RuleSeed.MAP_REMOTE -> RuleFile(
                name = name, kind = RuleKind.MAP_REMOTE.id, match = match,
                mapRemote = MapRemoteSpec(host = match.host),
            )
            RuleSeed.REWRITE -> RuleFile(
                name = name, kind = RuleKind.REWRITE.id, match = match,
                rewrite = listOf(RewriteStep(target = RewriteTarget.RESPONSE_HEADER.id, op = RewriteOp.SET.id)),
            )
            RuleSeed.BREAKPOINT -> RuleFile(
                name = name, kind = RuleKind.BREAKPOINT.id, match = match,
                breakpoint = BreakpointSpec(request = true, response = true),
            )
        }
    }
}

/**
 * Makes the rule [seed] describes from [row] and returns its id. A mock reads
 * the captured response body now, while it is still in the cache, because the
 * rule has to serve it long after the cache has moved on.
 */
fun ruleFromFlow(
    store: RuleStore,
    row: TrafficRow,
    seed: RuleSeed,
    bodyProvider: (String, org.bittrace.proxy.BodySide) -> ByteArray?,
): String {
    FlowRules.ruleFor(row, seed)?.let { return store.create(it) }
    val status = row.response?.response?.status?.takeIf { it in 100..999 }
        ?: error("this flow has no response to serve")
    val body = bodyProvider(row.id, org.bittrace.proxy.BodySide.RESPONSE)
        ?: error("the response body is no longer in the cache")
    val headers = row.completeResponse?.response?.headers.orEmpty().map { HeaderLine(it.name, it.value) }
    return store.saveMock("Mock " + FlowRules.nameFor(row), FlowRules.matchFor(row), status, headers, body)
}
