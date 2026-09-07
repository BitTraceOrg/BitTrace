package org.bittrace.api

import org.bittrace.ui.components.editor.graphql
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for `{{name}}` substitution.
 *
 * The interesting cases are all about what *not* to touch: text with no braces,
 * a name that has no value, a value that itself looks like a placeholder, and
 * the auth fields that pick a code path rather than carry one. Substitution that
 * is too eager produces a request nobody wrote.
 */
class VariablesTest {

    private val temp: Path = Files.createTempDirectory("bittrace-vars")

    @AfterTest
    fun cleanUp() {
        runCatching {
            Files.walk(temp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private val vars = mapOf("host" to "api.example.com", "key" to "abc123", "empty" to "")

    // --- resolve ------------------------------------------------------------

    @Test
    fun `a placeholder is replaced by its value`() {
        assertEquals("https://api.example.com/v1", resolve("https://{{host}}/v1", vars))
    }

    @Test
    fun `several placeholders in one string are all replaced`() {
        assertEquals("api.example.com/abc123", resolve("{{host}}/{{key}}", vars))
    }

    @Test
    fun `whitespace inside the braces is ignored`() {
        // Otherwise `{{ host }}` is a typo that looks identical to a working one.
        assertEquals("api.example.com", resolve("{{ host }}", vars))
    }

    @Test
    fun `an unknown name resolves to nothing`() {
        assertEquals("https:///v1", resolve("https://{{nope}}/v1", vars))
    }

    @Test
    fun `text with no braces is returned untouched`() {
        val plain = "https://example.com/orders?id=7"
        assertEquals(plain, resolve(plain, vars))
    }

    @Test
    fun `an unclosed brace is left alone`() {
        assertEquals("{{host", resolve("{{host", vars))
        assertEquals("api.example.com and {b", resolve("{{host}} and {b", vars))
    }

    @Test
    fun `a value that looks like a placeholder is not rescanned`() {
        // One pass, so the result of a substitution cannot depend on what
        // another variable holds — and a cycle is impossible rather than caught.
        val recursive = mapOf("a" to "{{b}}", "b" to "boom")

        assertEquals("{{b}}", resolve("{{a}}", recursive))
    }

    @Test
    fun `with no variables at all, every name is simply unknown`() {
        // Not a special case. "Unknown resolves to nothing" has to hold when the
        // project defines nothing, or a draft would behave differently from a
        // saved request for no reason a user could see.
        assertEquals("", resolve("{{host}}", emptyMap()))
        assertEquals("https:///x", ApiRequest(url = "https://{{host}}/x").resolved(emptyMap()).url)
    }

    @Test
    fun `an OAuth client id comes from the project's own file`() {
        // The reported case, end to end: a row saved in a project's variables
        // file, and a request naming it from an auth field.
        val project = temp.resolve("Github").also { it.createDirectories() }
        ProjectVariables.write(project, listOf(KeyValue("clientid", "Iv1.0123456789abcdef")))

        val sent = ApiRequest(
            url = "https://api.github.com/users/repo",
            auth = ApiAuth(type = AUTH_OAUTH2, clientId = "{{clientid}}"),
        ).resolved(ProjectVariables.lookupIn(project))

        assertEquals("Iv1.0123456789abcdef", sent.auth.clientId)
    }

    // --- what went unresolved -----------------------------------------------

    @Test
    fun `a tracked lookup names what it could not supply`() {
        val tracked = TrackedVariables(vars)
        val request = ApiRequest(
            url = "https://{{host}}/{{tenant}}",
            auth = ApiAuth(clientId = "{{clientid}}", clientSecret = "{{key}}"),
        ).resolved(tracked)

        assertEquals("https://api.example.com/", request.url)
        assertEquals("", request.auth.clientId)
        assertEquals("abc123", request.auth.clientSecret)
        // In the order the request asked for them, and only the ones it asked for.
        assertEquals(listOf("tenant", "clientid"), tracked.missing.toList())
    }

    @Test
    fun `a name that resolves to a blank value is not missing`() {
        // `empty` is a row somebody deliberately left blank. That is an answer,
        // and it must not be reported as a hole.
        val tracked = TrackedVariables(vars)
        assertEquals("", resolve("{{empty}}", tracked))
        assertTrue(tracked.missing.isEmpty())
    }

    @Test
    fun `a request that asks for nothing reports nothing`() {
        val tracked = TrackedVariables(vars)
        ApiRequest(url = "https://api.example.com").resolved(tracked)
        assertTrue(tracked.missing.isEmpty())
    }

    @Test
    fun `the same missing name is reported once`() {
        val tracked = TrackedVariables(vars)
        ApiRequest(
            url = "https://{{gone}}/{{gone}}",
            headers = listOf(KeyValue("X-Thing", "{{gone}}")),
        ).resolved(tracked)
        assertEquals(listOf("gone"), tracked.missing.toList())
    }

    @Test
    fun `an empty project reports every name the request used`() {
        // The case a draft used to hit silently: no project, so no values, so
        // every placeholder is a hole.
        val tracked = TrackedVariables(emptyMap())
        ApiRequest(url = "https://{{host}}", auth = ApiAuth(clientId = "{{clientid}}")).resolved(tracked)
        assertEquals(listOf("host", "clientid"), tracked.missing.toList())
    }

    // --- the lookup ---------------------------------------------------------

    @Test
    fun `unticked and unnamed rows are left out`() {
        val rows = listOf(
            KeyValue("host", "example.com"),
            KeyValue("parked", "value", enabled = false),
            KeyValue("", "orphan"),
        )

        assertEquals(mapOf("host" to "example.com"), ProjectVariables.lookup(rows))
    }

    @Test
    fun `a repeated name reads as an override`() {
        val rows = listOf(KeyValue("host", "old"), KeyValue("host", "new"))

        assertEquals("new", ProjectVariables.lookup(rows)["host"])
    }

    // --- the request --------------------------------------------------------

    @Test
    fun `every part of a request that goes on the wire is substituted`() {
        val request = ApiRequest(
            name = "Get {{host}}",
            url = "https://{{host}}/orders",
            params = listOf(KeyValue("q", "{{key}}")),
            headers = listOf(KeyValue("X-{{host}}", "{{key}}")),
            cookies = listOf(KeyValue("sid", "{{key}}")),
            body = ApiBody(contentType = "application/json", text = """{"k":"{{key}}"}"""),
            auth = ApiAuth(type = AUTH_BEARER, token = "{{key}}"),
        )

        val sent = request.resolved(vars)

        assertEquals("https://api.example.com/orders", sent.url)
        assertEquals("abc123", sent.params.single().value)
        // Both halves of a row: a variable is as useful in a header's name.
        assertEquals("X-api.example.com", sent.headers.single().name)
        assertEquals("abc123", sent.headers.single().value)
        assertEquals("abc123", sent.cookies.single().value)
        assertEquals("""{"k":"abc123"}""", sent.body.text)
        assertEquals("abc123", sent.auth.token)
        // The display name is not sent, so it is not touched.
        assertEquals("Get {{host}}", sent.name)
    }

    @Test
    fun `the discriminator fields are left alone`() {
        // These pick a code path rather than carry a value; a placeholder that
        // resolved to nothing in one would not make a different request, it
        // would make an incoherent one.
        val auth = ApiAuth(
            type = AUTH_OAUTH2,
            keyIn = KEY_IN_HEADER,
            grantType = GRANT_AUTH_CODE,
            signatureMethod = SIG_HMAC_SHA1,
            jwtAlgorithm = JWT_RS256,
        ).resolved(vars)

        assertEquals(AUTH_OAUTH2, auth.type)
        assertEquals(KEY_IN_HEADER, auth.keyIn)
        assertEquals(GRANT_AUTH_CODE, auth.grantType)
        assertEquals(SIG_HMAC_SHA1, auth.signatureMethod)
        assertEquals(JWT_RS256, auth.jwtAlgorithm)
    }

    @Test
    fun `the OAuth fields that identify a token are substituted`() {
        // These four plus the grant make up OAuthTokens' fingerprint. If the
        // authorise path keyed on `{{cid}}` and the send path on `abc123`, the
        // lookup would miss and every send would re-authorise.
        val auth = ApiAuth(
            type = AUTH_OAUTH2,
            clientId = "{{key}}",
            tokenUrl = "https://{{host}}/token",
            scope = "{{key}}",
            audience = "{{host}}",
        ).resolved(vars)

        assertEquals("abc123", auth.clientId)
        assertEquals("https://api.example.com/token", auth.tokenUrl)
        assertEquals("abc123", auth.scope)
        assertEquals("api.example.com", auth.audience)
    }

    @Test
    fun `GraphQL parts are substituted before the envelope is built`() {
        // `payload()` builds JSON with a generator, so substituting the parts
        // first gets the values escaped. Doing it the other way round would put
        // an unescaped quote straight into the envelope.
        val body = ApiBody(
            contentType = "application/graphql",
            text = "query { user(host: \"{{host}}\") }",
            graphqlVariables = """{"k":"{{key}}"}""",
        ).resolved(mapOf("host" to """say "hi"""", "key" to "abc123"))

        val payload = body.payload()
        assertTrue(payload.contains("""\"hi\""""), "the quote should be escaped: $payload")
        assertFalse(payload.contains("{{"), "nothing should be left unsubstituted")
    }

    @Test
    fun `a request with no placeholders is unchanged whatever is defined`() {
        val request = ApiRequest(url = "https://example.com/x", headers = listOf(KeyValue("A", "b")))

        assertEquals(request, request.resolved(vars))
    }

    @Test
    fun `a token stays findable when its client id is a variable`() {
        // OAuthTokens keys on grant, client id, token URL, scope and audience.
        // Authorising stores under one set of values and sending looks up under
        // another; if only one side is substituted the lookup misses, and the
        // symptom is not an error but a client that re-authorises on every send.
        val declared = ApiAuth(
            type = AUTH_OAUTH2,
            grantType = GRANT_CLIENT_CREDENTIALS,
            clientId = "{{key}}",
            tokenUrl = "https://{{host}}/token",
            scope = "{{key}}",
        )
        val store = org.bittrace.api.oauth.OAuthTokens()
        val token = org.bittrace.api.oauth.OAuthToken(accessToken = "at")

        // The authorise path stores against the substituted auth...
        store.put(declared.resolved(vars), token)

        // ...and the send path must find it against the same.
        assertEquals("at", store.of(declared.resolved(vars))?.accessToken)
    }

    // --- the file -----------------------------------------------------------

    @Test
    fun `variables round-trip through the file`() {
        val project = temp.resolve("Payments").also { it.createDirectories() }
        val rows = listOf(KeyValue("host", "example.com"), KeyValue("key", "abc", enabled = false))

        ProjectVariables.write(project, rows)

        assertEquals(rows, ProjectVariables.read(project))
    }

    @Test
    fun `the file goes away once nothing is left in it`() {
        val project = temp.resolve("Payments").also { it.createDirectories() }
        ProjectVariables.write(project, listOf(KeyValue("host", "example.com")))

        ProjectVariables.write(project, emptyList())

        assertFalse(Files.exists(ProjectVariables.pathIn(project)))
    }

    @Test
    fun `wholly blank rows are not stored`() {
        val project = temp.resolve("Payments").also { it.createDirectories() }

        ProjectVariables.write(project, listOf(KeyValue("host", "example.com"), KeyValue()))

        assertEquals(1, ProjectVariables.read(project).size)
    }

    @Test
    fun `a missing or corrupt file reads as no variables`() {
        val project = temp.resolve("Empty").also { it.createDirectories() }
        assertTrue(ProjectVariables.read(project).isEmpty())

        Files.writeString(ProjectVariables.pathIn(project), "{{{ not yaml")
        assertTrue(ProjectVariables.read(project).isEmpty())
    }

    @Test
    fun `the file name starts with a dot`() {
        // Load-bearing: a plain `.yaml` directly inside a project folder is the
        // tell `adoptLegacyLayout` uses for the pre-project layout, and it would
        // sweep every project into a folder called "My project".
        assertTrue(ProjectVariables.FILE_NAME.startsWith("."))
    }

    // --- the guards ---------------------------------------------------------

    private fun storeWithProject(): Pair<CollectionStore, Path> {
        val project = temp.resolve("Payments")
        project.resolve("Auth").createDirectories()
        Files.writeString(
            project.resolve("Auth").resolve("Login.yaml"),
            RequestYaml.encode(ApiRequest(name = "Login")),
        )
        val store = CollectionStore(temp)
        store.reload()
        return store to project
    }

    @Test
    fun `every project offers a variables node under it`() {
        val (store, project) = storeWithProject()

        val node = store.tree.single().variables

        assertEquals(ProjectVariables.pathIn(project), node.path)
        assertEquals("Variables", node.name)
        // Not one of the children: everything that walks the tree for requests
        // would otherwise have to filter it back out.
        assertTrue(store.tree.single().children.all { it is CollectionNode })
    }

    @Test
    fun `a request cannot be created inside the variables file`() {
        // `depthOf` counts path components, so the variables file sits at a
        // collection's depth. Without the directory check this writes a request
        // *inside a file*.
        val (store, project) = storeWithProject()

        val failure = store.createNamedRequest(ProjectVariables.pathIn(project)).exceptionOrNull()

        assertNotNull(failure)
    }

    @Test
    fun `a request cannot be saved over the variables file`() {
        val (store, project) = storeWithProject()

        val failure = store.save(ProjectVariables.pathIn(project), ApiRequest()).exceptionOrNull()

        assertNotNull(failure, "save must refuse a path that is not a request")
        assertFalse(Files.exists(ProjectVariables.pathIn(project)))
    }

    @Test
    fun `a request cannot be saved over a project folder`() {
        // The failure this guard exists for: an atomic move of a small YAML file
        // over a directory full of somebody's work.
        val (store, project) = storeWithProject()

        assertNotNull(store.save(project, ApiRequest()).exceptionOrNull())
        assertTrue(Files.isDirectory(project))
    }

    @Test
    fun `the variables node cannot be renamed or deleted`() {
        val (store, _) = storeWithProject()
        val node = store.tree.single().variables

        assertNotNull(store.rename(node, "Something").exceptionOrNull())
        assertNotNull(store.delete(node).exceptionOrNull())
    }

    @Test
    fun `the variables node cannot be exported or imported into`() {
        val (store, _) = storeWithProject()
        val node = store.tree.single().variables

        assertNotNull(store.exportNode(node, temp.resolve("out.zip")).exceptionOrNull())
        assertNotNull(store.importInto(node, temp.resolve("in.zip")).exceptionOrNull())
    }
}
