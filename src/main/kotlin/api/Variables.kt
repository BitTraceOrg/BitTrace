package org.bittrace.api

import org.bittrace.data.readOrDefault
import org.bittrace.data.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * Values a project reuses, and the `{{name}}` syntax that reads them.
 *
 * The same base URL, tenant id or key turns up in twenty requests in a project,
 * and until now changing it meant opening twenty files. A variable is one place
 * to put it: write `{{host}}` in a URL, a header, a body or an auth field, and
 * it is replaced on the way out.
 *
 * Substitution happens **at send time and is never written back**. The saved
 * request keeps the `{{host}}` form, which is what makes it worth committing —
 * a request file describes an endpoint, not one machine's idea of it. This is
 * the rule `ApiSender` already documents for the API-key query parameter.
 */
@Serializable
class VariablesFile(val variables: List<KeyValue> = emptyList())

object ProjectVariables {

    /**
     * The file, inside each project.
     *
     * **The leading dot is load-bearing.** `CollectionStore.holdsRequestDirectly`
     * reads a plain `.yaml` sitting directly in a project folder as the tell for
     * the pre-project layout, and `adoptLegacyLayout` would then sweep every
     * project into a folder called "My project". The dot filter that stops this
     * already exists — it was added when the secrets sidecar walked into exactly
     * this trap — and this file only stays out of its way by being named here.
     */
    const val FILE_NAME = ".bittrace-variables.yaml"

    fun pathIn(project: Path): Path = project.resolve(FILE_NAME)

    /**
     * A project's rows, or none.
     *
     * Never throws. A corrupt variables file costs you the substitutions, which
     * you can retype; failing the read would cost you the project.
     */
    fun read(project: Path): List<KeyValue> {
        return readOrDefault(pathIn(project), emptyList()) {
            appYaml.decodeFromString(VariablesFile.serializer(), it).variables
        }
    }

    /** Temp file plus move, so a failure part-way cannot truncate what is there. */
    fun write(project: Path, rows: List<KeyValue>) {
        val file = pathIn(project)
        val kept = rows.filterNot { it.name.isBlank() && it.value.isBlank() }
        if (kept.isEmpty()) {
            Files.deleteIfExists(file)
            return
        }
        writeAtomically(file, appYaml.encodeToString(VariablesFile.serializer(), VariablesFile(kept)))
    }

    /**
     * The rows as a lookup.
     *
     * Unticked rows are left out, matching what the same checkbox means on the
     * headers and params tables — it is how you park a value without deleting
     * it. A later row of the same name wins, so a duplicate reads as an
     * override rather than an error.
     */
    fun lookup(rows: List<KeyValue>): Map<String, String> = rows
        .filter { it.enabled && it.name.isNotBlank() }
        .associate { it.name.trim() to it.value }

    fun lookupIn(project: Path): Map<String, String> = lookup(read(project))
}

/**
 * `{{name}}` replaced by its value; an unknown name by nothing.
 *
 * Whitespace inside the braces is ignored, so `{{ host }}` and `{{host}}` are
 * the same variable — the alternative is a typo that looks identical to a
 * working one.
 *
 * Values are substituted once and not rescanned. A variable whose value happens
 * to contain `{{...}}` yields those characters, which makes the result of a
 * substitution independent of what any other variable holds, and makes a cycle
 * impossible rather than merely handled.
 */
fun resolve(text: String, vars: Map<String, String>): String {
    if (text.isEmpty() || !text.contains("{{")) return text
    return PLACEHOLDER.replace(text) { match -> vars[match.groupValues[1].trim()].orEmpty() }
}

/** Both halves of every row: a variable is as useful in a header's name as in its value. */
fun List<KeyValue>.resolved(vars: Map<String, String>): List<KeyValue> =
    map { it.copy(name = resolve(it.name, vars), value = resolve(it.value, vars)) }

/**
 * The request as it should go on the wire.
 *
 * `name` is left alone: it is the file's display name, not something that is
 * sent. `params` is substituted for the sake of anything that reads the table,
 * but note that the **URL is what is actually sent** — `ApiSender.buildUrl`
 * returns `request.url` and nothing appends the params table to it.
 */
