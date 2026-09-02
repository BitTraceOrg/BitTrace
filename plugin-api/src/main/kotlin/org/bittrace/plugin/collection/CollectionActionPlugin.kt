package org.bittrace.plugin.collection

import org.bittrace.plugin.Plugin
import java.nio.file.Path

/**
 * Which rung of the tree a collections menu was opened on.
 *
 * The three are the whole hierarchy — a project holds collections, a collection
 * holds requests, and nothing nests any deeper — so a plugin can decide what to
 * offer from the kind alone rather than by counting path segments.
 */
enum class CollectionTargetKind { PROJECT, COLLECTION, REQUEST }

/**
 * The node a collections-tree menu was opened on.
 *
 * Carries what the tree itself knows and nothing more: a collection of 500
 * requests is listed by walking a directory, not by parsing 500 files, so
 * handing plugins a parsed request here would undo that. A plugin that needs
 * the request's contents has [path] and can read it — at the moment someone
 * actually picks its action, which is the only moment it is worth the read.
 *
 * @property method the request's HTTP method; null for a project or collection.
 * @property project the name of the project this sits in, or its own name when
 *   [kind] is [CollectionTargetKind.PROJECT]. An action that reports what it did
 *   ("Exported 12 requests from Payments") has the context without walking back
 *   up [path], which is guesswork for a plugin that does not know the root.
 * @property collection the name of the collection this sits in, its own name
 *   for a collection, and null for a project.
 */
class CollectionTarget(
    val kind: CollectionTargetKind,
    val name: String,
    val path: Path,
    val project: String,
    val collection: String? = null,
    val method: String? = null,
) {
    val isProject: Boolean get() = kind == CollectionTargetKind.PROJECT

    val isCollection: Boolean get() = kind == CollectionTargetKind.COLLECTION

    val isRequest: Boolean get() = kind == CollectionTargetKind.REQUEST

    /** A project or a collection — anything holding others rather than held. */
    val isFolder: Boolean get() = !isRequest
}

/**
 * What an action can ask of the host while it runs.
 *
 * Deliberately two verbs. An action that needed more than "I changed the disk"
 * and "tell the user this" would be reaching into the app rather than extending
 * it, and every method added here is one every future host has to keep working.
 */
interface CollectionActionContext {

    /** Re-walks the collections folder. Call after changing anything on disk. */
    fun refresh()

    /**
     * Shows a one-line message in the API client's notice strip. For a result
     * or a failure — it is not a progress channel, and the last call wins.
     */
    fun notify(message: String)
}

/**
 * One item in a collections-tree context menu.
 *
 * @property enabled a disabled item is still shown. An action that does not
 *   apply at all should not be returned; one that applies but cannot run *right
 *   now* should be returned disabled, so the menu does not change shape between
 *   two rows that look the same.
 * @property order lower sorts first. Ties keep plugin load order, so a plugin's
 *   own items stay in the order it listed them.
 * @property perform what the item does. Runs on the UI thread — hand anything
 *   slow to a thread of your own and report back through [CollectionActionContext.notify].
 */
class CollectionAction(
    val label: String,
    val enabled: Boolean = true,
    val order: Int = 100,
    val perform: (CollectionActionContext) -> Unit,
)

/**
 * A plugin category that adds items to the context menu of a project, a
 * collection or a saved request.
 *
 * [actionsFor] is called each time a menu opens, and returns however many items
 * that node deserves — none, one, or several. Returning a list rather than a
 * single action is the point: "duplicate, export, copy path" is one plugin's
 * worth of thought about requests, not three plugins.
 *
 * The host's own Rename and Delete come first and are not replaceable; plugin
 * items follow, below a separator. Keep [actionsFor] cheap — it runs on every
 * menu open, so decide from the [CollectionTarget] rather than by touching disk.
 */
interface CollectionActionPlugin : Plugin {

    /** The items to offer for [target], or an empty list to add nothing. */
    fun actionsFor(target: CollectionTarget): List<CollectionAction>
}
