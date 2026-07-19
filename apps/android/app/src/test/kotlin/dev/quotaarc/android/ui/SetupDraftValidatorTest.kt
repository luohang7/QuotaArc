package dev.quotaarc.android.ui

import dev.quotaarc.android.data.connection.DevicePairingCodec
import dev.quotaarc.android.ui.model.SetupFieldError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupDraftValidatorTest {
    @Test
    fun `accepts a bounded non-empty pairing bundle draft`() {
        val result = SetupDraftValidator.validate("""{"pairingVersion":1}""")

        assertNull(result.pairingError)
    }

    @Test
    fun `requires the pairing bundle`() {
        assertEquals(
            SetupFieldError.PAIRING_REQUIRED,
            SetupDraftValidator.validate("   ").pairingError,
        )
    }

    @Test
    fun `caps UTF-8 input at the data contract limit`() {
        assertEquals(
            SetupFieldError.PAIRING_TOO_LARGE,
            SetupDraftValidator.validate(
                "x".repeat(DevicePairingCodec.MAX_PAIRING_BYTES + 1),
            ).pairingError,
        )
        assertEquals(
            SetupFieldError.PAIRING_TOO_LARGE,
            SetupDraftValidator.validate(
                "界".repeat(DevicePairingCodec.MAX_PAIRING_BYTES / 2),
            ).pairingError,
        )
    }
}
