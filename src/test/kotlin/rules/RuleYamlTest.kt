package org.bittrace.rules

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bittrace.proxy.Commands

/**
 * The rule files, from this side.
 *
 * `src/test/resources/rules` is a copy of MITMConnect's
 * `tests/fixtures/rules`, which its pytest suite reads with ruamel. Reading the
 * same files here with kaml is half of what keeps the two parsers agreeing; the
 * other half is that what kaml *writes* is read back there — set
 * `BITTRACE_RULES_OUT=<dir>` and this suite leaves its encoded copies in that
 * folder for `python -m pytest` (or `core.Rules.RuleSet`) to load.
 */
class RuleYamlTest {

    private val fixtures: Path = Paths.get(javaClass.getResource("/rules/rules.yaml")!!.toURI()).parent

    @Test
    fun `every shared fixture decodes`() {
        val manifest = RuleYaml.decodeManifest(fixtures.resolve("rules.yaml").readText())
        assertEquals(listOf("add-header", "mock-users", "to-staging", "pause-login"), manifest.rules.map { it.id })
        assertFalse(manifest.rules.first { it.id == "to-staging" }.enabled)
        assertEquals(300, manifest.breakpointTimeoutSec)

        val rewrite = RuleYaml.decodeRule(fixtures.resolve("add-header.yaml").readText())
        assertEquals(RuleKind.REWRITE, rewrite.ruleKind)
        assertEquals("*.example.com", rewrite.match.host)
        assertEquals(listOf("requestHeader", "responseBody"), rewrite.rewrite!!.map { it.target })
        assertEquals("\"live\"", rewrite.rewrite!![1].find)

        val local = RuleYaml.decodeRule(fixtures.resolve("mock-users.yaml").readText())
        assertEquals("mocks/mock-users", local.mapLocal!!.path)
        assertEquals(listOf(HeaderLine("Cache-Control", "no-store")), local.mapLocal!!.headers)

        val remote = RuleYaml.decodeRule(fixtures.resolve("to-staging.yaml").readText())
        assertEquals("staging.example.com", remote.mapRemote!!.host)
        assertEquals("/v2/*", remote.mapRemote!!.path)

        val bp = RuleYaml.decodeRule(fixtures.resolve("pause-login.yaml").readText())
        assertTrue(bp.match.pathRegex)
        assertEquals("^/auth/(login|token)$", bp.match.path)
        assertTrue(bp.breakpoint!!.response)
    }

    @Test
    fun `encoding round-trips and leaves out other kinds' blocks`() {
        val out = System.getenv("BITTRACE_RULES_OUT")?.let { Paths.get(it).createDirectories() }
        for (file in fixtures.listDirectoryEntries("*.yaml")) {
            if (file.name == RuleStore.MANIFEST) {
                val m = RuleYaml.decodeManifest(file.readText())
                val text = RuleYaml.encodeManifest(m)
                assertEquals(m, RuleYaml.decodeManifest(text))
                out?.resolve(file.name)?.writeText(text)
                continue
            }
            val rule = RuleYaml.decodeRule(file.readText()).normalized()
            val text = RuleYaml.encodeRule(rule)
            assertEquals(rule, RuleYaml.decodeRule(text))
            for (block in listOf("rewrite:", "mapLocal:", "mapRemote:", "breakpoint:")) {
                val expected = block.removeSuffix(":") == rule.kind
                assertEquals(expected, text.lines().any { it.startsWith(block) }, "$block in ${file.name}:\n$text")
            }
            assertFalse("null" in text, text)
            out?.resolve(file.name)?.writeText(text)
        }
        // A regex survives quoting both ways — the case most likely to break.
        val tricky = RuleFile(kind = "rewrite", match = RuleMatch(path = """^/v\d+/(a|b)"\x41""", pathRegex = true),
            rewrite = listOf(RewriteStep(target = "responseBody", op = "replace", find = """\s+"id": \d+""", regex = true)))
        assertEquals(tricky.normalized(), RuleYaml.decodeRule(RuleYaml.encodeRule(tricky)))
        out?.resolve("tricky.yaml")?.writeText(RuleYaml.encodeRule(tricky))
    }

    @Test
    fun `a file from a later build still loads`() {
        val rule = RuleYaml.decodeRule("kind: teleport\nname: x\nfuture: true\n")
        assertNull(rule.ruleKind)
        assertEquals(rule, rule.normalized())
    }
}

class RuleStoreTest {

    private fun temp(): Path = Files.createTempDirectory("bittrace-rules")

    private class Recorder {
        val sent = mutableListOf<Pair<Int, String>>()
        fun attach(store: RuleStore) { store.notify = { tag, json -> sent += tag to json } }
    }

    @Test
    fun `create writes the file before the manifest and announces both`() {
        val dir = temp()
        val store = RuleStore(dir)
        val rec = Recorder().also { it.attach(store) }
        val id = store.create(RuleFile(name = "x", kind = "breakpoint"))

        assertTrue(RuleStore.ID.matches(id))
        assertTrue(dir.resolve("$id.yaml").exists())
        assertEquals(listOf(id), RuleYaml.decodeManifest(dir.resolve("rules.yaml").readText()).rules.map { it.id })
        // The sidecar's RulesOrder only re-reads the manifest, so the new rule
        // has to be loaded by its own command first.
        assertEquals(listOf(Commands.RULE_CHANGED to """{"id":"$id"}""", Commands.RULES_ORDER to "{}"), rec.sent)
        // normalized: a breakpoint got its block, and nothing else.
        assertTrue(store.rules[id]!!.breakpoint != null)
        assertTrue(store.isActive(id))
    }

