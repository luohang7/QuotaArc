package dev.quotaarc.android.data.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class ContractFailureKind {
    PAYLOAD_TOO_LARGE,
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    INVALID,
}

class QuotaArcContractException(
    val kind: ContractFailureKind,
    val issues: List<ContractValidationIssue> = emptyList(),
    cause: Throwable? = null,
) : IllegalArgumentException(
    when (kind) {
        ContractFailureKind.PAYLOAD_TOO_LARGE -> "QuotaArc response is too large"
        ContractFailureKind.MALFORMED -> "QuotaArc response is malformed"
        ContractFailureKind.UNSUPPORTED_SCHEMA -> "QuotaArc schema version is unsupported"
        ContractFailureKind.INVALID -> "QuotaArc response violates the v1 contract"
    },
    cause,
)

class QuotaArcV1JsonCodec(
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        encodeDefaults = true
    }

    fun decode(bytes: ByteArray): QuotaArcSummary {
        if (bytes.size > maxPayloadBytes) {
            throw QuotaArcContractException(ContractFailureKind.PAYLOAD_TOO_LARGE)
        }
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw QuotaArcContractException(ContractFailureKind.MALFORMED, cause = error)
        }
        val summary = try {
            json.decodeFromString<QuotaArcSummary>(text)
        } catch (error: SerializationException) {
            throw QuotaArcContractException(ContractFailureKind.MALFORMED, cause = error)
        } catch (error: IllegalArgumentException) {
            throw QuotaArcContractException(ContractFailureKind.MALFORMED, cause = error)
        }
        val issues = QuotaArcV1Validator.validate(summary)
        if (issues.isNotEmpty()) {
            val kind =
                if (issues.any { it.path == "$.schemaVersion" }) {
                    ContractFailureKind.UNSUPPORTED_SCHEMA
                } else {
                    ContractFailureKind.INVALID
                }
            throw QuotaArcContractException(kind, issues)
        }
        return summary
    }

    fun decode(text: String): QuotaArcSummary = decode(text.toByteArray(StandardCharsets.UTF_8))

    fun encode(summary: QuotaArcSummary): String {
        QuotaArcV1Validator.requireValid(summary)
        return json.encodeToString(summary)
    }

    companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024
    }
}
