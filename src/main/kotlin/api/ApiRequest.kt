package org.bittrace.api

import com.fasterxml.jackson.core.JsonFactory
import kotlinx.serialization.Serializable
import java.io.StringWriter
import java.util.Base64

/**
 * One authored request, as stored in a collection's YAML file.
 *
 * Headers and params are ordered lists rather than maps for three reasons that
 * all matter in practice: HTTP allows a header to repeat, order is sometimes
 * significant, and a row that is kept but switched off is the most-used feature
 * of any API client — none of which a map can express.
 */
@Serializable
data class ApiRequest(
    val name: String = "New request",
    val method: String = "GET",
    val url: String = "",
    val params: List<KeyValue> = emptyList(),
    val headers: List<KeyValue> = emptyList(),
    /** Sent as one `Cookie` header; kept apart so each is editable on its own. */
    val cookies: List<KeyValue> = emptyList(),
    /** How the request proves who it is; applied at send time, not stored as a header. */
    val auth: ApiAuth = ApiAuth(),
    val body: ApiBody = ApiBody(),
    /** How this request is sent, where it differs from the app's defaults. */
    val settings: RequestSettings = RequestSettings(),
    /**
     * The timeout, as requests written before there were settings stored it.
     *
     * Read on the way in and folded into [settings] by `RequestYaml`, never
     * written back. Kept only so that a value somebody deliberately changed is
     * not lost the first time an old request is opened; see the migration there
     * for why a 30 000 is dropped rather than carried across.
     */
    val timeoutMs: Long? = null,
) {
    /** Header/param rows that are switched on, with a name filled in. */
    fun activeHeaders(): List<KeyValue> = headers.filter { it.enabled && it.name.isNotBlank() }

    fun activeParams(): List<KeyValue> = params.filter { it.enabled && it.name.isNotBlank() }

    fun activeCookies(): List<KeyValue> = cookies.filter { it.enabled && it.name.isNotBlank() }
}

/** A header or query parameter row. */
@Serializable
data class KeyValue(
    val name: String = "",
    val value: String = "",
    val enabled: Boolean = true,
)

/**
 * The request body: either typed text, or a file sent from disk.
 *
 * A file keeps its path rather than its bytes, so a collection stays small and
 * a large upload never has to sit in memory — and re-opening the request picks
 * up whatever the file says now.
 */
@Serializable
data class ApiBody(
    val contentType: String = "",
    val text: String = "",
    val filePath: String = "",
    /**
     * The GraphQL variables, as JSON, kept apart from the operation in [text].
     *
     * Two fields rather than one, because they are two things: an operation is
     * GraphQL and variables are JSON, they want different highlighting and
     * different halves of the editor, and a single blob would have to be pulled
     * apart and put back together on every keystroke to offer either.
     */
    val graphqlVariables: String = "",
) {
    val fromFile: Boolean get() = filePath.isNotBlank()

    val isEmpty: Boolean get() = text.isEmpty() && !fromFile

    /** Whether this body is an operation plus variables rather than plain text. */
    val isGraphQl: Boolean get() = contentType.contains("graphql", ignoreCase = true)

    /**
     * What goes on the wire.
     *
     * For GraphQL that is the JSON envelope every server actually reads —
     * `{"query": …, "variables": …}` — assembled here rather than stored,
     * exactly as [ApiAuth] assembles its header at send time. Storing the
     * envelope instead would mean parsing it apart to edit either half.
     */
    fun payload(): String = if (isGraphQl) graphqlEnvelope(text, graphqlVariables) else text

    /**
     * The `Content-Type` this body is sent with.
     *
     * `application/graphql` is the content type of a *raw* operation, and almost
     * nothing accepts it. What this app sends is the JSON envelope, so it says
     * so — [contentType] stays the marker for which editor to show, and this is
     * what the server is told.
     */
    fun wireContentType(): String = if (isGraphQl) "application/json" else contentType
}

/**
 * `{"query": …, "variables": …}`.
 *
 * The operation is written as a JSON string, so quotes and newlines inside it
 * are escaped properly rather than by hand. Variables go in as raw JSON: the
 * point of the pane is that you type JSON into it, and re-encoding what you
 * typed would turn an object into a string. Blank variables are left out
 * entirely — `"variables": null` is a thing some servers reject.
 *
 * Variables that are not valid JSON are still embedded verbatim. That produces a
 * body the server will refuse, which is the correct outcome: this app sends what
 * you wrote and shows you the answer.
 */
