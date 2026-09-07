package org.bittrace.data

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Where the app keeps its files, and how it writes them.
 *
 * Both halves existed in several copies. The directory was spelled out twice
 * and the write dance five times, and each copy had drifted: one store wrote
 * non-atomically, four left an orphan `.tmp` behind on failure, and two spelled
 * the Linux fallback themselves rather than deriving it.
 */

/** `%APPDATA%\BitTrace` on Windows, `~/.config/BitTrace` elsewhere. */
fun configDir(): Path {
    val base = System.getenv("APPDATA")?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".config")
    return base.resolve("BitTrace")
}

fun configFile(name: String): Path = configDir().resolve(name)

/**
 * Writes [text] to [path] so that a failure cannot leave a half-written file.
 *
 * Content goes to a sibling `.tmp` and is then moved into place atomically, so
 * a reader sees either the old file or the new one and never a truncated one.
 * That matters most for the files nobody notices until they are broken —
 * settings, history, a project's variables.
 *
 * The temp file is removed if the move does not happen. Four of the five hand-
 * written copies of this skipped that and left `<name>.tmp` behind, which went
 * unnoticed only because the generated `.gitignore` hides it.
 */
fun writeAtomically(path: Path, text: String) =
    writeAtomically(path) { it.write(text.toByteArray(Charsets.UTF_8)) }

/**
 * As above, for content large enough to stream rather than hold as a string.
 *
 * [write] receives the temp file's stream; a HAR export runs a JSON generator
 * over it rather than building a document in memory first.
 */
fun writeAtomically(path: Path, write: (OutputStream) -> Unit) {
    path.parent?.let { Files.createDirectories(it) }
    val temp = path.resolveSibling("${path.fileName}.tmp")
    try {
        Files.newOutputStream(temp).use(write)
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: Exception) {
        Files.deleteIfExists(temp)
        throw failure
    }
}

/**
 * Decodes [path], or returns [default] if it cannot be read.
 *
 * Every store wants this and each had written it slightly differently — two
 * checking `exists`, two `isRegularFile`, two logging and two silent. The
 * distinction never mattered: a directory named `settings.json` would land in
 * the failure branch either way.
 *
 * Note what this is *not* for: a caller that must tell "unreadable" from
 * "absent" — because it is about to delete the file — needs to make that
 * distinction itself rather than take a default.
 */
inline fun <T> readOrDefault(path: Path, default: T, tag: String? = null, decode: (String) -> T): T = try {
    if (Files.isRegularFile(path)) decode(Files.readString(path)) else default
} catch (failure: Exception) {
    tag?.let { System.err.println("[$it] load failed, using defaults: $failure") }
    default
}
