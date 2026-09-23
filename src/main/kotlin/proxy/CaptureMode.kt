package org.bittrace.proxy

/**
 * Which capture profile the sidecar is started with.
 *
 * [BASIC] reports the traffic itself — flows, bodies, WebSocket messages.
 * [ADVANCED] adds the connection machinery underneath: the CONNECT tunnel that
 * carried it and the TLS handshake that secured it. That costs frames on every
 * tunnel and every handshake, so it is opt-in.
 *
 * The profile is fixed for the life of a sidecar process; changing it means a
 * restart. [ProxyStatus.capture] says which one is actually live.
 *
 * @property id the value stored in settings
 */
enum class CaptureMode(val id: String, val label: String) {
    BASIC("basic", "Basic"),
    ADVANCED("advanced", "Advanced");

    /** Extra command-line arguments the sidecar is started with. */
    val args: List<String> get() = if (this == ADVANCED) listOf("--advanced") else emptyList()

    companion object {
        /** Anything unrecognised — a hand-edited file, an older build — reads as basic. */
        fun fromId(id: String?): CaptureMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BASIC
    }
}
