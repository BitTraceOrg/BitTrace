package org.bittrace.api

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for collection archives.
 *
 * Three things here are worth holding down. A round trip has to be lossless or
 * "export, delete, import" quietly costs somebody their work. A zip is a file
 * from somewhere else, so an entry that tries to escape the target has to be
 * refused rather than trusted. And the renaming has to survive a whole folder
 * being renamed, not just its top entry.
 */
class CollectionArchiveTest {

    private val temp: Path = Files.createTempDirectory("bittrace-archive-test")

    @AfterTest
    fun cleanUp() {
        Files.walk(temp).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun dir(name: String): Path = temp.resolve(name).also { it.createDirectories() }

    private fun file(at: Path, name: String, text: String): Path =
        at.resolve(name).also { it.parent.createDirectories(); it.writeText(text) }

    /** Accepts everything, keeping its own name — the identity import. */
    private val asIs: (ArchiveItem) -> String? = { it.name }

    // --- round trip --------------------------------------------------------

    @Test
    fun `a project round-trips with its collections and requests`() {
        val source = dir("project")
        file(source, "Auth/login.yaml", "name: login")
        file(source, "Auth/logout.yaml", "name: logout")
        file(source, "Billing/charge.yaml", "name: charge")

        val zip = temp.resolve("project.zip")
        assertEquals(3, zipDirectory(source, zip).getOrThrow())

        val target = dir("restored")
        val report = unzipInto(zip, target, asIs).getOrThrow()

        assertEquals(3, report.files)
        assertEquals(setOf("Auth", "Billing"), report.written.toSet())
        assertTrue(report.skipped.isEmpty())
        assertEquals("name: login", target.resolve("Auth/login.yaml").readText())
        assertEquals("name: charge", target.resolve("Billing/charge.yaml").readText())
    }

    @Test
    fun `an empty collection survives the round trip`() {
        val source = dir("with-empty")
        source.resolve("Empty").createDirectories()
        file(source, "Full/one.yaml", "name: one")

        val zip = temp.resolve("empty.zip")
        zipDirectory(source, zip).getOrThrow()
        val target = dir("empty-restored")
        unzipInto(zip, target, asIs).getOrThrow()

        // Without a directory entry this folder would zip to nothing and come
        // back as nothing, which looks exactly like having lost it.
        assertTrue(target.resolve("Empty").isDirectory(), "an empty collection is still a collection")
    }

    @Test
    fun `the archive holds the contents, not the folder`() {
        val source = dir("outer")
        file(source, "Inner/one.yaml", "x")

        val zip = temp.resolve("outer.zip")
        zipDirectory(source, zip).getOrThrow()
        val target = dir("outer-restored")
        unzipInto(zip, target, asIs).getOrThrow()

        assertTrue(target.resolve("Inner").exists(), "the level's contents land directly in the target")
        assertFalse(target.resolve("outer").exists(), "the level's own folder is not in the archive")
    }

    // --- safety ------------------------------------------------------------

    @Test
    fun `an entry that escapes the target is refused`() {
        val zip = temp.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("../escape.yaml"))
            out.write("owned".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("fine/ok.yaml"))
            out.write("ok".toByteArray())
            out.closeEntry()
        }

        val target = dir("guarded")
        val report = unzipInto(zip, target, asIs).getOrThrow()

        assertFalse(temp.resolve("escape.yaml").exists(), "nothing may be written outside the target")
        // The good entry still lands: one hostile name does not void the import.
        assertEquals("ok", target.resolve("fine/ok.yaml").readText())
        assertEquals(1, report.files)
    }

    @Test
    fun `a windows-style separator is read as a path, not a name`() {
        val zip = temp.resolve("backslash.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("Auth\\login.yaml"))
            out.write("name: login".toByteArray())
            out.closeEntry()
        }

        val target = dir("backslash-target")
        val report = unzipInto(zip, target, asIs).getOrThrow()

        assertEquals(listOf("Auth"), report.written)
        assertEquals("name: login", target.resolve("Auth/login.yaml").readText())
    }

    // --- the caller's policy ----------------------------------------------

    @Test
    fun `a rejected item is reported rather than dropped in silence`() {
        val source = dir("mixed")
        file(source, "Folder/one.yaml", "x")
        file(source, "loose.yaml", "y")

        val zip = temp.resolve("mixed.zip")
        zipDirectory(source, zip).getOrThrow()
        val target = dir("folders-only")

        // The rule a project import uses: folders yes, loose files no.
        val report = unzipInto(zip, target) { item -> item.name.takeIf { item.isDirectory } }.getOrThrow()

        assertEquals(listOf("Folder"), report.written)
        assertContains(report.skipped, "loose.yaml")
        assertFalse(target.resolve("loose.yaml").exists())
    }

    @Test
    fun `renaming an item carries everything under it`() {
        val source = dir("rename-source")
        file(source, "Auth/login.yaml", "name: login")

        val zip = temp.resolve("rename.zip")
        zipDirectory(source, zip).getOrThrow()
        val target = dir("rename-target")

        unzipInto(zip, target) { "Auth 2" }.getOrThrow()

        assertEquals("name: login", target.resolve("Auth 2/login.yaml").readText())
        assertFalse(target.resolve("Auth").exists())
    }

    @Test
    fun `an item the caller renames does not touch what is already there`() {
        val source = dir("clash-source")
        file(source, "Auth/login.yaml", "incoming")

        val zip = temp.resolve("clash.zip")
        zipDirectory(source, zip).getOrThrow()

        val target = dir("clash-target")
        file(target, "Auth/login.yaml", "existing")

        unzipInto(zip, target) { "Auth 2" }.getOrThrow()

        assertEquals("existing", target.resolve("Auth/login.yaml").readText(), "nothing on disk is replaced")
        assertEquals("incoming", target.resolve("Auth 2/login.yaml").readText())
    }

    @Test
    fun `an archive with no usable items writes nothing`() {
        val source = dir("nothing-source")
        file(source, "loose.yaml", "y")

        val zip = temp.resolve("nothing.zip")
        zipDirectory(source, zip).getOrThrow()
        val target = dir("nothing-target")

        val report = unzipInto(zip, target) { item -> item.name.takeIf { item.isDirectory } }.getOrThrow()

        assertTrue(report.written.isEmpty())
        assertEquals(0, report.files)
        assertEquals(listOf("loose.yaml"), report.skipped)
    }
}
