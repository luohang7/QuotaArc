package dev.quotaarc.android.data.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class DevicePairingFailureKind {
    MALFORMED,
    UNSUPPORTED_PAIRING_VERSION,
    INVALID_ENDPOINT,
    INVALID_COLLECTOR_ID,
    INVALID_CERTIFICATE_FINGERPRINT,
    INVALID_DEVICE_TOKEN,
    INVALID_SCOPES,
}

class DevicePairingException(
    val kind: DevicePairingFailureKind,
) : IllegalArgumentException("Invalid device pairing bundle: ${kind.name}")

object DevicePairingCodec {
    private const val SUPPORTED_PAIRING_VERSION = 1
    const val MAX_PAIRING_BYTES = 16 * 1024

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
    }

    fun decode(bytes: ByteArray): DeviceConnection {
        if (bytes.size > MAX_PAIRING_BYTES) {
            throw DevicePairingException(DevicePairingFailureKind.MALFORMED)
        }
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw DevicePairingException(DevicePairingFailureKind.MALFORMED)
        }
        return decode(text)
    }

    fun decode(text: String): DeviceConnection {
        if (
            text.length > MAX_PAIRING_BYTES ||
            text.toByteArray(StandardCharsets.UTF_8).size > MAX_PAIRING_BYTES
        ) {
            throw DevicePairingException(DevicePairingFailureKind.MALFORMED)
        }
        val bundle = try {
            json.decodeFromString<PairingBundleWire>(text)
        } catch (_: SerializationException) {
            throw DevicePairingException(DevicePairingFailureKind.MALFORMED)
        } catch (_: IllegalArgumentException) {
            throw DevicePairingException(DevicePairingFailureKind.MALFORMED)
        }
        if (bundle.pairingVersion != SUPPORTED_PAIRING_VERSION) {
            throw DevicePairingException(
                DevicePairingFailureKind.UNSUPPORTED_PAIRING_VERSION,
            )
        }
        val endpoint = DeviceConnectionRules.normalizeEndpoint(bundle.endpoint)
            ?: throw DevicePairingException(DevicePairingFailureKind.INVALID_ENDPOINT)
        if (!DeviceConnectionRules.isCollectorId(bundle.collectorId)) {
            throw DevicePairingException(DevicePairingFailureKind.INVALID_COLLECTOR_ID)
        }
        if (!DeviceConnectionRules.isCertificateSha256(bundle.certificateSha256)) {
            throw DevicePairingException(
                DevicePairingFailureKind.INVALID_CERTIFICATE_FINGERPRINT,
            )
        }
        val credential = try {
            DeviceCredential.parse(bundle.deviceToken)
        } catch (_: IllegalArgumentException) {
            throw DevicePairingException(DevicePairingFailureKind.INVALID_DEVICE_TOKEN)
        }
        val scopes = parseScopes(bundle.scopes)
        val metadata = DeviceConnectionMetadata(
            endpoint = endpoint,
            collectorId = bundle.collectorId,
            certificateSha256 = bundle.certificateSha256,
            deviceId = credential.deviceId,
            scopes = scopes,
        )
        return DeviceConnection(
            metadata = metadata,
            credential = credential,
        )
    }

    private fun parseScopes(values: List<String>): Set<DeviceCapability> {
        if (values.isEmpty() || values.toSet().size != values.size) {
            throw DevicePairingException(DevicePairingFailureKind.INVALID_SCOPES)
        }
        val scopes = values.mapTo(linkedSetOf()) { value ->
            DeviceCapability.fromWireName(value)
                ?: throw DevicePairingException(DevicePairingFailureKind.INVALID_SCOPES)
        }
        if (DeviceCapability.SUMMARY_READ !in scopes) {
            throw DevicePairingException(DevicePairingFailureKind.INVALID_SCOPES)
        }
        return scopes
    }
}

@Serializable
private data class PairingBundleWire(
    val pairingVersion: Int,
    val endpoint: String,
    val collectorId: String,
    val certificateSha256: String,
    val deviceToken: String,
    val scopes: List<String>,
)
