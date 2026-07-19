package dev.quotaarc.android.data.endpoint

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

class ValidatedEndpoint internal constructor(
    val baseUrl: String,
)

enum class EndpointDraftError {
    EMPTY,
    MALFORMED,
    HTTPS_REQUIRED,
    HOST_REQUIRED,
    INVALID_PORT,
    USER_INFO_FORBIDDEN,
    PATH_FORBIDDEN,
    QUERY_FORBIDDEN,
    FRAGMENT_FORBIDDEN,
}

sealed interface EndpointValidationResult {
    data class Valid(val endpoint: ValidatedEndpoint) : EndpointValidationResult
    data class Invalid(val reason: EndpointDraftError) : EndpointValidationResult
}

/**
 * Validates and canonicalizes the HTTPS origin before production transport is
 * constructed. This type deliberately has no persistence method.
 */
object EndpointDraftValidator {
    fun validate(raw: String): EndpointValidationResult {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return EndpointValidationResult.Invalid(EndpointDraftError.EMPTY)

        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return EndpointValidationResult.Invalid(EndpointDraftError.MALFORMED)
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return EndpointValidationResult.Invalid(EndpointDraftError.HTTPS_REQUIRED)
        }
        if (uri.rawUserInfo != null) {
            return EndpointValidationResult.Invalid(EndpointDraftError.USER_INFO_FORBIDDEN)
        }
        if (uri.host.isNullOrBlank()) {
            return EndpointValidationResult.Invalid(EndpointDraftError.HOST_REQUIRED)
        }
        if (uri.port == 0 || uri.port < -1 || uri.port > 65_535) {
            return EndpointValidationResult.Invalid(EndpointDraftError.INVALID_PORT)
        }
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") {
            return EndpointValidationResult.Invalid(EndpointDraftError.PATH_FORBIDDEN)
        }
        if (uri.rawQuery != null) {
            return EndpointValidationResult.Invalid(EndpointDraftError.QUERY_FORBIDDEN)
        }
        if (uri.rawFragment != null) {
            return EndpointValidationResult.Invalid(EndpointDraftError.FRAGMENT_FORBIDDEN)
        }

        val normalizedPort = if (uri.port == 443) -1 else uri.port
        val normalized = URI(
            "https",
            null,
            uri.host.lowercase(Locale.ROOT),
            normalizedPort,
            null,
            null,
            null,
        ).toASCIIString()
        return EndpointValidationResult.Valid(ValidatedEndpoint(normalized))
    }
}
