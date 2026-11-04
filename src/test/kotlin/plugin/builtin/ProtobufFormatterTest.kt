package org.bittrace.plugin.builtin

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Schema-less decoding, built from hand-encoded messages so each wire type is
 * pinned on its own, and the gRPC framing around them.
 */
class ProtobufFormatterTest {

    private val pb = ProtobufFormatter()

    // --- a tiny encoder ------------------------------------------------------

    private class Msg {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(field: Int, wire: Int) = varint((field.toLong() shl 3) or wire.toLong())
        fun int(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun fixed32(field: Int, v: Int) = apply { tag(field, 5); repeat(4) { out.write((v ushr (it * 8)) and 0xFF) } }
        fun fixed64(field: Int, v: Long) = apply { tag(field, 1); repeat(8) { out.write(((v ushr (it * 8)) and 0xFF).toInt()) } }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(payload: ByteArray, flags: Int = 0) = ByteArrayOutputStream().apply {
        write(flags)
        val n = payload.size
        write(n ushr 24); write(n ushr 16); write(n ushr 8); write(n)
        write(payload)
    }.toByteArray()

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(b) } }.toByteArray()

    // --- tests ---------------------------------------------------------------

    @Test
    fun `claims protobuf and grpc types only`() {
        assertTrue(pb.handles("application/x-protobuf"))
        assertTrue(pb.handles("application/grpc+proto"))
        assertTrue(pb.handles("application/grpc-web+proto"))
        assertFalse(pb.handles("application/json"))
    }

    @Test
    fun `decodes scalars, strings and nested messages`() {
        val inner = Msg().int(1, 42).str(2, "nested value").build()
        val body = Msg()
            .int(1, 150)
            .str(2, "hello world")
            .bytes(3, inner)
            .fixed32(4, 1.5f.toBits())
            .fixed64(5, 2.25.toBits())
            .int(6, -1)
            .build()
        val out = pb.format(body, "application/x-protobuf")
        assertContains(out, "1: 150")
        assertContains(out, "2: \"hello world\"")
        assertContains(out, "3 {\n  1: 42\n  2: \"nested value\"\n}")
        assertContains(out, "float 1.5")
        assertContains(out, "double 2.25")
        assertContains(out, "# int64 -1")
    }

    @Test
    fun `binary bytes fall back to hex`() {
        val out = pb.format(Msg().bytes(1, byteArrayOf(0x00, 0xff.toByte(), 0x07)).build(), "application/protobuf")
        assertContains(out, "<3 bytes>")
        assertContains(out, "00 ff 07")
    }

    @Test
    fun `garbage does not throw`() {
        val out = pb.format(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte()), "application/x-protobuf")
        assertContains(out, "not a valid protobuf message")
        // Truncated length-delimited field.
        assertContains(pb.format(byteArrayOf(0x12, 0x10, 0x41), "application/x-protobuf"), "not a valid")
    }

    @Test
    fun `splits grpc frames, inflates gzip and reads grpc-web trailers`() {
        val a = Msg().str(1, "first message").build()
        val b = Msg().str(1, "second message").build()
        val trailers = "grpc-status: 0\r\ngrpc-message: OK\r\n".toByteArray()
        val body = frame(a) + frame(gzip(b), flags = 1) + frame(trailers, flags = 0x80)
        val out = pb.format(body, "application/grpc-web+proto")
        assertContains(out, "# message 1 (${a.size} bytes)")
        assertContains(out, "1: \"first message\"")
        assertContains(out, "gzip → ${b.size}")
        assertContains(out, "1: \"second message\"")
        assertContains(out, "# trailers")
        assertContains(out, "grpc-status: 0")
    }

    @Test
    fun `bad grpc framing is reported, not thrown`() {
        val out = pb.format(byteArrayOf(0, 0, 0, 0x10, 0), "application/grpc")
        assertContains(out, "not gRPC framing?")
    }

    @Test
    fun `spans stay inside the text and in order`() {
        val body = Msg().int(1, 7).str(2, "x y").build()
        val result = pb.highlight(body, "application/x-protobuf")
        var last = 0
        result.spans.forEach {
            assertTrue(it.start >= last && it.end <= result.text.length && it.start < it.end)
            last = it.end
        }
        assertEquals(false, result.spans.isEmpty())
    }
}
