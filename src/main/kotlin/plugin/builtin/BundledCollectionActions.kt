package org.bittrace.plugin.builtin

import org.bittrace.plugin.PluginHost
import org.bittrace.plugin.collection.CollectionAction
import org.bittrace.plugin.collection.CollectionActionContext
import org.bittrace.plugin.collection.CollectionActionPlugin
import org.bittrace.plugin.collection.CollectionTarget
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The bundled collections-tree actions.
 *
 * These ship with the app but go through the same extension point an external
 * JAR would, which is the point: the seam is exercised by the app itself rather
 * than existing only on paper, so it cannot quietly rot.
 *
 * Both items are things the tree could not do before and neither is destructive
 * — Rename and Delete stay the host's, above the separator, because a plugin
 * that could remove them could make a collection uneditable.
 */
class BundledCollectionActions : CollectionActionPlugin {
    override val id = "bittrace.collection-actions"
    override val name = "Collection actions"

    /**
     * The host, kept from [init] for its clipboard.
     *
     * The loader inits a plugin before registering it, so this is set long
     * before any menu can be opened; the null branch below is what a host that
     * skipped the contract would get, not a state this app reaches.
     */
    private var host: PluginHost? = null

    override fun init(host: PluginHost) {
        this.host = host
    }

    /** Copies through the host, and says which way it went. */
    private fun copy(text: String, copied: String, context: CollectionActionContext) {
        val host = host
        when {
            host == null -> context.notify("No clipboard available.")
            host.copyToClipboard(text) -> context.notify(copied)
            else -> context.notify("Could not copy to the clipboard.")
        }
    }

    override fun actionsFor(target: CollectionTarget): List<CollectionAction> = buildList {
        add(
            CollectionAction(label = "Copy path", order = 10) { context ->
                copy(target.path.toString(), "Copied ${target.path}", context)
            },
        )
        // Duplicating a folder means copying a tree, which is a different
        // operation with its own failure modes; a request is one file.
        if (target.isRequest) {
            add(
                CollectionAction(label = "Duplicate", order = 20) { context ->
                    duplicate(target.path)
                        .onSuccess { copy -> context.refresh(); context.notify("Created ${copy.fileName}") }
                        .onFailure { context.notify("Could not duplicate ${target.name}: ${it.message}") }
                },
            )
        }
    }
}


/**
 * Copies [file] beside itself under the first free `name (n)` name.
 *
 * Numbered rather than `-copy`, so duplicating the same request twice gives two
 * files instead of a collision. `CREATE_NEW` does the checking: testing for
 * existence and then writing would leave a gap in which something else could
 * take the name.
 */
private fun duplicate(file: Path): Result<Path> = runCatching {
    val stem = file.fileName.toString().substringBeforeLast('.')
    val extension = file.fileName.toString().substringAfterLast('.', "")
    val suffix = if (extension.isEmpty()) "" else ".$extension"

    for (n in 2..MAX_COPIES) {
        val candidate = file.resolveSibling("$stem ($n)$suffix")
        val made = runCatching {
            Files.copy(file, candidate, StandardCopyOption.COPY_ATTRIBUTES)
        }
        if (made.isSuccess) return@runCatching candidate
        if (made.exceptionOrNull() !is java.nio.file.FileAlreadyExistsException) {
            throw made.exceptionOrNull()!!
        }
    }
    error("there are already $MAX_COPIES copies of this request")
}

/** A cap, so a bad state cannot turn one click into an unbounded loop of stats. */
private const val MAX_COPIES = 99
