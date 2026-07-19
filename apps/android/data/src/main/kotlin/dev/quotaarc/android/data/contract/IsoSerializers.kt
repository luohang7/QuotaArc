package dev.quotaarc.android.data.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

object IsoInstantSerializer : KSerializer<Instant> {
    private val exactTimestampPattern = Regex(
        """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$""",
    )

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("QuotaArcIsoInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val value = decoder.decodeString()
        if (!exactTimestampPattern.matches(value)) {
            throw IllegalArgumentException("Expected an exact ISO-8601 timestamp with timezone")
        }
        try {
            return OffsetDateTime.parse(value).toInstant()
        } catch (error: DateTimeException) {
            throw IllegalArgumentException(
                "Expected an ISO-8601 timestamp with timezone",
                error,
            )
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }
}

object IsoLocalDateSerializer : KSerializer<LocalDate> {
    private val exactDatePattern = Regex("""^\d{4}-\d{2}-\d{2}$""")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("QuotaArcIsoDate", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDate {
        val value = decoder.decodeString()
        if (!exactDatePattern.matches(value)) {
            throw IllegalArgumentException("Expected an exact ISO calendar date")
        }
        try {
            return LocalDate.parse(value)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("Expected an ISO calendar date", error)
        }
    }

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }
}
