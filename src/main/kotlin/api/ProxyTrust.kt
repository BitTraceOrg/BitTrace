package org.bittrace.api

import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import org.bittrace.proxy.CertificateAuthority

/**
 * Trust for requests sent through BitTrace's own proxy.
 *
 * Traffic through the sidecar is re-signed by mitmproxy's root CA, which the
 * JVM does not trust — and, on Windows, would not trust even if the user
 * installed it, since the JVM reads its own `cacerts` rather than the OS store.
 * Rather than making people install a root CA system-wide just to use the API
 * client, this trusts that one CA **in addition to** the platform defaults, and
 * only inside this client.
 *
 * The managers are [X509ExtendedTrustManager], not the plain interface. That is
 * load-bearing: `HttpClient` only performs hostname verification when the trust
 * manager is the extended type and the engine/socket overloads are implemented.
 * A plain `X509TrustManager` here would silently accept a valid certificate
 * issued for any other host.
 */
object ProxyTrust {

    /**
     * A context trusting the platform CAs plus mitmproxy's, or null when the CA
     * has not been minted yet — in which case the caller leaves `HttpClient` on
     * its default context and plain HTTP still works.
     */
    fun sslContext(): SSLContext? {
        val system = platformTrust() ?: return null
        val mitm = mitmTrust() ?: return null
        return runCatching {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(EitherTrust(system, mitm)), SecureRandom())
            }
        }.getOrNull()
    }

    /** True once the sidecar has written its CA — the API client can do HTTPS. */
    fun caAvailable(): Boolean = CertificateAuthority.certFile() != null

    private fun platformTrust(): X509ExtendedTrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            // A null KeyStore means "the JVM's own cacerts", which survives jlink.
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
    }.getOrNull()

    private fun mitmTrust(): X509ExtendedTrustManager? = runCatching {
        val path = CertificateAuthority.certFile() ?: return null
        val certificate = Files.newInputStream(path).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        } as? X509Certificate ?: return null

        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("mitmproxy", certificate)
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
    }.getOrNull()

    /**
     * Accepts a chain if either manager does, so adding the proxy CA never
     * weakens validation of anything else — an ordinary certificate is still
     * checked, and hostname verification still runs, because every extended
     * overload is delegated.
     */
    private class EitherTrust(
        private val system: X509ExtendedTrustManager,
        private val mitm: X509ExtendedTrustManager,
    ) : X509ExtendedTrustManager() {

        private inline fun either(first: () -> Unit, second: () -> Unit) =
            try {
                first()
            } catch (e: CertificateException) {
                second()
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            either({ system.checkServerTrusted(chain, authType, engine) }) {
                mitm.checkServerTrusted(chain, authType, engine)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            either({ system.checkServerTrusted(chain, authType, socket) }) {
                mitm.checkServerTrusted(chain, authType, socket)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            either({ system.checkServerTrusted(chain, authType) }) {
                mitm.checkServerTrusted(chain, authType)
            }

        // We never present a client certificate, so these just delegate.
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            system.checkClientTrusted(chain, authType, engine)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            system.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + mitm.acceptedIssuers
    }
}
