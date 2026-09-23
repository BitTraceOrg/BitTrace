package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * What a client offered when it began a TLS handshake, before anything was
 * agreed — the only place its own preferences are visible. Advanced capture
 * only.
 *
 * [clientConnectionId] is the same value `InitialRequest` carries, so a hello
 * can be tied to the requests that later travel over the connection.
 */
@Serializable
data class TlsClientHelloData(
    val clientConnectionId: String = "",
    val startedDateTime: String = "",
    val clientAddress: String = "",
    val destination: String = "",
    /** Null for a connection made to a bare IP. */
    val sni: String? = null,
    /** Empty when no ALPN was offered. */
    val alpnProtocols: List<String> = emptyList(),
    /** Raw IANA cipher-suite numbers, in the client's order of preference. */
    val cipherSuites: List<Int> = emptyList(),
    val cipherSuiteCount: Int = 0,
    /** Extension type numbers, in the order sent. */
    val extensions: List<Int> = emptyList(),
    val ignoreConnection: Boolean = false,
    /** Present only when the client sent the extension. */
    val supportedVersions: List<String>? = null,
    /** Present only when the client sent the extension. */
    val supportedGroups: List<String>? = null,
) {
    /** [cipherSuites] by IANA name where known, hex otherwise; GREASE values dropped. */
    val cipherSuiteNames: List<String>
        get() = cipherSuites.filterNot(::isGrease).map { CIPHER_NAMES[it] ?: hex(it) }

    /** [extensions] by name where known, number otherwise; GREASE values dropped. */
    val extensionNames: List<String>
        get() = extensions.filterNot(::isGrease).map { EXTENSION_NAMES[it] ?: it.toString() }
}

private fun hex(value: Int) = "0x%04x".format(value)

/**
 * RFC 8701 GREASE: values of the form 0x?A?A a client sends to keep
 * servers tolerant of unknowns. They mean nothing and would only
 * clutter the list.
 */
private fun isGrease(value: Int) = (value and 0x0F0F) == 0x0A0A && (value shr 8) == (value and 0xFF)

/** The suites a current browser or client library actually offers. */
private val CIPHER_NAMES = mapOf(
    0x1301 to "TLS_AES_128_GCM_SHA256",
    0x1302 to "TLS_AES_256_GCM_SHA384",
    0x1303 to "TLS_CHACHA20_POLY1305_SHA256",
    0xC02B to "ECDHE-ECDSA-AES128-GCM-SHA256",
    0xC02F to "ECDHE-RSA-AES128-GCM-SHA256",
    0xC02C to "ECDHE-ECDSA-AES256-GCM-SHA384",
    0xC030 to "ECDHE-RSA-AES256-GCM-SHA384",
    0xCCA9 to "ECDHE-ECDSA-CHACHA20-POLY1305",
    0xCCA8 to "ECDHE-RSA-CHACHA20-POLY1305",
    0xC013 to "ECDHE-RSA-AES128-SHA",
    0xC014 to "ECDHE-RSA-AES256-SHA",
    0xC009 to "ECDHE-ECDSA-AES128-SHA",
    0xC00A to "ECDHE-ECDSA-AES256-SHA",
    0x009C to "AES128-GCM-SHA256",
    0x009D to "AES256-GCM-SHA384",
    0x002F to "AES128-SHA",
    0x0035 to "AES256-SHA",
    0x00FF to "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
)

private val EXTENSION_NAMES = mapOf(
    0 to "server_name",
    5 to "status_request",
    10 to "supported_groups",
    11 to "ec_point_formats",
    13 to "signature_algorithms",
    16 to "alpn",
    18 to "signed_certificate_timestamp",
    21 to "padding",
    23 to "extended_master_secret",
    27 to "compress_certificate",
    35 to "session_ticket",
    41 to "pre_shared_key",
    43 to "supported_versions",
    45 to "psk_key_exchange_modes",
    51 to "key_share",
    17513 to "application_settings",
    65037 to "encrypted_client_hello",
    65281 to "renegotiation_info",
)

/**
 * One hop's handshake finishing, or failing. An intercepted HTTPS connection
 * produces two: [SIDE_SERVER] (proxy → origin, the real chain) and
 * [SIDE_CLIENT] (proxy → client, the certificate the proxy generated).
 *
 * A failed handshake produces no flow and no error hook, so this frame is the
 * only thing that explains why a site simply did not load.
 */
