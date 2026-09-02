package org.bittrace.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How many requests were captured on each day.
 *
 * The Home heatmap used to spread the session's flow count evenly across a
 * fixed grid, which drew a shape but told you nothing — a hundred flows in one
 * burst and a hundred over a week looked identical, and the picture reset every
 * time the app restarted. This is the real tally, and it outlives the session.
 *
 * Kept as ISO dates so the file is readable and so a day means the same thing
 * whatever timezone the machine wakes up in. Counts are per *day*, not per
 * timestamp, so the file stays a few kilobytes however much traffic goes by.
 */
class ActivityStore(private val file: Path = defaultPath()) {

    /** Snapshot-backed so the heatmap redraws as traffic arrives. */
    var days by mutableStateOf<Map<LocalDate, Int>>(emptyMap())
        private set

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var dirty = false

    init {
        days = load()
    }

    /**
     * Counts one captured request against the day it started.
     *
     * Takes the flow's own timestamp rather than "now": a flow that began before
     * midnight belongs to the day it began, and a replayed or delayed frame
     * should not land on the wrong square.
     */
    fun record(startedDateTime: String) {
        val day = dayOf(startedDateTime) ?: return
        days = days + (day to (days[day] ?: 0) + 1)
        dirty = true
    }

    /**
     * The last [count] days, oldest first, ending today — days with no traffic
     * included as zero, so the strip is a calendar rather than a list of the
     * days that happened to be busy.
     */
    fun recent(count: Int): List<Pair<LocalDate, Int>> {
        val today = LocalDate.now()
        return (count - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            day to (days[day] ?: 0)
        }
    }

    /** Writes the tally if anything changed. Cheap enough to call on a timer. */
    fun flush() {
        if (!dirty) return
        dirty = false
        try {
            Files.createDirectories(file.parent)
            val encoded = days.entries
                .sortedBy { it.key }
                .associate { it.key.format(ISO) to it.value }
            Files.writeString(file, json.encodeToString(SERIALIZER, encoded))
        } catch (e: Exception) {
            System.err.println("[activity] save failed: $e")
        }
    }

    private fun load(): Map<LocalDate, Int> = try {
        if (!Files.isRegularFile(file)) {
            emptyMap()
        } else {
            // A day older than the longest range the UI offers can never be
            // shown again, so it is dropped on read rather than kept forever.
            val cutoff = LocalDate.now().minusDays(RETAIN_DAYS)
            json.decodeFromString(SERIALIZER, Files.readString(file))
                .mapNotNull { (key, count) ->
                    runCatching { LocalDate.parse(key, ISO) }.getOrNull()
                        ?.takeIf { !it.isBefore(cutoff) }
                        ?.let { it to count }
                }
                .toMap()
        }
    } catch (e: Exception) {
        System.err.println("[activity] load failed, starting empty: $e")
        emptyMap()
    }

    private fun dayOf(startedDateTime: String): LocalDate? =
        org.bittrace.components.instantOf(startedDateTime)
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDate()

    companion object {
        /** Long enough to cover the widest range the dashboard offers, with slack. */
        private const val RETAIN_DAYS = 400L
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val SERIALIZER = MapSerializer(String.serializer(), Int.serializer())

        fun defaultPath(): Path {
            val base = System.getenv("APPDATA")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("BitTrace").resolve("activity.json")
        }
    }
}
