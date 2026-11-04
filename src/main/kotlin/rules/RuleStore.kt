package org.bittrace.rules

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.bittrace.data.configDir
import org.bittrace.data.writeAtomically
import org.bittrace.proxy.Commands
import org.bittrace.proxy.RuleStatusData

/**
 * The YAML both sides agree on. `encodeDefaults` so every file shows every
 * field — the same choice `appYaml` makes, and here it matters twice over:
 * the sidecar reads these with a different parser, and a field that is present
 * is one neither side has to agree on a default for.
 */
object RuleYaml {
    private val yaml = Yaml(configuration = YamlConfiguration(encodeDefaults = true, strictMode = false))

    fun encodeRule(rule: RuleFile): String = yaml.encodeToString(RuleFile.serializer(), rule.normalized())
    fun decodeRule(text: String): RuleFile = yaml.decodeFromString(RuleFile.serializer(), text)
    fun encodeManifest(m: RuleManifest): String = yaml.encodeToString(RuleManifest.serializer(), m)
    fun decodeManifest(text: String): RuleManifest = yaml.decodeFromString(RuleManifest.serializer(), text)
}

/**
 * The traffic rules, and the one place they are written.
 *
 * **The folder is the truth.** `rules.yaml` holds the order, each rule's
 * enabled flag and the switches; `<id>.yaml` holds one rule. The sidecar reads
 * the folder before it binds its port, so a restart needs nothing resent, and
 * after that this store announces each change on stdin — [notify] — so the
 * sidecar re-reads just that file. Every write is atomic (`writeAtomically`),
 * because the sidecar can be reading while this is writing.
 *
 * What the *sidecar* made of each file arrives back as `RuleStatus` frames and
 * lands in [status]. That is kept apart from [localErrors], which is what this
 * end could not read: a hand-edited file kaml refuses and ruamel accepts (or
 * the reverse) is exactly the case where the two have to be shown separately.
 *
 * Snapshot state throughout, mutated on the UI thread; the rules tool reads it
 * directly. There is no file watcher, as there is none anywhere in the app —
 * [reload] is the way a hand edit is picked up.
 */
