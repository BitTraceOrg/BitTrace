package org.bittrace.api

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory

/**
 * One thing at a zip's top level.
 *
 * The top level is the only level import makes decisions about, because it is
 * the only one the collections layout cares about: a project takes folders and a
 * collection takes files, and what is *inside* an incoming folder is that
 * folder's business.
 *
 * @property files how many actual files it holds, so a caller can report "12
 *   requests" rather than "1 collection" and leave you guessing.
 */
class ArchiveItem(val name: String, val isDirectory: Boolean, val files: Int)

/** What an import did, and what it declined to do. */
class ImportReport(val written: List<String>, val skipped: List<String>, val files: Int)

/**
 * Zips everything under [source] into [target].
 *
 * The archive holds the directory's *contents*, not the directory itself: a
 * project zip opens onto its collections. That is what lets import mean
 * "unpack into the level you clicked" rather than "unpack and then work out
 * which folder to throw away".
 *
 * Empty directories get an entry of their own. Without one a collection with no
 * requests would not survive the round trip — it would zip to nothing and come
 * back as nothing, which looks identical to having lost it.
 */
fun zipDirectory(source: Path, target: Path): Result<Int> = runCatching {
    var files = 0
    Files.newOutputStream(target).use { raw ->
        ZipOutputStream(raw).use { zip ->
            Files.walk(source).use { walk ->
                // `.git` is skipped: once projects are repositories, an
                // unfiltered walk would put the entire history into every
                // exported zip — megabytes of pack files, and every credential
                // that was ever committed, in a file people mail to each other.
                walk.filter { it != source && !it.any { part -> part.toString() == ".git" } }.forEach { path ->
                    val name = source.relativize(path).joinToString("/")
                    if (path.isDirectory()) {
                        zip.putNextEntry(ZipEntry("$name/"))
                        zip.closeEntry()
                    } else {
                        zip.putNextEntry(ZipEntry(name))
                        Files.copy(path, zip)
                        zip.closeEntry()
                        files++
                    }
                }
            }
        }
    }
    files
}

/**
 * Unpacks [archive] into [target], asking [resolve] what each top-level item
 * should be called — or returning null to leave it out.
 *
 * The rename is the caller's because the naming rules are: this file knows how
 * to read a zip, and nothing about projects, collections or what is already on
 * disk. Everything under a renamed item follows it, so a folder that lands as
 * `Auth 2` keeps its requests.
 *
 * Entries that would escape [target] are skipped and reported. A zip is a file
 * from somewhere else, and `../../../evil.yaml` is a real entry name that a real
 * archiver will happily produce.
 */
fun unzipInto(archive: Path, target: Path, resolve: (ArchiveItem) -> String?): Result<ImportReport> = runCatching {
    ZipFile(archive.toFile()).use { zip ->
        val all = zip.entries().asSequence().toList()
        // Split before anything else looks at the names. An entry that walks up
        // out of the target is refused as itself rather than tidied into a
        // plausible one: sanitising `../escape.yaml` into `escape.yaml` would be
        // safe, and would also hide that the archive tried.
        val (hostile, entries) = all.partition { traverses(it.name) }
        val items = indexOf(entries)

        val written = mutableListOf<String>()
        val skipped = hostile.map { it.name }.toMutableList()
        val names = mutableMapOf<String, String>()

        items.forEach { item ->
            val name = resolve(item)
            if (name == null) skipped += item.name else { names[item.name] = name; written += name }
        }

        var files = 0
        entries.forEach { entry ->
            val parts = segmentsOf(entry.name)
            val top = parts.firstOrNull() ?: return@forEach
            val renamed = names[top] ?: return@forEach

            val relative = (listOf(renamed) + parts.drop(1)).joinToString("/")
            val destination = target.resolve(relative).normalize()
            // The backstop. Traversal is already gone by here, so this catches
            // whatever a path implementation makes of a name nobody predicted,
            // and it is why this lives in a function rather than as a
            // ZipInputStream loop at the call site.
            if (!destination.startsWith(target.normalize())) {
                skipped += entry.name
                return@forEach
            }

            if (entry.isDirectory) {
                Files.createDirectories(destination)
            } else {
                destination.parent?.let { Files.createDirectories(it) }
                zip.getInputStream(entry).use { stream: InputStream ->
                    Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                files++
            }
        }
        ImportReport(written, skipped, files)
    }
}

/**
 * What sits at the zip's top level, folded from its flat entry list.
 *
 * A zip has no tree, only names — and not every archiver writes the directory
 * entries, so `a/b.yaml` may be the only evidence that `a` is a folder. Both
 * forms are folded here so the caller sees the same shape either way.
 */
private fun indexOf(entries: List<ZipEntry>): List<ArchiveItem> {
    val directories = mutableSetOf<String>()
    val fileCounts = mutableMapOf<String, Int>()
    val order = mutableListOf<String>()

    entries.forEach { entry ->
        val parts = segmentsOf(entry.name)
        val top = parts.firstOrNull() ?: return@forEach
        if (top !in order) order += top
        // Anything deeper than one segment proves the top is a folder, whether
        // or not the archive bothered to say so.
        if (parts.size > 1 || entry.isDirectory) directories += top
        if (!entry.isDirectory) fileCounts[top] = (fileCounts[top] ?: 0) + 1
    }

    return order.map { ArchiveItem(it, it in directories, fileCounts[it] ?: 0) }
}

/**
 * A zip entry name as path segments.
 *
 * Backslashes are treated as separators too: an archive written on Windows by a
 * tool that ignores the spec is common enough that reading one as a single
 * bizarre file name would be the wrong answer.
 */
private fun segmentsOf(name: String): List<String> =
    name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

/** Whether any segment of [name] walks upwards, under either separator. */
private fun traverses(name: String): Boolean = segmentsOf(name).any { it == ".." }
