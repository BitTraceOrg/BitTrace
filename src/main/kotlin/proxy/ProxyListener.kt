package org.bittrace.proxy

import kotlinx.serialization.Serializable
import org.bittrace.data.CompleteRequestMessage
import org.bittrace.data.CompleteResponseMessage
import org.bittrace.data.ConnectRequestData
import org.bittrace.data.InitialRequestData
import org.bittrace.data.InitialResponseData

/**
 * A structured log line from the sidecar. The receipt time is stamped by the
 * consumer rather than carried on the wire.
 */
@Serializable
data class LogEntry(
    val level: String,
    val source: String,
    val message: String,
)

/**
 * Callbacks for everything the sidecar reports. These fire on the reader
 * threads, not the JavaFX thread — [org.bittrace.data.SessionStore] already
 * marshals what it receives, anything else you do here must marshal itself.
 *
 * Replaces the Tauri `app.emit(...)` events one-for-one:
 * `app-log`, `proxy-initial-request`, `proxy-initial-response`,
 * `proxy-complete-request`, `proxy-complete-response`.
 */
interface ProxyListener {
    fun onLog(entry: LogEntry) {}
    fun onInitialRequest(data: InitialRequestData) {}
    fun onInitialResponse(data: InitialResponseData) {}

    /** A client asked the proxy to open a tunnel. Its own flow, with its own id. */
    fun onConnectRequest(data: ConnectRequestData) {}

    /**
     * The tunnel was opened, or refused. The frame carries the same fields as
     * an ordinary initial response — status 200 on success, 0 with the reason
     * in `statusText` on failure — and shares the [onConnectRequest] flow id.
     */
    fun onConnectResponse(data: InitialResponseData) {}

    /** One chunk of a body large enough to be streamed; [body] is the chunk. */
    fun onBodyChunk(message: BodyChunkMessage, body: ByteArray) {}

    /** A streamed body ended; the totals say whether all of it arrived. */
    fun onBodyEnd(message: BodyEndMessage) {}

    /** [body] is the raw request body that travelled alongside the metadata. */
    fun onCompleteRequest(message: CompleteRequestMessage, body: ByteArray) {}

    /** [body] is the raw response body that travelled alongside the metadata. */
    fun onCompleteResponse(message: CompleteResponseMessage, body: ByteArray) {}

    /** The sidecar's PID as it reported it on stdout. */
    fun onPid(pid: String) {}

    /** A line the sidecar wrote to stderr. */
    fun onStderr(line: String) {}

    /** The process ended; [exitCode] is its status. */
    fun onExit(exitCode: Int) {}
}
