package org.bittrace.proxy

import org.bittrace.data.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Whether the sidecar's root CA is trusted by this machine. */
enum class CertTrust {
    /** No CA on disk yet — the sidecar writes it the first time it runs. */
    MISSING,

    /** Present in the system trust store: HTTPS flows will decrypt. */
    TRUSTED,

    /** On disk but not trusted: clients will reject the intercepted connection. */
    NOT_TRUSTED,

    /** Present, but this platform gives no reliable way to check the store. */
    UNKNOWN,
}

/** What the settings pane shows about the CA. */
data class CertInfo(
    val trust: CertTrust,
    val path: Path?,
    val subject: String? = null,
    val expires: String? = null,
    val sha256: String? = null,
    val detail: String? = null,
)

/**
 * The sidecar's HTTPS root certificate: where it lives, whether the machine
 * trusts it, and installing it if not.
 *
 * The sidecar is a packaged mitmproxy launched without a `--set confdir`
 * override, so its CA is the standard `~/.mitmproxy/mitmproxy-ca-cert.*`, minted
 * on the sidecar's first run.
 *
 * Trust checking and installation both shell out to the platform, so every call
 * here blocks — call them off the UI thread. Installation deliberately goes
 * through the OS tools (`certutil`, `security`), which show the user their own
 * confirmation: adding a root CA lets this app decrypt the machine's TLS, and
 * that decision stays with the OS prompt rather than being made silently here.
 */
object CertificateAuthority {

    private val isWindows get() = Platform.isWindows
    private val isMac get() = Platform.isMac

    /** mitmproxy's default confdir. */
    val dir: Path = Paths.get(System.getProperty("user.home"), ".mitmproxy")

    /** The CA file to install, preferring the DER form Windows expects. */
    fun certFile(): Path? = CERT_NAMES.map(dir::resolve).firstOrNull { Files.isRegularFile(it) }

    /** Reads the CA and checks it against the platform trust store. Blocking. */
    fun inspect(): CertInfo {
        val path = certFile()
            ?: return CertInfo(CertTrust.MISSING, null, detail = "Start the proxy once to generate it.")
        val cert = readCertificate(path)
            ?: return CertInfo(CertTrust.UNKNOWN, path, detail = "Certificate could not be parsed.")

        return CertInfo(
            trust = trustOf(cert),
            path = path,
            subject = cert.subjectX500Principal.name,
            expires = EXPIRY.format(cert.notAfter.toInstant()),
            sha256 = fingerprint(cert),
            detail = if (isWindows || isMac) null else "Trust state can't be read on this platform.",
        )
    }

    /**
     * Adds the CA to the current user's trust store via the platform tool.
     * Blocking; returns the tool's output, or a failure carrying its message.
     */
    fun install(): Result<String> {
        val path = certFile() ?: return Result.failure(IllegalStateException("No certificate to install yet."))
        val command = when {
            isWindows -> listOf("certutil", "-addstore", "-user", "Root", path.toString())
            isMac -> listOf(
                "security", "add-trusted-cert", "-r", "trustRoot",
                "-k", Paths.get(System.getProperty("user.home"), "Library/Keychains/login.keychain-db").toString(),
                path.toString(),
            )
            // Distributions disagree on both the directory and the refresh
            // command, and each needs root; point the user at their own docs.
            else -> return Result.failure(
                IllegalStateException("Install manually: copy $path into your system trust store."),
            )
        }
        return runCommand(command)
    }

    /** True where [install] can actually do the work. */
    val canInstall: Boolean get() = isWindows || isMac

    // --- internals ---

    private fun trustOf(cert: X509Certificate): CertTrust = when {
        isWindows -> windowsTrust(cert)
        isMac -> macTrust()
        else -> CertTrust.UNKNOWN
    }

    /** Scans the Windows user+machine root stores for the very same certificate. */
    private fun windowsTrust(cert: X509Certificate): CertTrust = try {
        val store = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
        val found = store.aliases().asSequence().any { alias ->
            (store.getCertificate(alias) as? X509Certificate)?.equals(cert) == true
        }
        if (found) CertTrust.TRUSTED else CertTrust.NOT_TRUSTED
    } catch (e: Exception) {
        CertTrust.UNKNOWN
    }

    /** `verify-cert` succeeds only when the keychain actually trusts the root. */
    private fun macTrust(): CertTrust {
        val path = certFile() ?: return CertTrust.MISSING
        val result = runCommand(listOf("security", "verify-cert", "-c", path.toString()))
        return if (result.isSuccess) CertTrust.TRUSTED else CertTrust.NOT_TRUSTED
    }

    private fun readCertificate(path: Path): X509Certificate? = try {
        Files.newInputStream(path).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as? X509Certificate
        }
    } catch (e: Exception) {
        null
    }

    private fun fingerprint(cert: X509Certificate): String? = try {
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        null
    }

    /** Runs [command], returning its combined output or a failure with the same. */
    private fun runCommand(command: List<String>): Result<String> = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        when {
            !finished -> {
                process.destroyForcibly()
                Result.failure(IllegalStateException("${command.first()} timed out."))
            }
            process.exitValue() == 0 -> Result.success(output)
            else -> Result.failure(IllegalStateException(output.ifBlank { "${command.first()} failed." }))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private val CERT_NAMES = listOf("mitmproxy-ca-cert.cer", "mitmproxy-ca-cert.pem")

    private val EXPIRY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
}
