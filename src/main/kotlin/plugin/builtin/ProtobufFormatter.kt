package org.bittrace.plugin.builtin

import org.bittrace.plugin.format.BodyFormatter
import org.bittrace.plugin.format.FormattedBody
import org.bittrace.plugin.format.Span
import org.bittrace.plugin.format.TokenKind
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Protocol Buffers without a schema, and the gRPC framing around them.
 *
 * With no `.proto` to hand, what is on the wire is field numbers and wire
 * types, so that is what this shows: `3: "alice"`, `7 { 1: 42 }`. A
 * length-delimited field is the one ambiguity — a string, raw bytes and a
 * nested message are the same bytes on the wire — and it is settled the way
 * `protoc --decode_raw` settles it: a nested message if the bytes parse as one
 * *exactly*, text if they are printable, hex otherwise.
 *
 * gRPC wraps each message in a five-byte prefix (a flags byte, then a
 * big-endian length); gRPC-web adds a trailer frame with the top flag bit set,
 * carrying the status as HTTP-style header lines. A compressed frame is tried
 * as gzip, the codec nearly every server uses — the formatter is given the
 * bytes and the content type, not the `grpc-encoding` header, so it cannot
 * know for certain.
 *
 * Spans are recorded as the text is written rather than by lexing the result,
 * since the output has no grammar worth a lexer.
 */
class ProtobufFormatter : BodyFormatter {
    override val id = "bittrace.protobuf"
    override val name = "Protobuf"

    override fun handles(mimeType: String): Boolean {
        val m = mimeType.lowercase()
        return "grpc" in m || "protobuf" in m
    }

    override fun format(bytes: ByteArray, mimeType: String): String = highlight(bytes, mimeType).text

    override fun highlight(bytes: ByteArray, mimeType: String): FormattedBody {
        val out = Out()
        if (bytes.isEmpty()) return FormattedBody("")
        if ("grpc" in mimeType.lowercase()) grpc(bytes, out) else message(bytes, 0, bytes.size, 0, out)
        return out.build()
    }

