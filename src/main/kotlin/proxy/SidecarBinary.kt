package org.bittrace.proxy

import org.bittrace.data.Platform
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Locates the MITMConnect sidecar, which ships inside the application as a
 * classpath resource under `/sidecar/`.
 *
 * A process cannot be spawned from a jar entry, so the binary is unpacked to a
 * cache directory named after the hash of its contents. That makes extraction
 * idempotent across runs (a second launch reuses the file instead of rewriting
 * it, which would fail on Windows while an earlier copy is still running) and
 * makes a rebuilt sidecar land in a new directory rather than colliding with
 * the old one.
 */
object SidecarBinary {

    /** Classpath folder the binary is packaged under. */
    const val RESOURCE_DIR = "/sidecar"

    val isWindows: Boolean get() = Platform.isWindows

    /** "MITMConnect.exe" on Windows, "MITMConnect" elsewhere. */
    val filename: String
        get() = if (isWindows) "MITMConnect.exe" else "MITMConnect"

    /** Classpath path the binary is read from, e.g. `/sidecar/MITMConnect.exe`. */
    val resourcePath: String
        get() = "$RESOURCE_DIR/$filename"

    @Volatile
    private var cached: Path? = null

    /**
     * Returns the unpacked binary, extracting it on first use.
     *
     * A `bittrace.sidecar.dir` system property short-circuits this and uses a
     * binary already on disk, which is how you point a dev run at a freshly
     * built sidecar without rebuilding the app.
     *
     * @throws FileNotFoundException if the resource is not packaged and no
     *   override is set
     */
    @Synchronized
    fun resolve(): Path {
        cached?.let { if (Files.isRegularFile(it)) return it }

        System.getProperty("bittrace.sidecar.dir")?.let { override ->
            val path = Path.of(override).resolve(filename)
            if (!Files.isRegularFile(path)) {
                throw FileNotFoundException("bittrace.sidecar.dir is set but $path does not exist")
            }
            return path.also { cached = it }
        }

        val bytes = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: throw FileNotFoundException(
                "sidecar not packaged: expected $resourcePath on the classpath " +
                    "(put the binary in src/main/resources$RESOURCE_DIR/), " +
                    "or set -Dbittrace.sidecar.dir=<folder> to use one on disk"
            )

        val target = cacheDirectory(bytes).resolve(filename)
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.size.toLong()) {
            extract(bytes, target)
        }
        return target.also { cached = it }
    }

    /** Writes via a temp file in the same directory, so readers never see a partial binary. */
    private fun extract(bytes: ByteArray, target: Path) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "sidecar", ".tmp")
        try {
            Files.write(temp, bytes)
            temp.toFile().setExecutable(true, false)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            // Losing a race with another instance is fine as long as the
            // binary it wrote is there.
            if (!Files.isRegularFile(target)) throw e
        }
        target.toFile().setExecutable(true, false)
    }

    /** `<tmp>/bittrace-sidecar-<first 16 hex of sha-256>`. */
    private fun cacheDirectory(bytes: ByteArray): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        return Path.of(System.getProperty("java.io.tmpdir"), "$CACHE_PREFIX$id")
    }

    /** Names the extraction directory, and identifies one afterwards. */
    private const val CACHE_PREFIX = "bittrace-sidecar-"

    /**
     * Whether [command] is a sidecar this application extracted.
     *
     * Deliberately not "is it called MITMConnect": someone may have a build of
     * their own open, and a name match alone would make it ours to kill. What
     * makes it ours is the directory — either an extraction folder, whose name
     * this object chose, or the one a dev run was pointed at explicitly.
     *
     * The hash in an extraction folder is not checked, so a sidecar left behind
     * by an *older build* still counts. That is the point: it is holding the
     * listen port just as surely as a current one, and the version it was built
     * from makes no difference to that.
     */
    fun isExtractedSidecar(command: String?): Boolean {
        val path = command?.let { runCatching { Path.of(it) }.getOrNull() } ?: return false
        if (!path.fileName?.toString().equals(filename, ignoreCase = true)) return false
        val parent = path.parent ?: return false

        System.getProperty("bittrace.sidecar.dir")?.let { override ->
            val dir = runCatching { Path.of(override).toAbsolutePath().normalize() }.getOrNull()
            if (dir != null && dir == parent.toAbsolutePath().normalize()) return true
        }
        return parent.fileName?.toString()?.startsWith(CACHE_PREFIX) == true
    }
}