    @Test
    fun `toggles, order and delete go to disk and to the sidecar`() {
        val dir = temp()
        val store = RuleStore(dir)
        val a = store.create(RuleFile(name = "a", kind = "breakpoint"))
        val b = store.create(RuleFile(name = "b", kind = "breakpoint"))
        val rec = Recorder().also { it.attach(store) }

        store.setEnabled(a, false)
        assertFalse(store.isActive(a))
        store.move(b, 0)
        assertEquals(listOf(b, a), store.manifest.rules.map { it.id })
        store.setKindEnabled(RuleKind.BREAKPOINT, false)
        assertEquals(0, store.activeCount)
        store.save(b, RuleFile(name = "b2", kind = "rewrite", rewrite = listOf(RewriteStep())))
        store.delete(a)

        assertFalse(dir.resolve("$a.yaml").exists())
        assertEquals(
            listOf(
                Commands.RULES_ORDER to "{}", Commands.RULES_ORDER to "{}", Commands.RULES_ORDER to "{}",
                Commands.RULE_CHANGED to """{"id":"$b"}""",
                Commands.RULES_ORDER to "{}", Commands.RULE_REMOVED to """{"id":"$a"}""",
            ),
            rec.sent,
        )

        // What is on disk is what a fresh store (a restarted app) sees.
        val again = RuleStore(dir)
        assertEquals(listOf(b), again.manifest.rules.map { it.id })
        assertEquals("b2", again.rules[b]!!.name)
        assertFalse(again.manifest.kinds.breakpoint)
    }

    @Test
    fun `a mock keeps the body on disk and drops the wire framing headers`() {
        val dir = temp()
        val store = RuleStore(dir)
        val id = store.saveMock(
            "mock", RuleMatch(path = "/x"), 201,
            listOf(HeaderLine("Content-Type", "application/json"), HeaderLine("Content-Encoding", "gzip"),
                HeaderLine("Content-Length", "12")),
            """{"ok":true}""".toByteArray(),
        )
        val spec = store.rules[id]!!.mapLocal!!
        assertEquals("mocks/$id/body", spec.path)
        assertEquals(201, spec.status)
        assertEquals(listOf("Content-Type"), spec.headers.map { it.name })
        assertEquals("""{"ok":true}""", dir.resolve(spec.path).readText())
        store.delete(id)
        assertFalse(dir.resolve("mocks").resolve(id).exists())
    }

    @Test
    fun `an unreadable file is reported and not lost`() {
        val dir = temp()
        RuleStore(dir).create(RuleFile(name = "ok", kind = "breakpoint"))
        val id = RuleYaml.decodeManifest(dir.resolve("rules.yaml").readText()).rules.single().id
        dir.resolve("$id.yaml").writeText("kind: [\n")
        val store = RuleStore(dir)
        assertTrue(store.localErrors.containsKey(id))
        assertEquals(listOf(id), store.manifest.rules.map { it.id })
        assertTrue(dir.resolve("$id.yaml").exists())
    }
}

class RuleFormTest {

    @Test
    fun `fields round-trip through a rule`() {
        val rule = RuleFile(
            name = "r", kind = "mapRemote",
            match = RuleMatch(methods = listOf("GET", "POST"), host = "*.a.com", port = 8443, path = "/api/*"),
            mapRemote = MapRemoteSpec(scheme = "http", host = "localhost", port = 3000, path = "/v2/*"),
        )
        assertEquals(rule.normalized(), RuleForm.of(rule).toRule().getOrThrow())
    }

    @Test
    fun `typed text is parsed once, on save`() {
        val form = RuleForm(kind = RuleKind.BREAKPOINT, methods = "get,  post ", schemes = "HTTPS", port = " ")
        val rule = form.toRule().getOrThrow()
        assertEquals(listOf("GET", "POST"), rule.match.methods)
        assertEquals(listOf("https"), rule.match.schemes)
        assertEquals(0, rule.match.port)
    }

    @Test
    fun `bad input is a message, not a different rule`() {
        fun err(form: RuleForm) = form.toRule().exceptionOrNull()?.message.orEmpty()
        assertTrue("Port" in err(RuleForm(kind = RuleKind.BREAKPOINT, port = "80a")))
        assertTrue("regex" in err(RuleForm(kind = RuleKind.BREAKPOINT, path = "(", pathRegex = true)))
        assertTrue("step" in err(RuleForm(kind = RuleKind.REWRITE)))
        assertTrue("name" in err(RuleForm(kind = RuleKind.REWRITE, steps = listOf(RewriteStep(target = "requestHeader")))))
        assertTrue("status" in err(RuleForm(kind = RuleKind.REWRITE, steps = listOf(RewriteStep(target = "status", value = "x")))))
        assertTrue("changes nothing" in err(RuleForm(kind = RuleKind.MAP_REMOTE)))
        assertTrue("file" in err(RuleForm(kind = RuleKind.MAP_LOCAL)))
        assertTrue("Pause" in err(RuleForm(kind = RuleKind.BREAKPOINT, pauseRequest = false)))
    }

    @Test
    fun `header text keeps order and duplicates`() {
        val parsed = HeaderText.parse("Set-Cookie: a=1\r\nnot a header\n: nameless\nSet-Cookie:b=2\nX-Empty:")
        assertEquals(listOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2", "X-Empty" to ""), parsed.map { it.name to it.value })
        assertEquals("Set-Cookie: a=1\nSet-Cookie: b=2\nX-Empty: ", HeaderText.format(parsed))
    }
}
