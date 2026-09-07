package proxy

import org.bittrace.proxy.ProxyProcess
import org.bittrace.proxy.SidecarBinary
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which processes the orphan sweep is willing to kill.
 *
 * This is the half of the sweep worth pinning down: getting it wrong does not
 * fail a build, it kills something that belonged to somebody else. The other
 * half — "is the parent gone?" — needs real processes to exercise and is left
 * to the one smoke check at the bottom.
 */
class SidecarIdentityTest {

    private val tmp: String = System.getProperty("java.io.tmpdir")
    private val name: String = SidecarBinary.filename

    private fun inDir(vararg parts: String): String =
        Path.of(tmp, *parts).resolve(name).toString()

    @Test
    fun `a binary in an extraction directory is ours`() {
        assertTrue(SidecarBinary.isExtractedSidecar(inDir("bittrace-sidecar-44fce7c4974dfea7")))
    }

    @Test
    fun `any extraction directory counts, not just this build's`() {
        // A sidecar from an older build holds the listen port exactly as well as
        // a current one, so the hash is deliberately not checked.
        assertTrue(SidecarBinary.isExtractedSidecar(inDir("bittrace-sidecar-0000000000000000")))
        assertTrue(SidecarBinary.isExtractedSidecar(inDir("bittrace-sidecar-deadbeefdeadbeef")))
    }

    @Test
    fun `the same binary somewhere else is not ours`() {
        // Someone building MITMConnect themselves. Same name, not our business.
        assertFalse(SidecarBinary.isExtractedSidecar(Path.of(tmp, name).toString()))
        assertFalse(SidecarBinary.isExtractedSidecar(inDir("some-other-tool")))
        assertFalse(SidecarBinary.isExtractedSidecar(Path.of("C:", "dev", "MITMConnect", name).toString()))
    }

    @Test
    fun `a different binary in an extraction directory is not ours`() {
        assertFalse(
            SidecarBinary.isExtractedSidecar(
                Path.of(tmp, "bittrace-sidecar-44fce7c4974dfea7", "something-else.exe").toString()
            )
        )
    }

    @Test
    fun `unreadable and malformed commands are left alone`() {
        // `info().command()` is empty for another user's process.
        assertFalse(SidecarBinary.isExtractedSidecar(null))
        assertFalse(SidecarBinary.isExtractedSidecar(""))
    }

    @Test
    fun `the dev override directory counts as ours`() {
        val dir = Path.of(tmp, "hand-built-sidecar")
        val previous = System.getProperty("bittrace.sidecar.dir")
        System.setProperty("bittrace.sidecar.dir", dir.toString())
        try {
            assertTrue(SidecarBinary.isExtractedSidecar(dir.resolve(name).toString()))
            // Still only that directory.
            assertFalse(SidecarBinary.isExtractedSidecar(Path.of(tmp, "elsewhere", name).toString()))
        } finally {
            if (previous == null) System.clearProperty("bittrace.sidecar.dir")
            else System.setProperty("bittrace.sidecar.dir", previous)
        }
    }

    @Test
    fun `sweeping with no sidecars running kills nothing and does not throw`() {
        val logged = mutableListOf<String>()
        val killed = ProxyProcess.killOrphans { logged += it.message }
        assertEquals(0, killed, "killed something: $logged")
    }
}