@Serializable
data class TlsHandshakeData(
    val clientConnectionId: String = "",
    val connectionId: String = "",
    val side: String = "",
    val established: Boolean = false,
    val timestamp: String = "",
    val address: String = "",
    val sni: String? = null,
    /** Negotiated; null on a failed handshake. */
    val version: String? = null,
    val cipher: String? = null,
    val alpn: String? = null,
    val alpnOffers: List<String> = emptyList(),
    /** mitmproxy's rendering of the alert or failure; null once established. */
    val error: String? = null,
    /**
     * What the peer presented. On a failed handshake it is read back off the
     * SSL object, so a verification failure still names the rejected cert.
     * Capped at 10; [certificateCount] is the true length.
     */
    val certificates: List<TlsCertificate> = emptyList(),
    val certificateCount: Int = 0,
    /** What this proxy presented on the hop — on [SIDE_CLIENT], the interception cert. */
    val presentedCertificate: TlsCertificate? = null,
) {
    companion object {
        const val SIDE_CLIENT = "client"
        const val SIDE_SERVER = "server"
    }
}

/**
 * Certificate metadata, not DER. Every field is nullable because the sidecar
 * degrades a malformed certificate to a partial description rather than
 * dropping the frame — and malformed ones are exactly what fails handshakes.
 */
@Serializable
data class TlsCertificate(
    val subject: String? = null,
    val issuer: String? = null,
    val commonName: String? = null,
    val organization: String? = null,
    val serial: String? = null,
    val notBefore: String? = null,
    val notAfter: String? = null,
    val expired: Boolean? = null,
    val isCa: Boolean? = null,
    val fingerprintSha256: String? = null,
    val keyAlgorithm: String? = null,
    val keyBits: Int? = null,
    /** Capped at 50; [altNameCount] is the real total. */
    val altNames: List<String> = emptyList(),
    val altNameCount: Int = 0,
    val altNamesTruncated: Boolean = false,
)

/**
 * Everything captured about the TLS on one client connection, shared by every
 * row that travelled over it — the CONNECT that opened the tunnel and each
 * request inside it — since they all carry the same `clientConnectionId`.
 *
 * Kept per connection rather than per flow because a handshake belongs to no
 * flow: one that fails produces no request at all, and this is then the only
 * place the failure lands. Snapshot state, because the handshakes finish after
 * the CONNECT row is already on screen.
 */
class TlsConnection(val clientConnectionId: String) {
    var clientHello by mutableStateOf<TlsClientHelloData?>(null)

    /** Proxy → origin: the origin's real chain and what was negotiated with it. */
    var serverHandshake by mutableStateOf<TlsHandshakeData?>(null)

    /** Proxy → client: the certificate this proxy generated and presented. */
    var clientHandshake by mutableStateOf<TlsHandshakeData?>(null)

    /** Takes a handshake frame onto whichever hop it describes. */
    fun record(handshake: TlsHandshakeData) {
        if (handshake.side == TlsHandshakeData.SIDE_CLIENT) clientHandshake = handshake
        else serverHandshake = handshake
    }

    /** The first hop that failed, or null while none has. */
    val failure: TlsHandshakeData?
        get() = serverHandshake?.takeIf { !it.established } ?: clientHandshake?.takeIf { !it.established }

    /** Negotiated version, preferring the client hop — the one the row's own `_tls` describes. */
    val version: String? get() = clientHandshake?.version ?: serverHandshake?.version

    val sni: String? get() = clientHello?.sni ?: serverHandshake?.sni ?: clientHandshake?.sni

    val cipher: String? get() = serverHandshake?.cipher ?: clientHandshake?.cipher

    val alpn: String? get() = serverHandshake?.alpn ?: clientHandshake?.alpn

    /** The origin's leaf certificate — what the site actually presented. */
    val serverCertificate: TlsCertificate? get() = serverHandshake?.certificates?.firstOrNull()

    /** Nothing has arrived yet: no hello, no handshake. */
    val isEmpty: Boolean get() = clientHello == null && serverHandshake == null && clientHandshake == null
}
