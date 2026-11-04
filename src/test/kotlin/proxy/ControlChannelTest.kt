package org.bittrace.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import org.bittrace.data.NameValuePair

class ControlChannelTest {

    @Test
    fun `commands are framed the way the reader reads frames`() {
        val out = ByteArrayOutputStream()
        val channel = ControlChannel(out)
        channel.send(Commands.RULE_CHANGED, """{"id":"r-1"}""")
        channel.send(Commands.BREAKPOINT_RESUME, """{"id":"f","side":"request","action":"edit"}""", byteArrayOf(0, 1, 2))
        channel.close()

        val frames = buildList { FrameReader.read(ByteArrayInputStream(out.toByteArray())) { add(it) } }
        assertEquals(listOf(Commands.RULE_CHANGED, Commands.BREAKPOINT_RESUME), frames.map { it.tag })
        assertEquals("""{"id":"r-1"}""", String(frames[0].json))
        assertContentEquals(byteArrayOf(0, 1, 2), frames[1].body)
    }

    @Test
    fun `a dead pipe closes the channel instead of throwing at the caller`() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("pipe closed")
        }
        val logs = mutableListOf<LogEntry>()
        val channel = ControlChannel(broken) { synchronized(logs) { logs += it } }
        channel.send(Commands.RULES_RELOAD, "{}")
        channel.send(Commands.RULES_RELOAD, "{}")
        channel.close()
        assertFalse(channel.isOpen)
        assertEquals(1, logs.size)
    }

    @Test
    fun `a resume leaves out what was not edited`() {
        val json = Json { encodeDefaults = false; explicitNulls = false }
        val answer = BreakpointResume(
            "f", "response", "edit",
            response = BreakpointResume.ResponseEdit(status = 418, headers = listOf(NameValuePair("A", "1"))),
        )
        assertEquals(
            """{"id":"f","side":"response","action":"edit","response":{"status":418,"headers":[{"name":"A","value":"1"}]}}""",
            json.encodeToString(BreakpointResume.serializer(), answer),
        )
    }
}
