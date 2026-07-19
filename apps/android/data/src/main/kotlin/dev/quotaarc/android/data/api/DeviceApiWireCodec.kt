package dev.quotaarc.android.data.api

import dev.quotaarc.android.data.contract.IsoInstantSerializer
import dev.quotaarc.android.data.contract.QuotaArcV1JsonCodec
import dev.quotaarc.android.data.contract.containsUnsafeClientText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant

internal class DeviceWireContractException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

@Serializable
internal data class CollectorHealth(
    val schemaVersion: Int,
    val collectorId: String,
    @Serializable(with = IsoInstantSerializer::class)
    val generatedAt: Instant,
    val capabilities: List<String>,
)

@Serializable
internal data class RefreshReceipt(
    val schemaVersion: Int,
    val collectorId: String,
    val requestId: String,
    @Serializable(with = IsoInstantSerializer::class)
    val acceptedAt: Instant,
    @Serializable(with = IsoInstantSerializer::class)
    val completedAt: Instant,
    val status: String,
    @Serializable(with = IsoInstantSerializer::class)
    val summaryGeneratedAt: Instant,
)

@Serializable
internal data class DeviceErrorEnvelope(
    val schemaVersion: Int,
    val error: DeviceErrorBody,
)

@Serializable
internal data class DeviceErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

internal class DeviceApiWireCodec(
    private val maxPayloadBytes: Int = QuotaArcV1JsonCodec.DEFAULT_MAX_PAYLOAD_BYTES,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        explicitNulls = true
    }

    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
    }

    fun decodeHealth(bytes: ByteArray): CollectorHealth =
        decode<CollectorHealth>(bytes).also(::validateHealth)

    fun decodeRefreshReceipt(bytes: ByteArray): RefreshReceipt =
        decode<RefreshReceipt>(bytes).also(::validateRefreshReceipt)

    fun decodeError(bytes: ByteArray): DeviceErrorEnvelope =
        decode<DeviceErrorEnvelope>(bytes).also(::validateError)

    private inline fun <reified T> decode(bytes: ByteArray): T {
        if (bytes.size > maxPayloadBytes) {
            throw DeviceWireContractException("Device API response is too large")
        }
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw DeviceWireContractException("Device API response is not UTF-8", error)
        }
        return try {
            json.decodeFromString(text)
        } catch (error: SerializationException) {
            throw DeviceWireContractException("Device API response is malformed", error)
        } catch (error: IllegalArgumentException) {
            throw DeviceWireContractException("Device API response is malformed", error)
        }
    }

    private fun validateHealth(value: CollectorHealth) {
        if (
            value.schemaVersion != DEVICE_SCHEMA_VERSION ||
            !COLLECTOR_ID_PATTERN.matches(value.collectorId)
        ) {
            throw DeviceWireContractException("Collector health identity is invalid")
        }
        validateCapabilities(value.capabilities)
    }

    private fun validateRefreshReceipt(value: RefreshReceipt) {
        if (
            value.schemaVersion != DEVICE_SCHEMA_VERSION ||
            !COLLECTOR_ID_PATTERN.matches(value.collectorId) ||
            !REQUEST_ID_PATTERN.matches(value.requestId) ||
            value.status !in REFRESH_STATUSES ||
            value.completedAt.isBefore(value.acceptedAt)
        ) {
            throw DeviceWireContractException("Refresh receipt is invalid")
        }
    }

    private fun validateError(value: DeviceErrorEnvelope) {
        val body = value.error
        if (
            value.schemaVersion != DEVICE_SCHEMA_VERSION ||
            !ERROR_CODE_PATTERN.matches(body.code) ||
            body.message.isEmpty() ||
            body.message.length > MAX_ERROR_MESSAGE_LENGTH ||
            containsUnsafeClientText(body.message)
        ) {
            throw DeviceWireContractException("Device API error is invalid")
        }
    }

    private fun validateCapabilities(capabilities: List<String>) {
        if (
            capabilities.isEmpty() ||
            capabilities.any { it !in DEVICE_CAPABILITIES } ||
            capabilities.toSet().size != capabilities.size
        ) {
            throw DeviceWireContractException("Collector capabilities are invalid")
        }
    }

    private companion object {
        const val DEVICE_SCHEMA_VERSION = 1
        const val MAX_ERROR_MESSAGE_LENGTH = 240
        val COLLECTOR_ID_PATTERN = Regex("""^qac_[A-Za-z0-9_-]{22,64}$""")
        val REQUEST_ID_PATTERN = Regex("""^qar_[A-Za-z0-9_-]{16,64}$""")
        val ERROR_CODE_PATTERN = Regex("""^[a-z0-9][a-z0-9_.-]{0,79}$""")
        val DEVICE_CAPABILITIES = setOf("summary.read", "refresh.write")
        val REFRESH_STATUSES = setOf("refreshed", "coalesced")
    }
}
