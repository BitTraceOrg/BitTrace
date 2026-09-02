package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
            delay(400)
            save(snapshot)
        }
    }

    private fun load(): Settings = try {
        if (Files.exists(file)) json.decodeFromString(Settings.serializer(), Files.readString(file))
        else Settings()
    } catch (e: Exception) {
        System.err.println("[settings] load failed, using defaults: $e")
        Settings()
    }

    private fun save(s: Settings) {
        try {
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, json.encodeToString(Settings.serializer(), s))
        } catch (e: Exception) {
            System.err.println("[settings] save failed: $e")
        }
    }

    companion object {
        fun defaultPath(): Path {
            val base = System.getenv("APPDATA")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("BitTrace").resolve("settings.json")
        }
    }
}
