package dev.quotaarc.android.ui

import dev.quotaarc.android.data.endpoint.EndpointDraftError
import dev.quotaarc.android.data.endpoint.EndpointDraftValidator
import dev.quotaarc.android.data.endpoint.EndpointValidationResult
import dev.quotaarc.android.ui.model.SetupFieldError

internal data class SetupValidation(
    val endpointError: SetupFieldError?,
    val tokenError: SetupFieldError?,
) {
    val isValid: Boolean
        get() = endpointError == null && tokenError == null
}

internal object SetupDraftValidator {
    private const val MAX_TOKEN_LENGTH = 4_096

    fun validate(endpoint: String, token: String): SetupValidation =
        SetupValidation(
            endpointError = validateEndpoint(endpoint),
            tokenError = validateToken(token),
        )

    private fun validateEndpoint(value: String): SetupFieldError? {
        return when (val result = EndpointDraftValidator.validate(value)) {
            is EndpointValidationResult.Valid -> null
            is EndpointValidationResult.Invalid -> result.reason.toSetupFieldError()
        }
    }

    private fun validateToken(value: String): SetupFieldError? = when {
        value.isBlank() -> SetupFieldError.TOKEN_REQUIRED
        value.length > MAX_TOKEN_LENGTH -> SetupFieldError.TOKEN_TOO_LONG
        else -> null
    }
}

private fun EndpointDraftError.toSetupFieldError(): SetupFieldError = when (this) {
    EndpointDraftError.EMPTY -> SetupFieldError.ENDPOINT_REQUIRED
    EndpointDraftError.HTTPS_REQUIRED -> SetupFieldError.HTTPS_REQUIRED
    EndpointDraftError.INVALID_PORT -> SetupFieldError.INVALID_PORT
    EndpointDraftError.MALFORMED,
    EndpointDraftError.HOST_REQUIRED,
    EndpointDraftError.USER_INFO_FORBIDDEN,
    EndpointDraftError.PATH_FORBIDDEN,
    EndpointDraftError.QUERY_FORBIDDEN,
    EndpointDraftError.FRAGMENT_FORBIDDEN,
    -> SetupFieldError.ORIGIN_REQUIRED
}
