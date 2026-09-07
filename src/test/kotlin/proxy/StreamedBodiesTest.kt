package org.bittrace.proxy

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reassembly of bodies that arrive as `BodyChunk` frames.
 *
 * These cover the two things the inline path never had to do: joining chunks
 * back together, and undoing the `Content-Encoding` that mitmproxy leaves on a
 * streamed body but strips from an inline one.
 */
class StreamedBodiesTest {

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun end(
        id: String = "flow",
        side: String = "response",
        size: Long = 0,
        encoding: String = "",
        truncated: Boolean = false,
        dropped: Long = 0,
        aborted: Boolean = false,
    ) = BodyEndMessage(
        id = id,
        side = side,
        size = size,
        captured = size,
        dropped = dropped,
        truncated = truncated,
        aborted = aborted,
        contentEncoding = encoding,
    )

    @Test
    fun `joins chunks in arrival order`() {
        val bodies = StreamedBodies()
        bodies.chunk(BodyChunkMessage("flow", "response", 0), "hello ".toByteArray())
        bodies.chunk(BodyChunkMessage("flow", "response", 1), "world".toByteArray())

        assertContentEquals("hello world".toByteArray(), bodies.end(end(size = 11)))
    }

    @Test
    fun `keeps the two sides of one flow apart`() {
        val bodies = StreamedBodies()
        bodies.chunk(BodyChunkMessage("flow", "request", 0), "up".toByteArray())
        bodies.chunk(BodyChunkMessage("flow", "response", 0), "down".toByteArray())

        assertContentEquals("up".toByteArray(), bodies.end(end(side = "request", size = 2)))
        assertContentEquals("down".toByteArray(), bodies.end(end(size = 4)))
    }

    @Test
    fun `decodes a gzipped body`() {
        val plain = "the quick brown fox".repeat(50).toByteArray()
        val bodies = StreamedBodies()
        gzip(plain).let { bodies.chunk(BodyChunkMessage("flow", "response", 0), it) }

        assertContentEquals(plain, bodies.end(end(encoding = "gzip", size = plain.size.toLong())))
    }

    @Test
    fun `keeps what inflated from a body cut short`() {
        // Varied enough that half the compressed stream is still real deflate
        // data — one line repeated gzips down to a few dozen bytes, most of
        // which is header.
        val plain = (0 until 20_000).joinToString("\n") { "row $it holds value ${it * 7919}" }.toByteArray()
        val cut = gzip(plain).let { it.copyOf(it.size / 2) }
        val bodies = StreamedBodies()
        bodies.chunk(BodyChunkMessage("flow", "response", 0), cut)

        val decoded = bodies.end(end(encoding = "gzip", size = plain.size.toLong(), truncated = true, dropped = 3))

        assertTrue(decoded != null && decoded.isNotEmpty())
        assertTrue(decoded.size < plain.size, "a truncated body cannot inflate whole")
        assertContentEquals(decoded, plain.copyOf(decoded.size))
    }

    @Test
    fun `leaves a body encoded when there is no decoder for it`() {
        val raw = byteArrayOf(0x1B, 0x2C, 0x3D)
        val bodies = StreamedBodies()
        bodies.chunk(BodyChunkMessage("flow", "response", 0), raw)

        assertContentEquals(raw, bodies.end(end(encoding = "br", size = 3)))
    }

    @Test
    fun `stops at the limit and reports the loss`() {
        val logs = mutableListOf<LogEntry>()
        val bodies = StreamedBodies(limit = 8, onLog = { logs += it })
        bodies.chunk(BodyChunkMessage("flow", "response", 0), "123456".toByteArray())
        bodies.chunk(BodyChunkMessage("flow", "response", 1), "7890ab".toByteArray())

        assertContentEquals("12345678".toByteArray(), bodies.end(end(size = 12)))
        assertEquals(1, logs.size)
        assertEquals("warn", logs.single().level)
    }

    @Test
    fun `reports nothing for a body that never arrived`() {
        assertNull(StreamedBodies().end(end()))
    }

    @Test
    fun `clear drops what is still being assembled`() {
        val bodies = StreamedBodies()
        bodies.chunk(BodyChunkMessage("flow", "response", 0), "partial".toByteArray())
        bodies.clear()

        assertNull(bodies.end(end(size = 7)))
    }
}
