package org.bittrace.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.bittrace.data.SettingsStore

/** One request that was sent, and how often. */
@Serializable
data class HistoryEntry(
    val request: ApiRequest,
    val lastUsedMillis: Long = 0,
    val count: Int = 1,
)

@Serializable
private data class HistoryFile(val entries: List<HistoryEntry> = emptyList())

/**
 * Every distinct request the client has sent, newest first.
 *
 * "Distinct" is the point: hammering Send on the same call should leave one
 * entry with a rising count, not fifty identical rows. Two requests are the
 * same when their method, effective URL and body match — headers deliberately
 * do not count, so retrying with a fresh token updates the existing entry
 * instead of burying it under near-duplicates.
 *
 * Sent requests are recorded whatever the outcome. A request that failed is
 * often the one you most want to get back to.
 */
class HistoryStore(private val file: Path? = defaultPath()) {

    private val scope = CoroutineScope(Dispatchers.IO)

    var entries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /** Reads the history file. Blocking — call off the UI thread. */
    fun load() {
        val path = file ?: return
        entries = try {
            if (Files.isRegularFile(path)) {
                yaml.decodeFromString(HistoryFile.serializer(), Files.readString(path)).entries
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // A corrupt history is not worth failing the view over.
            emptyList()
        }
    }

    /** Files a sent request, folding it into an existing entry if it matches. */
    fun record(request: ApiRequest) {
        val signature = signatureOf(request)
        val existing = entries.firstOrNull { signatureOf(it.request) == signature }
        val entry = HistoryEntry(
            request = request,
            lastUsedMillis = System.currentTimeMillis(),
            count = (existing?.count ?: 0) + 1,
        )
        entries = (listOf(entry) + entries.filterNot { signatureOf(it.request) == signature }).take(CAPACITY)
        persist()
    }

    fun remove(entry: HistoryEntry) {
        val signature = signatureOf(entry.request)
        entries = entries.filterNot { signatureOf(it.request) == signature }
        persist()
    }

    fun clear() {
        entries = emptyList()
        persist()
    }

    /**
     * Writes on the IO dispatcher via a temp file, so a send is never delayed by
     * the disk and a crash mid-write cannot corrupt the history.
     */
    private fun persist() {
        val path = file ?: return
        val snapshot = HistoryFile(entries)
        scope.launch {
            runCatching {
                path.parent?.let { Files.createDirectories(it) }
                val temp = path.resolveSibling("${path.fileName}.tmp")
                Files.writeString(temp, yaml.encodeToString(HistoryFile.serializer(), snapshot))
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { System.err.println("[api] history save failed: $it") }
        }
    }

    /** Method + effective URL + body: what makes two sends "the same request". */
    private fun signatureOf(request: ApiRequest): String = buildString {
        append(request.method.uppercase()).append(' ').append(buildUrl(request)).append('\n')
        append(request.body.filePath.ifBlank { request.body.text })
    }

    companion object {
        /** Sits beside settings.json, like the collections and plugins folders. */
        fun defaultPath(): Path? = SettingsStore.defaultPath().parent?.resolve("history.yaml")

        private const val CAPACITY = 200

        private val yaml = Yaml(
            configuration = YamlConfiguration(encodeDefaults = true, strictMode = false),
        )
    }
}