fun ApiRequest.resolved(vars: Map<String, String>): ApiRequest {
    // No short-circuit on an empty map. It reads like a free optimisation and is
    // actually a second rule: an unknown name resolves to nothing, and "the
    // project has no variables at all" is the case where every name is unknown.
    // Returning the request untouched there would mean a draft sends `{{host}}`
    // literally while a project request sends nothing, which is one behaviour
    // too many for a feature whose whole job is to be predictable.
    return copy(
        url = resolve(url, vars),
        params = params.resolved(vars),
        headers = headers.resolved(vars),
        cookies = cookies.resolved(vars),
        auth = auth.resolved(vars),
        body = body.resolved(vars),
    )
}

/**
 * The body, substituted before it is assembled.
 *
 * Order matters for GraphQL: `payload()` wraps the operation and variables in a
 * JSON envelope with a real generator, so substituting the parts first gets the
 * values escaped properly. Substituting the envelope afterwards would paste
 * unescaped text into JSON, and a value with a quote in it would produce a body
 * the server cannot parse.
 *
 * `contentType` is not substituted — it is chosen from a fixed list of formats,
 * not typed.
 */
fun ApiBody.resolved(vars: Map<String, String>): ApiBody = copy(
    text = resolve(text, vars),
    graphqlVariables = resolve(graphqlVariables, vars),
    filePath = resolve(filePath, vars),
)

/**
 * Credentials and endpoints, substituted.
 *
 * The discriminators are deliberately left out — `type`, `keyIn`, `grantType`,
 * `signatureMethod` and `jwtAlgorithm` choose a code path rather than carry a
 * value, and a `{{...}}` resolving to nothing in one of them would not produce a
 * different request so much as an incoherent one.
 */
fun ApiAuth.resolved(vars: Map<String, String>): ApiAuth = copy(
    username = resolve(username, vars),
    password = resolve(password, vars),
    token = resolve(token, vars),
    keyName = resolve(keyName, vars),
    keyValue = resolve(keyValue, vars),
    consumerKey = resolve(consumerKey, vars),
    consumerSecret = resolve(consumerSecret, vars),
    oauthToken = resolve(oauthToken, vars),
    oauthTokenSecret = resolve(oauthTokenSecret, vars),
    realm = resolve(realm, vars),
    privateKeyPath = resolve(privateKeyPath, vars),
    clientId = resolve(clientId, vars),
    clientSecret = resolve(clientSecret, vars),
    authUrl = resolve(authUrl, vars),
    tokenUrl = resolve(tokenUrl, vars),
    deviceAuthUrl = resolve(deviceAuthUrl, vars),
    redirectPath = resolve(redirectPath, vars),
    scope = resolve(scope, vars),
    audience = resolve(audience, vars),
    jwtIssuer = resolve(jwtIssuer, vars),
    jwtSubject = resolve(jwtSubject, vars),
    jwtAudience = resolve(jwtAudience, vars),
    jwtKeyId = resolve(jwtKeyId, vars),
)

/**
 * A lookup that remembers the names it had no value for.
 *
 * An unknown name resolves to nothing, deliberately — but "nothing" and "the
 * empty string you meant" look identical on the wire, so a typo'd or
 * out-of-scope `{{name}}` used to be invisible: the request just went out with a
 * hole in it. Wrapping the map rather than threading a collector through
 * [resolve] and each `resolved` keeps every one of those signatures pure, and
 * keeps the single list of substituted fields single.
 *
 * Order is preserved so the log names them the way the request does.
 */
class TrackedVariables(private val vars: Map<String, String>) : Map<String, String> by vars {

    private val absent = LinkedHashSet<String>()

    /** Every `{{name}}` asked for that this project had no row for. */
    val missing: Set<String> get() = absent

    override fun get(key: String): String? {
        val value = vars[key]
        if (value == null) absent += key
        return value
    }
}

/**
 * `{{ name }}`, with the name captured.
 *
 * `[^{}]+?` rather than `.+?` so a stray brace ends the match instead of letting
 * it run on: `{{a}} and {b` should leave the tail alone.
 */
private val PLACEHOLDER = Regex("""\{\{\s*([^{}]+?)\s*}}""")