class RuleStore(
    val dir: Path = configDir().resolve("rules"),
) {
    /** Sends a command to the running sidecar; a no-op while none is running. */
    var notify: (tag: Int, json: String) -> Unit = { _, _ -> }

    var manifest: RuleManifest by mutableStateOf(RuleManifest())
        private set

    /** Rules by id, as last read or written here. */
    val rules = mutableStateMapOf<String, RuleFile>()

    /** Files this end could not parse, by id (`rules` for the manifest). */
    val localErrors = mutableStateMapOf<String, String>()

    /** The sidecar's last word on each file, by id (`rules` for the manifest). */
    val status = mutableStateMapOf<String, RuleStatusData>()

    init {
        load()
    }

    /** Rules in manifest order, with their enabled flag. */
    val ordered: List<Pair<ManifestEntry, RuleFile?>>
        get() = manifest.rules.map { it to rules[it.id] }

    /** Whether [id] is in force: enabled itself, its kind enabled, and the master switch on. */
    fun isActive(id: String): Boolean {
        val entry = manifest.rules.firstOrNull { it.id == id } ?: return false
        val kind = rules[id]?.ruleKind ?: return false
        return manifest.enabled && entry.enabled && manifest.kinds[kind]
    }

    val activeCount: Int get() = manifest.rules.count { isActive(it.id) }

    // -- reading -------------------------------------------------------------

    private fun load() {
        localErrors.clear()
        rules.clear()
        val manifestFile = dir.resolve(MANIFEST)
        manifest = if (manifestFile.exists()) {
            runCatching { RuleYaml.decodeManifest(manifestFile.readText()) }
                .onFailure { localErrors[MANIFEST_ID] = describe(it) }
                .getOrDefault(RuleManifest())
        } else {
            RuleManifest()
        }
        for (entry in manifest.rules) {
            val file = fileOf(entry.id) ?: continue
            if (!file.exists()) {
                localErrors[entry.id] = "${file.name} does not exist"
                continue
            }
            runCatching { RuleYaml.decodeRule(file.readText()) }
                .onSuccess { rules[entry.id] = it }
                .onFailure { localErrors[entry.id] = describe(it) }
        }
    }

    /** Re-reads the folder here and in the sidecar — how a hand edit takes effect. */
    fun reload() {
        load()
        notify(Commands.RULES_RELOAD, "{}")
    }

    // -- writing -------------------------------------------------------------

    /** Adds [rule] at the end of the order, enabled. Returns its new id. */
    fun create(rule: RuleFile): String {
        val id = newId()
        writeRule(id, rule)
        writeManifest(manifest.copy(rules = manifest.rules + ManifestEntry(id)))
        return id
    }

    /** Replaces an existing rule's file. */
    fun save(id: String, rule: RuleFile) {
        require(manifest.rules.any { it.id == id }) { "no rule $id" }
        writeRule(id, rule)
        notify(Commands.RULE_CHANGED, """{"id":"$id"}""")
    }

    /** Removes a rule from the order, then its file, then any mocks it owned. */
    fun delete(id: String) {
        writeManifest(manifest.copy(rules = manifest.rules.filterNot { it.id == id }))
        fileOf(id)?.let { Files.deleteIfExists(it) }
        mockDir(id).takeIf { it.isDirectory() }?.toFile()?.deleteRecursively()
        rules.remove(id)
        localErrors.remove(id)
        status.remove(id)
        notify(Commands.RULE_REMOVED, """{"id":"$id"}""")
    }

    fun setEnabled(id: String, enabled: Boolean) =
        writeManifest(manifest.copy(rules = manifest.rules.map { if (it.id == id) it.copy(enabled = enabled) else it }))

    /** Moves [id] to [index] in the evaluation order. */
    fun move(id: String, index: Int) {
        val list = manifest.rules.toMutableList()
        val from = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val entry = list.removeAt(from)
        list.add(index.coerceIn(0, list.size), entry)
        writeManifest(manifest.copy(rules = list))
    }

    fun setMasterEnabled(on: Boolean) = writeManifest(manifest.copy(enabled = on))

    fun setKindEnabled(kind: RuleKind, on: Boolean) =
        writeManifest(manifest.copy(kinds = manifest.kinds.with(kind, on)))

    fun setBreakpointTimeout(seconds: Int) =
        writeManifest(manifest.copy(breakpointTimeoutSec = seconds.coerceIn(1, 24 * 3600)))

    /**
     * Writes a captured response as a Map local mock: the body to
     * `mocks/<id>/body`, and a rule that serves it with the captured status and
     * headers. Relative to the rules folder, so the folder can be copied
     * somewhere else whole and still work.
     */
    fun saveMock(name: String, match: RuleMatch, status: Int, headers: List<HeaderLine>, body: ByteArray): String {
        val id = newId()
        val target = mockDir(id).resolve("body")
        Files.createDirectories(target.parent)
        writeAtomically(target) { it.write(body) }
        // Framing headers describe the bytes as they were on the wire, which is
        // not what is on disk: the body was decoded when it was captured.
        val kept = headers.filterNot { it.name.lowercase() in FRAMING_HEADERS }
        val rule = RuleFile(
            name = name,
            kind = RuleKind.MAP_LOCAL.id,
            match = match,
            mapLocal = MapLocalSpec(path = "$MOCKS/$id/body", status = status, headers = kept),
        )
        writeRule(id, rule)
        writeManifest(manifest.copy(rules = manifest.rules + ManifestEntry(id)))
        return id
    }

    /** Records the sidecar's verdict on a file. Called from the reader thread. */
    fun onStatus(data: RuleStatusData) = onUi {
        if (data.removed) status.remove(data.id) else status[data.id] = data
    }

    private fun writeRule(id: String, rule: RuleFile) {
        val file = fileOf(id) ?: error("invalid rule id $id")
        val normalized = rule.normalized()
        writeAtomically(file, RuleYaml.encodeRule(normalized))
        rules[id] = normalized
        localErrors.remove(id)
    }

    /**
     * Writes the manifest and tells the sidecar. `RulesOrder` only re-reads the
     * manifest, so an id the manifest gains is announced first with its own
     * `RuleChanged` — the sidecar has then loaded the rule by the time the
     * order that puts it in force arrives. A new rule's file is always written
     * before the manifest that names it, for the same reason.
     */
    private fun writeManifest(m: RuleManifest) {
        val added = m.rules.map { it.id }.toSet() - manifest.rules.map { it.id }.toSet()
        writeAtomically(dir.resolve(MANIFEST), RuleYaml.encodeManifest(m))
        manifest = m
        localErrors.remove(MANIFEST_ID)
        for (id in added) notify(Commands.RULE_CHANGED, """{"id":"$id"}""")
        notify(Commands.RULES_ORDER, "{}")
    }

    private fun fileOf(id: String): Path? = if (ID.matches(id)) dir.resolve("$id.yaml") else null

    private fun mockDir(id: String): Path = dir.resolve(MOCKS).resolve(id)

    private fun newId(): String {
        while (true) {
            val id = buildString {
                append("r-")
                repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
            if (manifest.rules.none { it.id == id } && fileOf(id)?.exists() != true) return id
        }
    }

    /** Files in the folder that no manifest entry names — shown, never deleted. */
    fun strays(): List<String> =
        if (!dir.isDirectory()) emptyList()
        else dir.listDirectoryEntries("*.yaml")
            .map { it.name.removeSuffix(".yaml") }
            .filter { it != MANIFEST_ID && manifest.rules.none { e -> e.id == it } }

    companion object {
        const val MANIFEST = "rules.yaml"
        /** The id `RuleStatus` and [localErrors] use for the manifest itself. */
        const val MANIFEST_ID = "rules"
        const val MOCKS = "mocks"

        /** The same pattern the sidecar checks before it builds a path from an id. */
        val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        private val FRAMING_HEADERS = setOf("content-length", "content-encoding", "transfer-encoding")

        private fun describe(e: Throwable): String = e.message?.lineSequence()?.firstOrNull() ?: e.toString()

        private inline fun onUi(crossinline block: () -> Unit) {
            if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
        }
    }
}
