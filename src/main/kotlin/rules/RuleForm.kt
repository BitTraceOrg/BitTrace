package org.bittrace.rules

/**
 * A rule as the editor holds it: every field as the text in its box.
 *
 * Not the [RuleFile] itself, because a field bound straight to a parsed value
 * fights the person typing into it — `GET, ` would be split, re-joined as `GET`
 * and lose the comma on every keystroke, and a port field could never pass
 * through empty on its way from `80` to `8080`. The text is kept as typed and
 * turned into a rule once, on save, where a bad number becomes a message rather
 * than a silently different rule.
 */
data class RuleForm(
    val name: String = "",
    val kind: RuleKind = RuleKind.REWRITE,
    val methods: String = "",
    val schemes: String = "",
    val host: String = "",
    val port: String = "",
    val path: String = "",
    val pathRegex: Boolean = false,
    val query: String = "",
    val steps: List<RewriteStep> = emptyList(),
    val localPath: String = "",
    val localStatus: String = "200",
    val localHeaders: String = "",
    val remoteScheme: String = "",
    val remoteHost: String = "",
    val remotePort: String = "",
    val remotePath: String = "",
    val preserveHost: Boolean = false,
    val pauseRequest: Boolean = true,
    val pauseResponse: Boolean = false,
    val includeApiClient: Boolean = false,
) {
    /** The rule this form describes, or the reason it does not describe one. */
    fun toRule(): Result<RuleFile> = runCatching {
        val match = RuleMatch(
            methods = words(methods).map { it.uppercase() },
            schemes = words(schemes).map { it.lowercase() },
            host = host.trim(),
            port = number(port, "Port", 0, 0..65535),
            path = path.trim(),
            pathRegex = pathRegex,
            query = query.trim(),
        )
        if (pathRegex) {
            runCatching { Regex(match.path) }.onFailure { throw IllegalArgumentException("Path is not a valid regex: ${it.message}") }
        }
        val base = RuleFile(name = name.trim(), kind = kind.id, match = match)
        when (kind) {
            RuleKind.REWRITE -> {
                require(steps.isNotEmpty()) { "Add at least one step" }
                steps.forEachIndexed { i, step -> validate(step, i + 1) }
                base.copy(rewrite = steps)
            }
            RuleKind.MAP_LOCAL -> {
                require(localPath.isNotBlank()) { "Choose a file or folder to serve" }
                base.copy(
                    mapLocal = MapLocalSpec(
                        path = localPath.trim(),
                        status = number(localStatus, "Status", 200, 100..999),
                        headers = HeaderText.parseLines(localHeaders),
                    ),
                )
            }
            RuleKind.MAP_REMOTE -> {
                val spec = MapRemoteSpec(
                    scheme = remoteScheme.trim().lowercase(),
                    host = remoteHost.trim(),
                    port = number(remotePort, "Port", 0, 0..65535),
                    path = remotePath.trim(),
                    preserveHost = preserveHost,
                )
                require(spec.scheme.isEmpty() || spec.scheme == "http" || spec.scheme == "https") {
                    "Scheme must be http or https"
                }
                require(spec.scheme.isNotEmpty() || spec.host.isNotEmpty() || spec.port != 0 || spec.path.isNotEmpty()) {
                    "Map remote changes nothing — set a host, scheme, port or path"
                }
                base.copy(mapRemote = spec)
            }
            RuleKind.BREAKPOINT -> {
                require(pauseRequest || pauseResponse) { "Pause on the request, the response, or both" }
                base.copy(breakpoint = BreakpointSpec(pauseRequest, pauseResponse, includeApiClient))
            }
        }
    }

    companion object {
        fun of(rule: RuleFile): RuleForm {
            val local = rule.mapLocal ?: MapLocalSpec()
            val remote = rule.mapRemote ?: MapRemoteSpec()
            val bp = rule.breakpoint ?: BreakpointSpec()
            return RuleForm(
                name = rule.name,
                kind = rule.ruleKind ?: RuleKind.REWRITE,
                methods = rule.match.methods.joinToString(", "),
                schemes = rule.match.schemes.joinToString(", "),
                host = rule.match.host,
                port = rule.match.port.takeIf { it != 0 }?.toString().orEmpty(),
                path = rule.match.path,
                pathRegex = rule.match.pathRegex,
                query = rule.match.query,
                steps = rule.rewrite.orEmpty(),
                localPath = local.path,
                localStatus = local.status.toString(),
                localHeaders = HeaderText.formatLines(local.headers),
                remoteScheme = remote.scheme,
                remoteHost = remote.host,
                remotePort = remote.port.takeIf { it != 0 }?.toString().orEmpty(),
                remotePath = remote.path,
                preserveHost = remote.preserveHost,
                pauseRequest = bp.request,
                pauseResponse = bp.response,
                includeApiClient = bp.includeApiClient,
            )
        }

        private fun words(text: String): List<String> =
            text.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }

        private fun number(text: String, label: String, empty: Int, range: IntRange): Int {
            val t = text.trim()
            if (t.isEmpty()) return empty
            val n = t.toIntOrNull() ?: throw IllegalArgumentException("$label must be a number")
            require(n in range) { "$label must be between ${range.first} and ${range.last}" }
            return n
        }

        private fun validate(step: RewriteStep, n: Int) {
            val target = RewriteTarget.of(step.target)
            val op = RewriteOp.of(step.op)
            if (target.named) require(step.name.isNotBlank()) { "Step $n: name the ${target.label.lowercase()}" }
            if (op == RewriteOp.REPLACE) {
                require(step.find.isNotEmpty()) { "Step $n: say what to replace" }
                if (step.regex) {
                    runCatching { Regex(step.find) }
                        .onFailure { throw IllegalArgumentException("Step $n: not a valid regex: ${it.message}") }
                }
            }
            if (target == RewriteTarget.STATUS) {
                require(op == RewriteOp.SET) { "Step $n: a status can only be set" }
                val code = step.value.trim().toIntOrNull()
                require(code != null && code in 100..999) { "Step $n: the status must be a code like 404" }
            }
        }
    }
}
