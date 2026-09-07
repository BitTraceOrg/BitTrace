package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/**
 * A persistent, JSON-backed settings store.
 *
 * The current [settings] are Compose snapshot state, so any composable reading
 * them recomposes when they change. [update] mutates in memory immediately and
 * schedules a debounced write to disk, so live-dragging a splitter doesn't spam
 * the filesystem. The file lives under the OS config dir
 * (`%APPDATA%\BitTrace\settings.json` on Windows).
 */
class SettingsStore(private val file: Path = defaultPath()) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(Dispatchers.IO)
    private var saveJob: Job? = null

    var settings by mutableStateOf(load())
        private set

    /** Applies [transform], recomposes readers now, and persists after a pause. */
    fun update(transform: (Settings) -> Settings) {
        settings = transform(settings)
        val snapshot = settings
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400.milliseconds)
            save(snapshot)
        }
    }

    private fun load(): Settings = readOrDefault(file, Settings(), tag = "settings") {
        json.decodeFromString(Settings.serializer(), it)
    }

    private fun save(s: Settings) {
        // Atomic, like every other store. It was a plain write until this pass:
        // a crash between opening the file and finishing it left a truncated
        // `settings.json`, and the app would start on defaults having silently
        // lost everything the user had set.
        try {
            writeAtomically(file, json.encodeToString(Settings.serializer(), s))
        } catch (e: Exception) {
            System.err.println("[settings] save failed: $e")
        }
    }

    companion object {
        fun defaultPath(): Path = configFile("settings.json")
    }
}
