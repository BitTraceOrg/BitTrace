package org.bittrace.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for the git layer, against temp-directory repositories. No network.
 *
 * Several of these guard failures that do not throw. A filepattern with the
 * wrong separator commits nothing and reports success; a repository inheriting
 * `core.autocrlf` shows every file modified forever. Both are invisible to a
 * smoke test and both would be reported as "git support is broken" with no
 * further detail, so they are asserted directly.
 */
class GitServiceTest {

    private val temp: Path = Files.createTempDirectory("bittrace-git")
    private val credentials = GitCredentials { "" }
    private val git = GitService({ GitIdentity("Test", "test@example.com") }, credentials)

    @AfterTest
    fun cleanUp() {
        git.close()
        runCatching {
            Files.walk(temp).sorted(Comparator.reverseOrder()).forEach { path ->
                path.toFile().setWritable(true)
                Files.deleteIfExists(path)
            }
        }
    }

    /** A newline, as a value, so fixtures do not repeat an escape. */
    private val LF = "\n"

    private fun project(name: String = "Payments"): Path =
        temp.resolve(name).also { it.resolve("Auth").createDirectories() }

    private fun write(project: Path, name: String, text: String): Path {
        val file = project.resolve("Auth").resolve(name)
        Files.writeString(file, text)
        return file
    }

    // --- init ---------------------------------------------------------------

    @Test
    fun `init produces a repo with a first commit and both control files`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: POST\n")

        git.init(dir).getOrThrow()

