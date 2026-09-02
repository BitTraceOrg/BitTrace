package org.bittrace.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Serializers that pool their strings through [TrafficStrings] as they decode.
 *
 * Interning at decode time rather than afterwards means the store never holds a
 * per-flow copy of a repeated header — the shared instance is installed at the
 * moment the object is constructed, so no rewriting pass over the message is
 * needed. Encoding is plain: the pool is a read-side concern.
 */

/** A `String` field whose values repeat across flows (mime types, and friends). */
object InternedStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.bittrace.InternedString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = TrafficStrings.intern(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/**
 * Decodes a header/query pair straight into its pooled form. Written by hand
 * rather than via a surrogate because this is the hot path — a flow carries
 * dozens of these, and the surrogate would allocate one object per pair only to
 * throw it away.
 */
object NameValuePairSerializer : KSerializer<NameValuePair> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("org.bittrace.NameValuePair") {
        element<String>("name")
        element<String>("value")
    }

    override fun deserialize(decoder: Decoder): NameValuePair = decoder.decodeStructure(descriptor) {
        var name = ""
        var value = ""
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> name = decodeStringElement(descriptor, 0)
                1 -> value = decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                // Unknown keys are dropped by the format (ignoreUnknownKeys).
                else -> throw SerializationException("unexpected index $index for NameValuePair")
            }
        }
        TrafficStrings.pair(name, value)
    }

    override fun serialize(encoder: Encoder, value: NameValuePair) = encoder.encodeStructure(descriptor) {
        encodeStringElement(descriptor, 0, value.name)
        encodeStringElement(descriptor, 1, value.value)
    }
}

/**
 * Cookies pool their name, path and domain — those repeat for every flow to a
 * host — and leave the value alone, since that is the unique part. Uses the
 * surrogate pattern: cookies are few per flow, so the generated decoder's
 * handling of seven optional fields is worth one transient object.
 */
object HarCookieSerializer : KSerializer<HarCookie> {

    @Serializable
    @SerialName("org.bittrace.HarCookie")
    private data class Surrogate(
        val name: String,
        val value: String,
        val path: String? = null,
        val domain: String? = null,
        val expires: String? = null,
        val httpOnly: Boolean? = null,
        val secure: Boolean? = null,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): HarCookie {
        val s = Surrogate.serializer().deserialize(decoder)
        return HarCookie(
            name = TrafficStrings.intern(s.name),
            value = s.value,
            path = s.path?.let(TrafficStrings::intern),
            domain = s.domain?.let(TrafficStrings::intern),
            expires = s.expires,
            httpOnly = s.httpOnly,
            secure = s.secure,
        )
    }

    override fun serialize(encoder: Encoder, value: HarCookie) = Surrogate.serializer().serialize(
        encoder,
        Surrogate(value.name, value.value, value.path, value.domain, value.expires, value.httpOnly, value.secure),
    )
}
