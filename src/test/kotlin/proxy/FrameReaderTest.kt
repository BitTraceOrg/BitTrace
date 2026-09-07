package org.bittrace.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameReaderTest {

    /** Builds one frame's bytes, mirroring the sidecar's writer. */
    private fun frame(tag: Int, json: ByteArray, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FrameReader.MAGIC)
        out.write(tag)
        out.write(leBytes(json.size))
        out.write(leBytes(body.size))
        out.write(json)
        out.write(body)
        return out.toByteArray()
    }

    private fun leBytes(value: Int) = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun readAll(stream: ByteArray): List<Frame> =
        buildList { FrameReader.read(ByteArrayInputStream(stream)) { add(it) } }

    @Test
    fun `reads multiple frames including binary and empty body`() {
        val binary = byteArrayOf(0, 0xFF.toByte(), '\n'.code.toByte(), '\n'.code.toByte(), 1)
        val stream = ByteArrayOutputStream().apply {
            write("garbage\n".toByteArray()) // leading stray bytes exercise resync
            write(frame(Tags.PID, "1234".toByteArray(), ByteArray(0)))
            write(frame(Tags.COMPLETE_RESPONSE, """{"id":"abc"}""".toByteArray(), binary))
            write(frame(Tags.INITIAL_REQUEST, """{"id":"z"}""".toByteArray(), ByteArray(0)))
        }.toByteArray()

        val frames = readAll(stream)

        assertEquals(3, frames.size)
        assertEquals(Tags.PID, frames[0].tag)
        assertContentEquals("1234".toByteArray(), frames[0].json)
        assertTrue(frames[0].body.isEmpty())

        assertEquals(Tags.COMPLETE_RESPONSE, frames[1].tag)
        assertContentEquals("""{"id":"abc"}""".toByteArray(), frames[1].json)
        assertContentEquals(binary, frames[1].body) // exact bytes, incl. NULs/newlines

        assertEquals(Tags.INITIAL_REQUEST, frames[2].tag)
        assertTrue(frames[2].body.isEmpty())
    }

    @Test
    fun `resyncs after a corrupt frame instead of allocating an absurd segment`() {
        val bogusLength = ByteArrayOutputStream().apply {
            write(FrameReader.MAGIC)
            write(Tags.LOG)
            write(leBytes(Int.MAX_VALUE)) // json_len past MAX_SEGMENT_LEN
            write(leBytes(0))
        }.toByteArray()

        val stream = bogusLength + frame(Tags.INITIAL_REQUEST, """{"id":"after"}""".toByteArray(), ByteArray(0))
        val frames = readAll(stream)

        assertEquals(1, frames.size)
        assertEquals(Tags.INITIAL_REQUEST, frames[0].tag)
    }

    @Test
    fun `truncated frame ends the stream without emitting a partial frame`() {
        val full = frame(Tags.INITIAL_REQUEST, """{"id":"z"}""".toByteArray(), ByteArray(0))
        assertTrue(readAll(full.copyOf(full.size - 3)).isEmpty())
    }

    @Test
    fun `body cache round trips and separates sides`() {
        val cache = BodyCache()
        cache.put("flow1", BodySide.REQUEST, byteArrayOf(1, 2, 3))
        cache.put("flow1", BodySide.RESPONSE, byteArrayOf(4, 5, 6))

        assertContentEquals(byteArrayOf(1, 2, 3), cache.get("flow1", BodySide.REQUEST))
        assertContentEquals(byteArrayOf(4, 5, 6), cache.get("flow1", BodySide.RESPONSE))
        assertNull(cache.get("missing", BodySide.REQUEST))

        cache.clear()
        assertNull(cache.get("flow1", BodySide.REQUEST))
    }

    @Test
    fun `body cache evicts the oldest ids past its capacity`() {
        val cache = BodyCache(capacity = 2)
        listOf("a", "b", "c").forEach { cache.put(it, BodySide.RESPONSE, byteArrayOf(1)) }

        assertEquals(2, cache.size)
        assertNull(cache.get("a", BodySide.RESPONSE))
        assertContentEquals(byteArrayOf(1), cache.get("c", BodySide.RESPONSE))
    }
}
