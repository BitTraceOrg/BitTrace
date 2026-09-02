package org.bittrace.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import org.bittrace.data.SettingsStore

/** A node in the collections tree. The tree mirrors the folder layout exactly. */
sealed interface Node {
    val path: Path
    val name: String
}

/**
 * A node that holds others — a project or a collection.
 *
 * The two are separate types because they sit at fixed, different levels and
 * mean different things, but everything that merely *contains* things (the
 * tree's expansion, its flatten pass) wants one name for both.
 */
sealed interface FolderNode : Node {
    val children: List<Node>
}

/** A project: the top level, holding collections and nothing else. */
class ProjectNode(
    override val path: Path,
    override val name: String,
    override val children: List<CollectionNode>,
) : FolderNode

/** A collection inside a project, holding saved requests. */
class CollectionNode(
    override val path: Path,
    override val name: String,
    override val children: List<RequestNode>,
) : FolderNode

/**
 * A saved request. The body is parsed on demand, not during the walk.
 *
 * [method] is the one field the tree needs before you open anything, since it
 * is what the row is labelled with — read off the file cheaply rather than by
 * parsing it. See `methodIn`.
 */
class RequestNode(override val path: Path, override val name: String, val method: String = "GET") : Node

/**
 * The saved requests, stored one-to-one with the filesystem in three fixed
 * levels: a project is a folder under `%APPDATA%\BitTrace\collections`, a
 * collection is a folder inside a project, and a request is a `.yaml` file
 * inside a collection.
 *
 * Three levels rather than arbitrary nesting because the depth is what carries
 * the meaning — "which project is this?" is the question you ask before "which
 * collection?", and a tree that let a collection hold collections could answer
 * neither. Anything at the wrong depth (a stray `.yaml` beside a project, a
 * folder inside a collection) is not shown; it is left where it is on disk
 * rather than moved or deleted.
 *
 * The tree carries only names and paths — a collection of 500 requests costs
 * one directory walk, not 500 YAML parses. A request is read when it is opened.
 *
 * Saving is explicit, unlike [SettingsStore]'s debounce. That store is driven by
 * continuous input (dragging a splitter) where intermediate values are
 * meaningless; a request is authored content, where autosaving would commit a
 * stray keystroke before the user could think better of it.
 */
class CollectionStore(private val root: Path? = defaultRoot()) {

    /** The projects as last loaded. Snapshot state, so the view recomposes on reload. */
    var tree by mutableStateOf<List<ProjectNode>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val available: Boolean get() = root != null