    private fun grpc(bytes: ByteArray, out: Out) {
        var pos = 0
        var index = 1
        while (pos < bytes.size) {
            if (out.full) return
            if (bytes.size - pos < 5) {
                out.meta("# ${bytes.size - pos} trailing bytes, too short for a gRPC frame\n")
                hexBlock(bytes, pos, bytes.size, 0, out)
                return
            }
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toLong() and 0xFF) shl 24) or ((bytes[pos + 2].toLong() and 0xFF) shl 16) or
                ((bytes[pos + 3].toLong() and 0xFF) shl 8) or (bytes[pos + 4].toLong() and 0xFF)
            val start = pos + 5
            if (length > bytes.size - start) {
                out.meta("# frame $index claims $length bytes, ${bytes.size - start} remain — not gRPC framing?\n")
                hexBlock(bytes, pos, bytes.size, 0, out)
                return
            }
            val end = start + length.toInt()
            if (index > 1) out.plain("\n")
            when {
                flags and 0x80 != 0 -> {
                    out.meta("# trailers ($length bytes)\n")
                    out.string(String(bytes, start, end - start, Charsets.UTF_8).trimEnd() + "\n")
                }
                flags and 0x01 != 0 -> {
                    val inflated = runCatching {
                        GZIPInputStream(ByteArrayInputStream(bytes, start, end - start)).use { it.readBytes() }
                    }.getOrNull()
                    if (inflated == null) {
                        out.meta("# message $index ($length bytes, compressed with a codec other than gzip)\n")
                        hexBlock(bytes, start, end, 0, out)
                    } else {
                        out.meta("# message $index ($length bytes, gzip → ${inflated.size})\n")
                        message(inflated, 0, inflated.size, 0, out)
                    }
                }
                else -> {
                    out.meta("# message $index ($length bytes)\n")
                    message(bytes, start, end, 0, out)
                }
            }
            pos = end
            index++
        }
    }

    /** Writes the fields in `[from, to)`, assuming they form a valid message. */
    private fun message(bytes: ByteArray, from: Int, to: Int, depth: Int, out: Out) {
        val fields = parse(bytes, from, to)
        if (fields == null) {
            out.meta("${indent(depth)}# not a valid protobuf message\n")
            hexBlock(bytes, from, to, depth, out)
            return
        }
        for (field in fields) {
            if (out.full) return
            out.plain(indent(depth))
            out.property(field.number.toString())
            when (field) {
                is Field.Varint -> {
                    out.punct(": ")
                    out.number(java.lang.Long.toUnsignedString(field.value))
                    // Only a negative int64 reaches the top bit; showing a
                    // zigzag reading of every odd number would be noise.
                    if (field.value < 0) out.comment("  # int64 ${field.value}")
                    out.plain("\n")
                }
                is Field.Fixed32 -> {
                    out.punct(": ")
                    out.number(Integer.toUnsignedString(field.value))
                    out.comment("  # fixed32 · float ${Float.fromBits(field.value)}")
                    out.plain("\n")
                }
                is Field.Fixed64 -> {
                    out.punct(": ")
                    out.number(java.lang.Long.toUnsignedString(field.value))
                    out.comment("  # fixed64 · double ${Double.fromBits(field.value)}")
                    out.plain("\n")
                }
                is Field.Bytes -> lengthDelimited(bytes, field, depth, out)
            }
        }
    }

    private fun lengthDelimited(bytes: ByteArray, field: Field.Bytes, depth: Int, out: Out) {
        val length = field.end - field.start
        val nested = if (depth < MAX_DEPTH && length > 0) parse(bytes, field.start, field.end) else null
        val text = printableText(bytes, field.start, field.end)
        when {
            // Text first when it is genuinely text: short ASCII strings often
            // happen to parse as a message too ("hi" is a valid field 13).
            text != null && (nested == null || looksLikeWords(text)) -> {
                out.punct(": ")
                out.string(quote(text))
                out.plain("\n")
            }
            nested != null -> {
                out.punct(" {\n")
                message(bytes, field.start, field.end, depth + 1, out)
                out.plain(indent(depth))
                out.punct("}\n")
            }
            length == 0 -> {
                out.punct(": ")
                out.string("\"\"")
                out.plain("\n")
            }
            else -> {
                out.punct(": ")
                out.meta("<$length bytes>\n")
                hexBlock(bytes, field.start, field.end, depth + 1, out)
            }
        }
    }

    private fun hexBlock(bytes: ByteArray, from: Int, to: Int, depth: Int, out: Out) {
        var pos = from
        while (pos < to && !out.full) {
            val end = minOf(pos + 16, to)
            out.plain(indent(depth))
            out.number((pos until end).joinToString(" ") { "%02x".format(bytes[it]) })
            out.plain("\n")
            pos = end
        }
    }

    private sealed class Field(val number: Long) {
        class Varint(number: Long, val value: Long) : Field(number)
        class Fixed32(number: Long, val value: Int) : Field(number)
        class Fixed64(number: Long, val value: Long) : Field(number)
        class Bytes(number: Long, val start: Int, val end: Int) : Field(number)
    }

    /**
     * The fields in `[from, to)`, or null unless the range parses as a message
     * and is consumed exactly. Groups (wire types 3 and 4) are deprecated and
     * treated as invalid, which errs toward showing bytes rather than inventing
     * structure.
     */
    private fun parse(bytes: ByteArray, from: Int, to: Int): List<Field>? {
        val fields = mutableListOf<Field>()
        var pos = from
        while (pos < to) {
            val (tag, afterTag) = varint(bytes, pos, to) ?: return null
            pos = afterTag
            val number = tag ushr 3
            if (number == 0L || number > MAX_FIELD) return null
            when ((tag and 7).toInt()) {
                0 -> {
                    val (value, next) = varint(bytes, pos, to) ?: return null
                    fields += Field.Varint(number, value)
                    pos = next
                }
                1 -> {
                    if (to - pos < 8) return null
                    var v = 0L
                    for (i in 7 downTo 0) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
                    fields += Field.Fixed64(number, v)
                    pos += 8
                }
                2 -> {
                    val (length, next) = varint(bytes, pos, to) ?: return null
                    if (length < 0 || length > to - next) return null
                    fields += Field.Bytes(number, next, next + length.toInt())
                    pos = next + length.toInt()
                }
                5 -> {
                    if (to - pos < 4) return null
                    var v = 0
                    for (i in 3 downTo 0) v = (v shl 8) or (bytes[pos + i].toInt() and 0xFF)
                    fields += Field.Fixed32(number, v)
                    pos += 4
                }
                else -> return null
            }
        }
        return fields
    }

    private fun varint(bytes: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = from
        while (pos < to && shift < 64) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }

    /** The range as UTF-8, if it decodes and has no control characters but whitespace. */
    private fun printableText(bytes: ByteArray, from: Int, to: Int): String? {
        if (from == to) return null
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, from, to - from)).toString()
        }.getOrNull() ?: return null
        return text.takeIf { s -> s.all { it >= ' ' || it == '\n' || it == '\r' || it == '\t' } }
    }

    /** Text with a space or a run of letters, rather than a few stray printable bytes. */
    private fun looksLikeWords(text: String): Boolean =
        text.count { it.isLetterOrDigit() || it == ' ' } * 10 >= text.length * 8 && text.length >= 2

    private fun quote(text: String) = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun indent(depth: Int) = "  ".repeat(depth)

    /** Output with spans recorded as it is written, and a ceiling on its size. */
    private class Out {
        private val sb = StringBuilder()
        private val spans = mutableListOf<Span>()
        private var truncated = false

        val full: Boolean get() = sb.length >= MAX_OUTPUT

        private fun add(text: String, kind: TokenKind?) {
            if (full) { truncated = true; return }
            val start = sb.length
            sb.append(text)
            if (kind != null) {
                // A trailing newline belongs to no token.
                val end = if (text.endsWith('\n')) sb.length - 1 else sb.length
                if (end > start) spans += Span(start, end, kind)
            }
        }

        fun plain(text: String) = add(text, null)
        fun property(text: String) = add(text, TokenKind.PROPERTY)
        fun number(text: String) = add(text, TokenKind.NUMBER)
        fun string(text: String) = add(text, TokenKind.STRING)
        fun punct(text: String) = add(text, TokenKind.PUNCTUATION)
        fun meta(text: String) = add(text, TokenKind.META)
        fun comment(text: String) = add(text, TokenKind.COMMENT)

        fun build(): FormattedBody {
            if (truncated || full) {
                val start = sb.length
                sb.append("\n# output truncated")
                spans += Span(start + 1, sb.length, TokenKind.META)
            }
            return FormattedBody(sb.toString().trimEnd('\n'), spans)
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_FIELD = (1L shl 29) - 1
        const val MAX_OUTPUT = 1_000_000
    }
}
