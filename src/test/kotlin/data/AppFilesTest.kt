package org.bittrace.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The write that five stores had each spelled out, and one had not.
 *
 * The cases worth pinning are the failure ones: four of the five hand-written
 * copies left an orphan `.tmp` behind, and the settings store was not atomic at
 * all, so a crash mid-write truncated the file it was supposed to be updating.
 */
class AppFilesTest {

    private val dir: Path = createTempDirectory("bittrace-appfiles")

    @AfterTest
    fun cleanUp() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `writes the file`() {
        val target = dir.resolve("settings.json")
        writeAtomically(target, "{}")
        assertEquals("{}", target.readText())
    }

    @Test
    fun `creates missing parents`() {
        val target = dir.resolve("nested/deeper/file.json")
        writeAtomically(target, "ok")
        assertEquals("ok", target.readText())
    }

    @Test
    fun `a failed write leaves the previous content intact`() {
        val target = dir.resolve("settings.json")
        writeAtomically(target, "original")

        assertFailsWith<IllegalStateException> {
            writeAtomically(target) { error("disk full") }
        }

        assertEquals("original", target.readText())
    }

    @Test
    fun `a failed write leaves no temp file behind`() {
        val target = dir.resolve("settings.json")

        assertFailsWith<IllegalStateException> {
            writeAtomically(target) { error("disk full") }
        }

        assertFalse(Files.exists(target.resolveSibling("settings.json.tmp")), "orphan .tmp left behind")
        assertFalse(Files.exists(target), "a failed write must not create the target")
    }

    @Test
    fun `readOrDefault falls back for an absent file`() {
        assertEquals("fallback", readOrDefault(dir.resolve("nope.json"), "fallback") { it })
    }

    @Test
    fun `readOrDefault falls back for a directory`() {
        assertEquals("fallback", readOrDefault(dir, "fallback") { it })
    }

    @Test
    fun `readOrDefault falls back when decoding throws`() {
        val target = dir.resolve("broken.json")
        writeAtomically(target, "not json")
        assertEquals("fallback", readOrDefault(target, "fallback") { error("bad json") })
    }

    @Test
    fun `configFile sits under the config directory`() {
        assertTrue(configFile("settings.json").startsWith(configDir()))
    }
}