        assertTrue(Files.isDirectory(dir.resolve(".git")))
        assertTrue(Files.exists(dir.resolve(".gitignore")))
        assertTrue(Files.exists(dir.resolve(".gitattributes")))
        // Not optional: an unborn branch puts branches, log, status and
        // ahead/behind each on a second code path.
        assertEquals(1, git.log(dir).getOrThrow().size)
        assertEquals("main", git.state(dir).getOrThrow().branch)
    }

    @Test
    fun `a fresh repo is clean, and stays clean whatever autocrlf says globally`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: POST\nurl: https://example.com\n")

        git.init(dir).getOrThrow()

        // With core.autocrlf inherited as true, git checks out CRLF while the
        // collection store writes LF — every request then reads as modified,
        // permanently, and the dirty count never returns to zero.
        assertEquals("false", readConfig(dir, "autocrlf"))
        assertTrue(git.state(dir).getOrThrow().clean, "a just-initialised project must be clean")
    }

    @Test
    fun `the atomic-save temp files are ignored`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: GET" + LF)
        git.init(dir).getOrThrow()

        Files.writeString(dir.resolve("Auth").resolve("Login.yaml.tmp"), "half written")

        assertTrue(git.state(dir).getOrThrow().clean, "a temp file mid-save must not show as a change")
    }

    @Test
    fun `project variables are tracked, not ignored`() = runBlocking {
        // Deliberate, and the opposite of what the credentials sidecar did.
        // Variables are project content: a colleague who clones this repository
        // should get the names and values the requests refer to, or every
        // `{{host}}` in it resolves to nothing on their machine.
        val dir = project()
        write(dir, "Login.yaml", "method: GET" + LF)
        git.init(dir).getOrThrow()

        Files.writeString(
            dir.resolve(org.bittrace.api.ProjectVariables.FILE_NAME),
            "variables:" + LF + "  - name: host" + LF + "    value: example.com" + LF + "    enabled: true" + LF,
        )

        assertFalse(git.state(dir).getOrThrow().clean, "the variables file should be a pending change")
    }

    @Test
    fun `init on an existing repo does nothing rather than failing`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: GET\n")
        git.init(dir).getOrThrow()

        git.init(dir).getOrThrow()

        assertEquals(1, git.log(dir).getOrThrow().size)
    }

    @Test
    fun `every call on a folder that is not a repo reports it as such`() = runBlocking {
        val dir = project()

        val failure = git.state(dir).exceptionOrNull()

        assertTrue(failure is GitFailure.NotARepo, "got ${failure?.let { it::class.simpleName }}")
    }

    // --- commit -------------------------------------------------------------

    @Test
    fun `committing a nested file actually commits it`() = runBlocking {
        // The separator bug: JGit filepatterns are POSIX whatever the platform,
        // and `relativize().toString()` gives backslashes on Windows. The commit
        // then succeeds having staged nothing at all — no exception, no files.
        val dir = project()
        git.init(dir).getOrThrow()
        val file = write(dir, "Login.yaml", "method: POST\n")

        git.commit(dir, listOf(file), "Add login").getOrThrow()

        assertTrue(git.state(dir).getOrThrow().clean, "the file is still uncommitted")
        assertEquals(2, git.log(dir).getOrThrow().size)
        assertEquals(1, git.log(dir, file).getOrThrow().size)
    }

    @Test
    fun `only the selected files go in`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()
        val login = write(dir, "Login.yaml", "method: POST\n")
        write(dir, "Logout.yaml", "method: POST\n")

        git.commit(dir, listOf(login), "Add login").getOrThrow()

        val state = git.state(dir).getOrThrow()
        assertEquals(1, state.dirty, "the unticked file should still be pending")
        assertFalse(state.clean)
    }

    @Test
    fun `a deleted file can be committed`() = runBlocking {
        val dir = project()
        val file = write(dir, "Login.yaml", "method: POST\n")
        git.init(dir).getOrThrow()
        Files.delete(file)

        git.commit(dir, listOf(file), "Remove login").getOrThrow()

        assertTrue(git.state(dir).getOrThrow().clean)
    }

    @Test
    fun `committing without an identity says so instead of inventing one`() = runBlocking {
        val anonymous = GitService({ GitIdentity("", "") }, GitCredentials { "" })
        val dir = project()
        anonymous.init(dir).getOrThrow()
        val file = write(dir, "Login.yaml", "method: GET\n")

        val failure = anonymous.commit(dir, listOf(file), "x").exceptionOrNull()

        assertTrue(failure is GitFailure.NoIdentity, "got ${failure?.let { it::class.simpleName }}")
        anonymous.close()
    }

    // --- branches -----------------------------------------------------------

    @Test
    fun `a branch can be made, switched to, and switched back`() = runBlocking {
        val dir = project()
        val file = write(dir, "Login.yaml", "method: GET\n")
        git.init(dir).getOrThrow()

        git.createBranch(dir, "experiment").getOrThrow()
        Files.writeString(file, "method: POST\n")
        git.commit(dir, listOf(file), "Try POST").getOrThrow()
        git.checkout(dir, "main").getOrThrow()

        assertEquals("main", git.state(dir).getOrThrow().branch)
        // The working tree really reverts, which is what makes the tab reload
        // after a checkout necessary rather than decorative.
        assertEquals("method: GET\n", Files.readString(file))
    }

    @Test
    fun `branches lists what exists, marking the current one`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: GET\n")
        git.init(dir).getOrThrow()
        git.createBranch(dir, "experiment").getOrThrow()

        val branches = git.branches(dir).getOrThrow()

        assertEquals(setOf("main", "experiment"), branches.map { it.name }.toSet())
        assertEquals("experiment", branches.single { it.current }.name)
    }

    // --- history ------------------------------------------------------------

    @Test
    fun `a file's log holds only the commits that touched it`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()
        val login = write(dir, "Login.yaml", "one\n")
        git.commit(dir, listOf(login), "Add login").getOrThrow()
        val other = write(dir, "Logout.yaml", "one\n")
        git.commit(dir, listOf(other), "Add logout").getOrThrow()
        Files.writeString(login, "two\n")
        git.commit(dir, listOf(login), "Change login").getOrThrow()

        val log = git.log(dir, login).getOrThrow()

        assertEquals(listOf("Change login", "Add login"), log.map { it.subject })
    }

    @Test
    fun `history survives a rename`() = runBlocking {
        // Renaming a request in the tree is a plain Files.move, so without
        // rename-following the History tab would go blank the moment anybody
        // renamed anything.
        val dir = project()
        git.init(dir).getOrThrow()
        val login = write(dir, "Login.yaml", "one\n")
        git.commit(dir, listOf(login), "Add login").getOrThrow()

        val renamed = dir.resolve("Auth").resolve("SignIn.yaml")
        Files.move(login, renamed)
        git.commit(dir, listOf(login, renamed), "Rename to SignIn").getOrThrow()

        val log = git.log(dir, renamed).getOrThrow()
        assertEquals(listOf("Rename to SignIn", "Add login"), log.map { it.subject })
    }

    @Test
    fun `contentAt returns the old text, and null before the file existed`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()
        val first = git.log(dir).getOrThrow().single().id
        val login = write(dir, "Login.yaml", "original\n")
        git.commit(dir, listOf(login), "Add login").getOrThrow()
        Files.writeString(login, "changed\n")
        val second = git.commit(dir, listOf(login), "Change login").getOrThrow()

        assertEquals("changed\n", git.contentAt(dir, login, second.id).getOrThrow())
        assertNull(git.contentAt(dir, login, first).getOrThrow(), "it did not exist yet")

        val parent = git.parentOf(dir, second.id).getOrThrow()
        assertNotNull(parent)
        assertEquals("original\n", git.contentAt(dir, login, parent).getOrThrow())
    }

    @Test
    fun `the root commit has no parent`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()

        assertNull(git.parentOf(dir, git.log(dir).getOrThrow().single().id).getOrThrow())
    }

    // --- remotes ------------------------------------------------------------

    @Test
    fun `a project with no remote says so rather than reaching the network`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: GET\n")
        git.init(dir).getOrThrow()

        assertFalse(git.state(dir).getOrThrow().hasRemote)
        assertTrue(git.fetch(dir).exceptionOrNull() is GitFailure.NoRemote)
        assertTrue(git.push(dir).exceptionOrNull() is GitFailure.NoRemote)
    }

    @Test
    fun `ahead and behind read as unknown without an upstream`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: GET\n")
        git.init(dir).getOrThrow()

        val state = git.state(dir).getOrThrow()
        assertEquals(-1, state.ahead, "no upstream is not the same as zero ahead")
        assertEquals(-1, state.behind)
        assertNull(state.upstream)
    }

    // --- host conventions ---------------------------------------------------

    @Test
    fun `the host is read from both URL shapes`() {
        assertEquals("github.com", GitCredentials.hostOf("https://github.com/erli/thing.git"))
        assertEquals("github.com", GitCredentials.hostOf("git@github.com:erli/thing.git"))
        assertEquals("", GitCredentials.hostOf("nonsense"))
    }

    @Test
    fun `the switchable list is locals sorted, with remotes only where no local exists`() = runBlocking {
        val dir = project()
        write(dir, "Login.yaml", "method: GET" + LF)
        git.init(dir).getOrThrow()
        git.createBranch(dir, "experiment", checkout = false).getOrThrow()
        val bare = temp.resolve("origin.git")
        org.eclipse.jgit.api.Git.init()
            .setBare(true)
            .setInitialBranch("main")
            .setDirectory(bare.toFile())
            .call()
            .close()
        git.addRemote(dir, bare.toUri().toString()).getOrThrow()
        git.push(dir, setUpstream = true).getOrThrow()
        git.fetch(dir).getOrThrow()

        val branches = git.state(dir).getOrThrow().branches

        // `origin/main` is deliberately absent: a remote branch you already have
        // locally is the same branch, and offering both would make picking one
        // of them a coin toss with different consequences.
        assertEquals(listOf("experiment", "main"), branches)
    }

    // --- publishing ---------------------------------------------------------

    @Test
    fun `publishing a local project pushes it and records the upstream`() = runBlocking {
        // A bare repo on disk stands in for a remote: the transport is different
        // but everything this code does — refspec, status checks, config — is
        // the same, and it runs without a network.
        val dir = project()
        write(dir, "Login.yaml", "method: GET\n")
        git.init(dir).getOrThrow()
        val bare = temp.resolve("origin.git")
        org.eclipse.jgit.api.Git.init()
            .setBare(true)
            .setInitialBranch("main")
            .setDirectory(bare.toFile())
            .call()
            .close()

        git.addRemote(dir, bare.toUri().toString()).getOrThrow()
        git.push(dir, setUpstream = true).getOrThrow()

        val state = git.state(dir).getOrThrow()
        assertTrue(state.hasRemote)
        // Without the two config keys written by hand — PushCommand has no
        // setUpstream — the branch reads as untracked forever, and Pull refuses
        // immediately after a successful publish.
        assertEquals("origin/main", state.upstream)
        assertEquals(0, state.ahead)
        assertEquals(0, state.behind)
    }

    @Test
    fun `a second commit after publishing shows as ahead`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()
        val bare = temp.resolve("origin.git")
        org.eclipse.jgit.api.Git.init()
            .setBare(true)
            .setInitialBranch("main")
            .setDirectory(bare.toFile())
            .call()
            .close()
        git.addRemote(dir, bare.toUri().toString()).getOrThrow()
        git.push(dir, setUpstream = true).getOrThrow()

        val file = write(dir, "Login.yaml", "method: GET\n")
        git.commit(dir, listOf(file), "Add login").getOrThrow()

        assertEquals(1, git.state(dir).getOrThrow().ahead)
    }

    @Test
    fun `a remote can only be added once`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()
        git.addRemote(dir, "https://example.com/thing.git").getOrThrow()

        val failure = git.addRemote(dir, "https://example.com/other.git").exceptionOrNull()

        assertTrue(failure is GitFailure.Broken, "adding twice should be refused")
    }

    @Test
    fun `removing a remote puts the project back where it started`() = runBlocking {
        val dir = project()
        git.init(dir).getOrThrow()
        git.addRemote(dir, "https://example.com/thing.git").getOrThrow()

        git.removeRemote(dir).getOrThrow()

        // The rollback path after a failed publish: without it, a mistyped URL
        // leaves a project that can never be published again from the menu.
        assertFalse(git.state(dir).getOrThrow().hasRemote)
    }

    @Test
    fun `a push the remote refuses is reported as a failure`() = runBlocking {
        // JGit does not throw for a rejected update — it comes back as an
        // ordinary result carrying a status, so an unchecked push reports
        // success for something the remote threw out.
        val dir = project()
        git.init(dir).getOrThrow()
        val bare = temp.resolve("origin.git")
        org.eclipse.jgit.api.Git.init()
            .setBare(true)
            .setInitialBranch("main")
            .setDirectory(bare.toFile())
            .call()
            .close()
        git.addRemote(dir, bare.toUri().toString()).getOrThrow()
        git.push(dir, setUpstream = true).getOrThrow()

        // Move the remote on independently, then rewrite history locally so the
        // push is a non-fast-forward.
        // A bare temp path: cloning refuses a directory that already has
        // content, and `project()` creates the collection folders.
        val other = temp.resolve("clone")
        org.eclipse.jgit.api.Git.cloneRepository()
            .setURI(bare.toUri().toString())
            .setDirectory(other.toFile())
            .call().use { clone ->
                Files.writeString(other.resolve("remote-only.txt"), "x")
                clone.add().addFilepattern(".").call()
                clone.commit().setMessage("From elsewhere")
                    .setAuthor("Other", "other@example.com")
                    .setCommitter("Other", "other@example.com").call()
                clone.push().call()
            }
        val file = write(dir, "Diverge.yaml", "method: GET\n")
        git.commit(dir, listOf(file), "Local only").getOrThrow()

        val failure = git.push(dir).exceptionOrNull()

        assertTrue(failure is GitFailure.Broken, "a rejected push must not report success")
        assertTrue(
            failure?.message?.contains("refused") == true,
            "message should say what happened: ${failure?.message}",
        )
    }

    private fun readConfig(project: Path, key: String): String? =
        Files.readAllLines(project.resolve(".git").resolve("config"))
            .firstOrNull { it.trim().startsWith("$key ") }
            ?.substringAfter('=')
            ?.trim()
}