    /** Re-walks the collections folder. Blocking — call off the UI thread. */
    fun reload() {
        val dir = root ?: return
        tree = try {
            error = null
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                adoptLegacyLayout(dir)
                foldersIn(dir).map { project ->
                    ProjectNode(project, project.name, foldersIn(project).map(::collectionAt))
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
            emptyList()
        }
    }

    /** Folders first, then requests, each alphabetical — the filesystem's own order. */
    private fun collectionAt(dir: Path): CollectionNode = CollectionNode(
        dir,
        dir.name,
        entriesIn(dir)
            .filter { !it.isDirectory() && it.extension.equals("yaml", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { RequestNode(it, it.nameWithoutExtension, methodIn(it)) },
    )

    /** Child folders, alphabetical, with dot-folders (notably `.trash`) hidden. */
    private fun foldersIn(dir: Path): List<Path> = entriesIn(dir)
        .filter { it.isDirectory() && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }

    private fun entriesIn(dir: Path): List<Path> = Files.newDirectoryStream(dir).use { it.toList() }

    /**
     * Moves a pre-project layout under one project, once.
     *
     * Before projects existed a collection was a folder at the root, so reading
     * that layout with today's walk would take every collection for a project
     * and show none of its requests. Rather than let them silently vanish, the
     * old collections are moved wholesale into a single new project — the same
     * folders, one level down, still copyable and still version-controllable.
     *
     * The tell is a top-level folder holding a request directly, which the new
     * layout never produces. Nothing else is touched: folders are moved, never
     * rewritten, so an interrupted migration leaves whole collections on both
     * sides rather than a torn one.
     */
    private fun adoptLegacyLayout(dir: Path) {
        val stale = foldersIn(dir)
        if (stale.none { holdsRequestDirectly(it) }) return
        val project = freeName(dir, LEGACY_PROJECT) ?: return
        Files.createDirectories(project)
        stale.forEach { collection ->
            runCatching { Files.move(collection, project.resolve(collection.name)) }
        }
    }

    private fun holdsRequestDirectly(dir: Path): Boolean = runCatching {
        entriesIn(dir).any { !it.isDirectory() && it.extension.equals("yaml", ignoreCase = true) }
    }.getOrDefault(false)

    /**
     * The method a saved request uses, read without parsing the file.
     *
     * The walk's whole point is that listing a collection costs a directory walk
     * rather than a YAML parse per request, and the tree needs exactly one field
     * out of each file to label its rows. So this reads lines until it finds
     * `method:` and stops — kaml writes top-level keys in declaration order, so
     * in a file this app wrote it is the second line. The line cap is what keeps
     * a hand-written or corrupt file from being read from end to end.
     *
     * Anything unreadable falls back to GET, which is [ApiRequest]'s own default
     * and therefore what a file with no `method:` key actually means.
     */
    private fun methodIn(file: Path): String = try {
        Files.newBufferedReader(file).use { reader ->
            generateSequence { reader.readLine() }
                .take(METHOD_SCAN_LINES)
                .firstOrNull { it.startsWith("method:") }
                ?.substringAfter(':')
                ?.trim()
                ?.trim('"', '\'')
                ?.uppercase()
                ?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    } ?: "GET"

    fun read(node: RequestNode): Result<ApiRequest> = runCatching {
        // The file stem is the display name: renaming on disk is a rename, and
        // the name inside the file is only a fallback for hand-written files.
        RequestYaml.decode(Files.readString(node.path)).copy(name = node.name)
    }

    /**
     * Writes [request] to [path] via a temp file in the same directory, so a
     * failure part-way cannot truncate an existing request.
     */
    fun save(path: Path, request: ApiRequest): Result<Unit> = runCatching {
        path.parent?.let { Files.createDirectories(it) }
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, RequestYaml.encode(request))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        reload()
    }

    /** Creates an empty project folder. */
    fun createProject(displayName: String): Result<Path> = runCatching {
        val dir = root ?: error("No collections folder available.")
        make(dir, displayName)
    }

    /** Creates an empty collection folder inside [project]. */
    fun createCollection(project: Path, displayName: String): Result<Path> = runCatching {
        check(depthOf(project) == PROJECT_DEPTH) { "A collection has to be created inside a project." }
        make(project, displayName)
    }

    private fun make(parent: Path, displayName: String): Path {
        val safe = fileNameFor(displayName) ?: error("'$displayName' is not a usable folder name.")
        val target = parent.resolve(safe)
        if (Files.exists(target)) error("'$safe' already exists.")
        Files.createDirectories(target)
        reload()
        return target
    }

    /**
     * Creates a project with the first free default name.
     *
     * Naming happens here rather than in the view so the toolbar can make one
     * without knowing what is already on disk; renaming is inline in the tree.
     */
    fun createNamedProject(): Result<Path> {
        val dir = root ?: return Result.failure(IllegalStateException("No collections folder available."))
        val target = freeName(dir, "New project")
            ?: return Result.failure(IllegalStateException("Too many projects are called 'New project'."))
        return createProject(target.name)
    }

    /**
     * Creates a collection with the first free default name inside [project].
     *
     * A null [project] means "wherever a collection would sensibly go" — the
     * only project when there is one, and a project made for it when there is
     * none. That is what lets the New collection menu item work without the
     * menu bar knowing what the tree has selected; a caller that does know
     * passes the project.
     */
    fun createNamedCollection(project: Path? = null): Result<Path> {
        val parent = project
            ?: soleProject()
            ?: return createNamedProject().mapCatching { made -> createNamedCollection(made).getOrThrow() }
        val target = freeName(parent, "New collection")
            ?: return Result.failure(IllegalStateException("Too many collections are called 'New collection'."))
        return createCollection(parent, target.name)
    }

    /**
     * Creates an empty request with the first free default name in [collection].
     *
     * Written to disk immediately, unlike the New request in the menu bar, which
     * opens an unsaved tab. The difference is what was asked for: a request made
     * *in* a collection belongs to it from the start, and a file that only exists
     * once you remember to save is not one you made in a folder.
     */
    fun createNamedRequest(collection: Path): Result<Path> = runCatching {
        check(depthOf(collection) == COLLECTION_DEPTH) { "Requests are created inside a collection." }
        val target = freeName(collection, "New request", ".yaml")
            ?: error("Too many requests are called 'New request'.")
        val name = target.name.substringBeforeLast('.')
        Files.writeString(target, RequestYaml.encode(ApiRequest(name = name)))
        reload()
        target
    }

    /** The project a nameless collection belongs in, when there is no doubt. */
    private fun soleProject(): Path? = tree.singleOrNull()?.path

    /**
     * `<stem>`, or `<stem> 2`, `<stem> 3`… — the first that is free in [parent].
     *
     * Null once the cap is reached, which is a caller's error to report rather
     * than a name to keep hunting for.
     */
    private fun freeName(parent: Path, stem: String): Path? = freeName(parent, stem, suffix = "")

    /**
     * As above, keeping [suffix] on the end — `Auth 2.yaml`, not `Auth.yaml 2`.
     *
     * The number goes on the stem because that is where a reader looks for it,
     * and because a file whose extension has drifted is a file nothing will
     * open.
     */
    private fun freeName(parent: Path, stem: String, suffix: String): Path? {
        for (index in 1..MAX_DEFAULT_NAMES) {
            val safe = fileNameFor(if (index == 1) stem else "$stem $index") ?: continue
            val candidate = parent.resolve("$safe$suffix")
            if (!Files.exists(candidate)) return candidate
        }
        return null
    }

    /** The path a request with this name would occupy inside [collection]. */
    fun pathFor(collection: Path, displayName: String): Result<Path> = runCatching {
        check(depthOf(collection) == COLLECTION_DEPTH) { "Requests are saved into a collection." }
        val safe = fileNameFor(displayName) ?: error("'$displayName' is not a usable file name.")
        val target = collection.resolve("$safe.yaml")
        check(target.toString().length < MAX_PATH_CHARS) { "That path would be too long." }
        target
    }

    /**
     * The collection [path] would save into: itself, or the collection holding
     * it. Null for a project or for anything outside the collections folder —
     * a project is not somewhere a request can go.
     */
    fun collectionFor(path: Path?): Path? = when (depthOf(path ?: return null)) {
        COLLECTION_DEPTH -> path.takeIf { it.isDirectory() }
        REQUEST_DEPTH -> path.parent
        else -> null
    }

    /** How many levels below the collections root [path] sits; -1 if it is outside. */
    private fun depthOf(path: Path): Int {
        val dir = root ?: return -1
        return runCatching { dir.relativize(path) }
            .map { relative -> if (relative.startsWith("..")) -1 else relative.nameCount }
            .getOrDefault(-1)
    }

    /** Renames a project, collection or request, refusing rather than auto-suffixing a clash. */
    fun rename(node: Node, displayName: String): Result<Path> = runCatching {
        val safe = fileNameFor(displayName) ?: error("'$displayName' is not a usable name.")
        val parent = node.path.parent ?: error("Cannot rename this item.")
        val target = if (node is RequestNode) parent.resolve("$safe.yaml") else parent.resolve(safe)
        if (Files.exists(target) && target != node.path) error("'$safe' already exists.")
        Files.move(node.path, target)
        reload()
        target
    }

    /**
     * Moves a node into `.trash/<timestamp>/` rather than deleting it.
     *
     * One `Files.move` instead of one `Files.delete`, and "I just deleted my
     * whole project" stops being unrecoverable.
     */
    fun delete(node: Node): Result<Unit> = runCatching {
        val dir = root ?: error("No collections folder available.")
        val bin = dir.resolve(".trash").resolve(LocalDateTime.now().format(STAMP))
        Files.createDirectories(bin)
        Files.move(node.path, bin.resolve(node.path.name), StandardCopyOption.REPLACE_EXISTING)
        reload()
    }

    /**
     * Zips [node]'s contents to [target].
     *
     * Contents rather than the folder itself, so the archive opens onto what is
     * inside it and [importInto] can unpack it wherever you point — see
     * `zipDirectory`. A request is refused: it is already one file, and copying
     * it needs no help from this app.
     */
    fun exportNode(node: Node, target: Path): Result<Int> = runCatching {
        check(node !is RequestNode) { "Only a project or a collection can be exported." }
        zipDirectory(node.path, target).getOrThrow()
    }

    /**
     * Unpacks [archive] into [node].
     *
     * What is allowed depends on where it lands, because the layout has exactly
     * three levels and an import must not be the thing that breaks that: a
     * project takes folders of requests, a collection takes request files.
     * Anything else is reported rather than written, so a zip unpacked at the
     * wrong level says so instead of quietly producing half a tree.
     *
     * Nothing on disk is replaced. A name already in use gets numbered, the same
     * way a second `New collection` does — an import is not a restore, and
     * finding out afterwards that it overwrote a morning's work is not a
     * trade-off worth offering.
     */
    fun importInto(node: Node, archive: Path): Result<ImportReport> = runCatching {
        val depth = depthOf(node.path)
        check(depth == PROJECT_DEPTH || depth == COLLECTION_DEPTH) {
            "A zip can only be imported into a project or a collection."
        }
        val taken = mutableSetOf<String>()

        val report = unzipInto(archive, node.path) { item ->
            val wanted = when (depth) {
                // A folder holds requests; a loose file at a project's level has
                // nowhere legal to go.
                PROJECT_DEPTH -> if (item.isDirectory) item.name to "" else null
                // A collection holds `.yaml` files and nothing else.
                else -> if (!item.isDirectory && item.name.endsWith(".yaml", true)) {
                    item.name.substringBeforeLast('.') to ".yaml"
                } else {
                    null
                }
            }
            wanted?.let { (stem, suffix) ->
                // `taken` covers names claimed earlier in this same import,
                // which are not on disk yet when the next one is resolved.
                var candidate = freeName(node.path, stem, suffix)?.name
                var bump = 1
                while (candidate != null && candidate in taken && bump < MAX_DEFAULT_NAMES) {
                    bump++
                    candidate = freeName(node.path, "$stem $bump", suffix)?.name
                }
                candidate?.also { taken += it }
            }
        }.getOrThrow()

        reload()
        report
    }

    companion object {
        /** Sits next to settings.json, exactly as the plugins folder does. */
        fun defaultRoot(): Path? = SettingsStore.defaultPath().parent?.resolve("collections")

        /** Levels below the collections root, one per rung of the hierarchy. */
        private const val PROJECT_DEPTH = 1
        private const val COLLECTION_DEPTH = 2
        private const val REQUEST_DEPTH = 3

        /** Where the pre-project collections are gathered, once. */
        private const val LEGACY_PROJECT = "My project"

        /** Far enough to reach `method:` in anything sanely written, and no further. */
        private const val METHOD_SCAN_LINES = 8

        /** A cap, so a crowded folder cannot turn one click into an unbounded search. */
        private const val MAX_DEFAULT_NAMES = 99

        /** Keeps clear of MAX_PATH on systems without long paths enabled. */
        private const val MAX_PATH_CHARS = 240

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
