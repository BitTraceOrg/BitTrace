package org.bittrace.proxy

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

    val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

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
        return Path.of(System.getProperty("java.io.tmpdir"), "bittrace-sidecar-$id")
    }
}