private fun graphqlEnvelope(operation: String, variables: String): String {
    val out = StringWriter()
    JsonFactory().createGenerator(out).use { json ->
        json.writeStartObject()
        json.writeStringField("query", operation)
        val trimmed = variables.trim()
        if (trimmed.isNotEmpty()) {
            json.writeFieldName("variables")
            json.writeRawValue(trimmed)
        }
        json.writeEndObject()
    }
    return out.toString()
}

/**
 * How the request authenticates.
 *
 * One flat record rather than one class per scheme. A sealed hierarchy would be
 * the tidier model, but it would also discard whatever you had typed the moment
 * you looked at another scheme — and flipping between Basic and Bearer to see
 * which one the server wants is exactly what this panel is for. Every scheme
 * keeps its own fields; [type] decides which are read.
 *
 * [type] is a `String` for the same reason [ApiRequest.method] is: a request
 * saved by a later build may name a scheme this one has never heard of, and it
 * should load — sending nothing — rather than fail to parse the whole file.
 *
 * Auth is deliberately *not* materialised into [ApiRequest.headers]. The header
 * table is what you typed, and writing a derived `Authorization` into it would
 * make the credential something you have to edit as base64, and would leave a
 * stale one behind the moment the username changed.
 *
 * @property scheme the word before a bearer token — `Bearer`, `Token`, `JWT`.
 * @property keyName the header or query parameter an API key is sent as.
 * @property keyIn [KEY_IN_HEADER] or [KEY_IN_QUERY].
 */
@Serializable
data class ApiAuth(
    val type: String = AUTH_NONE,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val scheme: String = "Bearer",
    val keyName: String = "",
    val keyValue: String = "",
    val keyIn: String = KEY_IN_HEADER,

    // --- OAuth 1.0 ----------------------------------------------------------
    val consumerKey: String = "",
    val consumerSecret: String = "",
    val oauthToken: String = "",
    val oauthTokenSecret: String = "",
    val signatureMethod: String = SIG_HMAC_SHA1,
    /** Sent in the header but never signed, per RFC 5849 §3.4.1.3.1. */
    val realm: String = "",
    /** PKCS#8 PEM, for RSA-SHA1. A path rather than the key, so it is not copied into a collection. */
    val privateKeyPath: String = "",

    // --- OAuth 2.0 ----------------------------------------------------------
    val grantType: String = GRANT_AUTH_CODE,
    val clientId: String = "",
    val clientSecret: String = "",
    val authUrl: String = "",
    val tokenUrl: String = "",
    val deviceAuthUrl: String = "",
    /** The loopback port the redirect comes back on. */
    val redirectPort: Int = 8081,
    val redirectPath: String = "/callback",
    val scope: String = "",
    val audience: String = "",
    /** Client id and secret in the form body rather than a Basic header. */
    val credentialsInBody: Boolean = false,
    val usePkce: Boolean = true,

    // --- OAuth 2.0, JWT Bearer grant (RFC 7523) ------------------------------
    val jwtAlgorithm: String = JWT_RS256,
    val jwtIssuer: String = "",
    /** Who the token is for. Defaults to the issuer, which is right as often as not. */
    val jwtSubject: String = "",
    /** Usually the token URL, which is what it falls back to. */
    val jwtAudience: String = "",
    /** `kid`, for a provider that rotates signing keys. */
    val jwtKeyId: String = "",
    val jwtValiditySeconds: Long = 300,
) {
    /**
     * The header this auth sends, or null when it sends none — no scheme
     * chosen, or the key going into the query instead.
     *
     * An empty field is sent as empty rather than suppressing the header. What
     * a server does with `Bearer ` or with base64 of `:` is worth being able to
     * find out — that is the kind of question this app exists to answer — and a
     * header that silently vanishes because one box is blank is the harder
     * thing to debug. The only requirement left is structural: a header needs a
     * name, so an unnamed API key sends nothing.
     */
    fun header(): Pair<String, String>? = when (type) {
        AUTH_BASIC -> {
            // RFC 7617: the credentials are base64 of `user:password`, and the
            // colon is a separator — a colon in the *username* is not
            // representable, which is the scheme's own limitation, not ours.
            val encoded = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            "Authorization" to "Basic $encoded"
        }

        AUTH_BEARER -> {
            val prefix = scheme.trim()
            "Authorization" to if (prefix.isEmpty()) token else "$prefix $token"
        }

        AUTH_API_KEY ->
            if (keyIn == KEY_IN_HEADER && keyName.isNotBlank()) keyName to keyValue else null

        else -> null
    }

    /** The query parameter this auth sends, when it is an API key sent that way. */
    fun queryParam(): Pair<String, String>? =
        if (type == AUTH_API_KEY && keyIn == KEY_IN_QUERY && keyName.isNotBlank()) {
            keyName to keyValue
        } else {
            null
        }
}

