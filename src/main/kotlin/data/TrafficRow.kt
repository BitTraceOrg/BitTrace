package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One row of the traffic table: the request skeleton that opened the flow plus
 * everything that arrives later for the same flow id.
 *
 * The late-arriving parts are Compose snapshot state, so any composable reading
 * them recomposes when a response lands on a row already on screen.
 */
class TrafficRow(
    val rowCount: Int,
    val request: InitialRequestData,
) {
    val id: String get() = request.id

    var response by mutableStateOf<InitialResponseData?>(null)
    var completeRequest by mutableStateOf<CompleteRequestMessage?>(null)
    var completeResponse by mutableStateOf<CompleteResponseMessage?>(null)

    override fun toString(): String =
        "TrafficRow(#$rowCount ${request.request.method} ${request.request.url} -> ${response?.response?.status})"
}
