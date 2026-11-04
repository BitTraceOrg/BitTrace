package org.bittrace.plugin.builtin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.format.FormattedBody
import org.bittrace.plugin.format.Span
import org.bittrace.plugin.format.TokenKind
import java.time.Instant
import java.util.Base64

/**
 * A JSON Web Token, decoded but **not verified**.
 *
 * Verifying needs the issuer's key, which a proxy never has, so nothing here
 * claims a token is genuine — only what it says. Anything shown from this is
 * labelled unverified.
 */
class DecodedJwt(
    val raw: String,
    val header: JsonObject,
    val payload: JsonObject,
) {
    val alg: String? get() = header.string("alg")
    val typ: String? get() = header.string("typ")
    val expiresAt: Instant? get() = payload.epoch("exp")
    val issuedAt: Instant? get() = payload.epoch("iat")
    val notBefore: Instant? get() = payload.epoch("nbf")
    val hasSignature: Boolean get() = raw.substringAfterLast('.').isNotEmpty()

    fun expired(now: Instant = Instant.now()): Boolean = expiresAt?.isBefore(now) == true

    val headerJson: String get() = pretty.encodeToString(JsonElement.serializer(), header)
    val payloadJson: String get() = pretty.encodeToString(JsonElement.serializer(), payload)

    private companion object {
        val pretty = Json { prettyPrint = true }

        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        /** NumericDate is seconds since the epoch; a fractional one is truncated. */
        fun JsonObject.epoch(key: String): Instant? {
            val p = this[key] as? JsonPrimitive ?: return null
            val seconds = p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() ?: return null
            return runCatching { Instant.ofEpochSecond(seconds) }.getOrNull()
        }
    }
}

/**
 * Decodes [token] as a JWS compact serialisation, or null when it is not one.
 *
 * Strict on purpose: both halves must be base64url JSON *objects*. A loose
 * match would label any three dot-separated words a token.
 */
fun decodeJwt(token: String): DecodedJwt? {
    val parts = token.trim().split('.')
    if (parts.size != 3) return null
    val header = decodeSegment(parts[0]) ?: return null
    val payload = decodeSegment(parts[1]) ?: return null
    return DecodedJwt(token.trim(), header, payload)
}

private fun decodeSegment(segment: String): JsonObject? {
    if (segment.isEmpty()) return null
    val bytes = runCatching { Base64.getUrlDecoder().decode(segment.trimEnd('=')) }.getOrNull() ?: return null
    val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        .getOrNull() ?: return null
    return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

/**
 * Every token in [text] that decodes, in order and without repeats.
 *
 * A JWT's header and payload are JSON objects, so both segments start `eyJ`
 * (`{"` in base64) — which is what keeps the scan from matching every dotted
 * identifier in a body.
 */
fun findJwts(text: String): List<DecodedJwt> =
    JWT_PATTERN.findAll(text)
        .map { it.value }
        .distinct()
        .mapNotNull(::decodeJwt)
        .toList()

private val JWT_PATTERN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")

/** "in 12m", "3h ago" — how far [instant] is from [now], for a readout. */
fun relativeTo(instant: Instant, now: Instant = Instant.now()): String {
    val seconds = instant.epochSecond - now.epochSecond
    val abs = kotlin.math.abs(seconds)
    val amount = when {
        abs < 60 -> "${abs}s"
        abs < 3_600 -> "${abs / 60}m"
        abs < 86_400 -> "${abs / 3_600}h"
        else -> "${abs / 86_400}d"
    }
    return if (seconds >= 0) "in $amount" else "$amount ago"
}

/**
 * The JWT chip: every token in the body, decoded.
 *
 * Claims `application/jwt`, where the body *is* a token, and is offered on any
 * other body for the commoner case — a token endpoint answering JSON with an
 * `access_token` and `id_token` inside.
 */
class JwtFormatter : BodyFormatter {
    override val id = "bittrace.jwt"
    override val name = "JWT"
    override fun handles(mimeType: String) = mimeType.contains("jwt", ignoreCase = true)

    override fun format(bytes: ByteArray, mimeType: String): String = highlight(bytes, mimeType).text

    override fun highlight(bytes: ByteArray, mimeType: String): FormattedBody {
        val text = textOrNull(bytes) ?: return FormattedBody("Body is not text.")
        val tokens = findJwts(text)
        if (tokens.isEmpty()) return FormattedBody("No JSON Web Token found in this body.")

        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        fun comment(line: String) {
            val start = out.length
            out.append("// ").append(line)
            spans += Span(start, out.length, TokenKind.COMMENT)
            out.append('\n')
        }
        fun json(body: String) {
            val start = out.length
            out.append(body).append('\n')
            highlightJson(body).forEach { spans += Span(it.start + start, it.end + start, it.kind) }
        }

        tokens.forEachIndexed { i, jwt ->
            if (i > 0) out.append('\n')
            comment(buildString {
                append("token ${i + 1} of ${tokens.size}")
                jwt.alg?.let { append(" · alg $it") }
                append(" · signature not verified")
            })
            jwt.expiresAt?.let { comment("exp $it (${if (jwt.expired()) "expired " else "expires "}${relativeTo(it)})") }
            jwt.issuedAt?.let { comment("iat $it (${relativeTo(it)})") }
            jwt.notBefore?.let { comment("nbf $it (${relativeTo(it)})") }
            comment("header")
            json(jwt.headerJson)
            comment("payload")
            json(jwt.payloadJson)
        }
        return FormattedBody(out.toString().trimEnd('\n'), spans)
    }
}