const val AUTH_NONE = "none"
const val AUTH_BASIC = "basic"
const val AUTH_BEARER = "bearer"
const val AUTH_API_KEY = "apikey"
const val AUTH_OAUTH1 = "oauth1"
const val AUTH_OAUTH2 = "oauth2"

/**
 * OAuth 1.0 signature methods.
 *
 * The names are the wire values, because they appear verbatim in
 * `oauth_signature_method` — a label and a protocol value that differ by a
 * hyphen is a bug waiting to be typed.
 */
const val SIG_HMAC_SHA1 = "HMAC-SHA1"
const val SIG_HMAC_SHA256 = "HMAC-SHA256"
const val SIG_PLAINTEXT = "PLAINTEXT"
const val SIG_RSA_SHA1 = "RSA-SHA1"

val SIGNATURE_METHODS = listOf(SIG_HMAC_SHA1, SIG_HMAC_SHA256, SIG_PLAINTEXT, SIG_RSA_SHA1)

/** OAuth 2.0 grants. Values are the `grant_type` sent, where there is one. */
const val GRANT_AUTH_CODE = "authorization_code"
const val GRANT_CLIENT_CREDENTIALS = "client_credentials"
const val GRANT_PASSWORD = "password"
const val GRANT_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code"
const val GRANT_JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer"

/**
 * JWT signing algorithms.
 *
 * RS256 is what providers with a key pair use — Google service accounts, Azure,
 * anything with a certificate. HS256 signs with the client secret instead, for
 * the providers that want that. ES256 is deliberately absent: JOSE wants the
 * raw R‖S pair and the JDK emits DER, so it needs a conversion this app has no
 * other reason to carry.
 */
const val JWT_RS256 = "RS256"
const val JWT_HS256 = "HS256"

val JWT_ALGORITHMS = listOf(JWT_RS256, JWT_HS256)

val GRANT_TYPES = listOf(
    GRANT_AUTH_CODE,
    GRANT_CLIENT_CREDENTIALS,
    GRANT_PASSWORD,
    GRANT_DEVICE_CODE,
    GRANT_JWT_BEARER,
)

fun grantLabel(grant: String): String = when (grant) {
    GRANT_AUTH_CODE -> "Authorization Code"
    GRANT_CLIENT_CREDENTIALS -> "Client Credentials"
    GRANT_PASSWORD -> "Password"
    GRANT_DEVICE_CODE -> "Device Code"
    GRANT_JWT_BEARER -> "JWT Bearer"
    else -> grant
}

const val KEY_IN_HEADER = "header"
const val KEY_IN_QUERY = "query"

/** The schemes offered in the picker, in the order they are shown. */
val AUTH_TYPES = listOf(AUTH_NONE, AUTH_BASIC, AUTH_BEARER, AUTH_API_KEY, AUTH_OAUTH1, AUTH_OAUTH2)

/** A scheme's display name; an unknown one shows as itself rather than blank. */
fun authTypeLabel(type: String): String = when (type) {
    AUTH_NONE -> "No auth"
    AUTH_BASIC -> "Basic"
    AUTH_BEARER -> "Bearer token"
    AUTH_API_KEY -> "API key"
    AUTH_OAUTH1 -> "OAuth 1.0"
    AUTH_OAUTH2 -> "OAuth 2.0"
    else -> type
}

/** Methods offered in the picker; the field itself accepts anything. */
val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/**
 * Turns a display name into a file name.
 *
 * Rejects rather than mangles: a name that cannot be a Windows file name is
 * refused so the user is told why, instead of quietly getting a file called
 * `GET _users_q_1`. Reserved device names are checked case-insensitively and
 * without their extension, which is how Windows resolves them.
 */
fun fileNameFor(displayName: String): String? {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty() || trimmed.length > 64) return null
    if (trimmed.any { it in ILLEGAL_CHARS || it.code < 0x20 }) return null
    if (trimmed.endsWith('.')) return null
    if (trimmed.uppercase() in RESERVED_NAMES) return null
    return trimmed
}

private const val ILLEGAL_CHARS = "\\/:*?\"<>|"

private val RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { add("COM$it"); add("LPT$it") }
}
