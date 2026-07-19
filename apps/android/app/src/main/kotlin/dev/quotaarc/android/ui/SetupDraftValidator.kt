package dev.quotaarc.android.ui

import dev.quotaarc.android.data.connection.DevicePairingCodec
import dev.quotaarc.android.ui.model.SetupFieldError
import java.nio.charset.StandardCharsets

internal data class SetupValidation(
    val pairingError: SetupFieldError?,
) {
    val isValid: Boolean
        get() = pairingError == null
}

internal object SetupDraftValidator {
    fun validate(pairingJson: String): SetupValidation =
        SetupValidation(
            pairingError = when {
                pairingJson.isBlank() -> SetupFieldError.PAIRING_REQUIRED
                pairingJson.length > DevicePairingCodec.MAX_PAIRING_BYTES ||
                    pairingJson.toByteArray(StandardCharsets.UTF_8).size >
                    DevicePairingCodec.MAX_PAIRING_BYTES ->
                    SetupFieldError.PAIRING_TOO_LARGE
                else -> null
            },
        )
}
