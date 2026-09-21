package org.bittrace.api

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The walk, and the two rules it is easy to get half-right.
 *
 * The dot-file rule was written into the legacy-layout check and left out of
 * the walk that builds a collection's rows, which is exactly the kind of drift
 * a second copy of a predicate produces: a project's own
 * `.bittrace-variables.yaml` rendered as a request one level down.
 */
class CollectionStoreTest {

    private val root: Path = createTempDirectory("bittrace-collections")

    @AfterTest
    fun cleanUp() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun project(name: String): Path = Files.createDirectories(root.resolve(name))

    private fun collection(project: Path, name: String): Path =
        Files.createDirectories(project.resolve(name))

    private fun request(collection: Path, file: String): Path =
        Files.writeString(collection.resolve(file), "method: GET\nurl: https://example.test\n")

    private fun load(): CollectionStore = CollectionStore(root).also { it.reload() }

    /**
     * The scaffold is laid when the collections folder does not exist yet, and
     * only then — an emptied folder belongs to whoever emptied it.
     */
    @Test
    fun `a collections folder that does not exist yet is seeded with a scratch`() {
        val fresh = root.resolve("fresh")
        val store = CollectionStore(fresh).also { it.reload() }

        val project = store.tree.single()
        assertEquals("Scratches", project.name)
        val collection = project.children.single()
        assertEquals("Scratches", collection.name)
        assertEquals(listOf("Scratch request"), collection.children.map { it.name })
    }

    @Test
    fun `an emptied collections folder is not seeded again`() {
        val store = load()
        assertTrue(store.tree.isEmpty())
        assertEquals(emptyList(), load().tree.map { it.name })
    }

    @Test
    fun `a dot-prefixed yaml inside a collection is not a request`() {
        val auth = collection(project("Acme"), "Auth")
        request(auth, "Login.yaml")
        request(auth, ".bittrace-variables.yaml")

        val requests = load().tree.single().children.single().children
        assertEquals(listOf("Login"), requests.map { it.name })
    }

    @Test
    fun `a project's variables file is not taken for a collection or a request`() {
        val acme = project("Acme")
        request(acme, ".bittrace-variables.yaml")
        request(collection(acme, "Auth"), "Login.yaml")

        val tree = load().tree
        assertEquals(listOf("Acme"), tree.map { it.name })
        assertEquals(listOf("Auth"), tree.single().children.map { it.name })
    }

    @Test
    fun `walk reaches every node, variables included`() {
        val acme = project("Acme")
        request(collection(acme, "Auth"), "Login.yaml")

        val names = load().tree.walk().map { it.name }.toList()
        assertEquals(listOf("Acme", "Variables", "Auth", "Login"), names)
    }

    @Test
    fun `walk finds a request by its path`() {
        val login = request(collection(project("Acme"), "Auth"), "Login.yaml")

        val found = load().tree.walk().filterIsInstance<RequestNode>().firstOrNull { it.path == login }
        assertTrue(found != null, "the request the reconcile pass looks up must be reachable")
        assertEquals("Login", found.name)
    }
}
