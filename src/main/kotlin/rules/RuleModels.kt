@file:OptIn(ExperimentalSerializationApi::class)

package org.bittrace.rules

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Traffic rules as they sit on disk: one YAML file per rule plus a manifest.
 *
 * These are the files MITMConnect reads (`core/Rules.py`), and the schema is
 * pinned in its PROTOCOL.md, "Traffic rules". Two rules for anything added
 * here, both because kaml writes these and ruamel reads them:
 *
 * - **Plain YAML only** — maps, lists, strings, ints, booleans. Nothing that
 *   needs a tag or an anchor, which is where two parsers start to disagree.
 * - **Every field defaulted**, the same promise `Settings` makes, so a file
 *   written by an older build still loads. The kind-specific blocks are null
 *   for the kinds that do not use them and are left out of the file
 *   (`EncodeDefault.Mode.NEVER`) rather than written as `null`.
 *
 * [RuleFile.kind] is a string rather than [RuleKind] for the reason
 * `ApiAuth.type` is: a file naming a kind a later build added still loads, and
 * reads as "unknown" instead of failing the whole folder.
 */

enum class RuleKind(val id: String, val label: String) {
    REWRITE("rewrite", "Rewrite"),
    MAP_LOCAL("mapLocal", "Map local"),
    MAP_REMOTE("mapRemote", "Map remote"),
    BREAKPOINT("breakpoint", "Breakpoint");

    companion object {
        fun of(id: String): RuleKind? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class RuleFile(
    val name: String = "",
    val kind: String = RuleKind.REWRITE.id,
    val match: RuleMatch = RuleMatch(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rewrite: List<RewriteStep>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mapLocal: MapLocalSpec? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mapRemote: MapRemoteSpec? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val breakpoint: BreakpointSpec? = null,
) {
    val ruleKind: RuleKind? get() = RuleKind.of(kind)

    /**
     * The same rule with only its own kind's block — so switching a rule from
     * Map local to Rewrite in the editor does not leave a dead `mapLocal:`
     * block in the file for someone to wonder about.
     */
    fun normalized(): RuleFile = when (ruleKind) {
        RuleKind.REWRITE -> copy(rewrite = rewrite ?: emptyList(), mapLocal = null, mapRemote = null, breakpoint = null)
        RuleKind.MAP_LOCAL -> copy(rewrite = null, mapLocal = mapLocal ?: MapLocalSpec(), mapRemote = null, breakpoint = null)
        RuleKind.MAP_REMOTE -> copy(rewrite = null, mapLocal = null, mapRemote = mapRemote ?: MapRemoteSpec(), breakpoint = null)
        RuleKind.BREAKPOINT -> copy(rewrite = null, mapLocal = null, mapRemote = null, breakpoint = breakpoint ?: BreakpointSpec())
        null -> this
    }
}

/** Every present criterion must hold; an empty one matches anything. */
@Serializable
data class RuleMatch(
    val methods: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
    /** A glob, case-insensitive: `*.example.com`. */
    val host: String = "",
    /** 0 matches any port. */
    val port: Int = 0,
    /** A glob over the path without its query — or a regex, searched, when [pathRegex]. */
    val path: String = "",
    val pathRegex: Boolean = false,
    /** A substring of the query string. */
    val query: String = "",
) {
    /** One line for a list row: methods, then host and path, e.g. `GET *.example.com/api/…`. */
    fun summary(): String {
        val where = buildString {
            if (schemes.isNotEmpty()) append(schemes.joinToString("|")).append("://")
            append(host.ifEmpty { "*" })
            if (port != 0) append(':').append(port)
            append(if (pathRegex) " ~ $path" else path.ifEmpty { "/*" })
            if (query.isNotEmpty()) append(" ?").append(query)
        }
        return if (methods.isEmpty()) where else methods.joinToString(" ") + " " + where
    }
}

enum class RewriteTarget(val id: String, val label: String, val named: Boolean = false) {
    REQUEST_HEADER("requestHeader", "Request header", named = true),
    RESPONSE_HEADER("responseHeader", "Response header", named = true),
    HOST("host", "Host"),
    PATH("path", "Path"),
    URL("url", "URL"),
    QUERY_PARAM("queryParam", "Query parameter", named = true),
    STATUS("status", "Status"),
    REQUEST_BODY("requestBody", "Request body"),
    RESPONSE_BODY("responseBody", "Response body");

    companion object {
        fun of(id: String): RewriteTarget = entries.firstOrNull { it.id == id } ?: REQUEST_HEADER
    }
}

enum class RewriteOp(val id: String, val label: String) {
    ADD("add", "Add"),
    SET("set", "Set"),
    REMOVE("remove", "Remove"),
    REPLACE("replace", "Replace");

    companion object {
        fun of(id: String): RewriteOp = entries.firstOrNull { it.id == id } ?: SET
    }
}

@Serializable
data class RewriteStep(
    val target: String = RewriteTarget.REQUEST_HEADER.id,
    val op: String = RewriteOp.SET.id,
    /** The header or query parameter name, for the targets that have one. */
    val name: String = "",
    val value: String = "",
    /** For [RewriteOp.REPLACE]: the text — or, with [regex], the pattern — to replace. */
    val find: String = "",
    val regex: Boolean = false,
)

@Serializable
data class HeaderLine(val name: String = "", val value: String = "")

@Serializable
data class MapLocalSpec(
    /**
     * A file, or a folder the request path is mapped into. Relative paths are
     * relative to the rules folder, which is where [RuleStore.saveMock] puts
     * the bodies it captures.
     */
    val path: String = "",
    val status: Int = 200,
    val headers: List<HeaderLine> = emptyList(),
)

@Serializable
data class MapRemoteSpec(
    /** Empty keeps the original; likewise [host], [path], and 0 for [port]. */
    val scheme: String = "",
    val host: String = "",
    val port: Int = 0,
    /**
     * A target ending in `*` (`/v2/` then `*`) swaps the match path's literal
     * prefix and keeps the rest; without the `*` it replaces the whole path.
     */
    val path: String = "",
    val preserveHost: Boolean = false,
)

@Serializable
data class BreakpointSpec(
    val request: Boolean = true,
    val response: Boolean = false,
    /**
     * Also pause the API client's own sends. Off by default: a Forge send
     * paused behind a window nobody is looking at reads as a hang.
     */
    val includeApiClient: Boolean = false,
)

/** `rules.yaml`: what is in force, and in what order. */
@Serializable
data class RuleManifest(
    val enabled: Boolean = true,
    val breakpointTimeoutSec: Int = 300,
    val kinds: KindSwitches = KindSwitches(),
    val rules: List<ManifestEntry> = emptyList(),
)

@Serializable
data class KindSwitches(
    val rewrite: Boolean = true,
    val mapLocal: Boolean = true,
    val mapRemote: Boolean = true,
    val breakpoint: Boolean = true,
) {
    operator fun get(kind: RuleKind): Boolean = when (kind) {
        RuleKind.REWRITE -> rewrite
        RuleKind.MAP_LOCAL -> mapLocal
        RuleKind.MAP_REMOTE -> mapRemote
        RuleKind.BREAKPOINT -> breakpoint
    }

    fun with(kind: RuleKind, on: Boolean): KindSwitches = when (kind) {
        RuleKind.REWRITE -> copy(rewrite = on)
        RuleKind.MAP_LOCAL -> copy(mapLocal = on)
        RuleKind.MAP_REMOTE -> copy(mapRemote = on)
        RuleKind.BREAKPOINT -> copy(breakpoint = on)
    }
}

@Serializable
data class ManifestEntry(val id: String, val enabled: Boolean = true)
