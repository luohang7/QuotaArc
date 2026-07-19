package dev.quotaarc.android.ui

import dev.quotaarc.android.ui.model.SetupFieldError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupDraftValidatorTest {
    @Test
    fun `accepts an HTTPS origin and non-empty token draft`() {
        val result = SetupDraftValidator.validate(
            endpoint = " https://collector.example.test:8443/ ",
            token = "pairing-token",
        )

        assertNull(result.endpointError)
        assertNull(result.tokenError)
    }

    @Test
    fun `rejects non-HTTPS endpoint`() {
        val result = SetupDraftValidator.validate(
            endpoint = "http://collector.example.test",
            token = "pairing-token",
        )

        assertEquals(SetupFieldError.HTTPS_REQUIRED, result.endpointError)
    }

    @Test
    fun `rejects credentials path query and fragment`() {
        val candidates = listOf(
            "https://user:secret@collector.example.test",
            "https://collector.example.test/api",
            "https://collector.example.test?token=secret",
            "https://collector.example.test#fragment",
        )

        candidates.forEach { endpoint ->
            val result = SetupDraftValidator.validate(endpoint, "pairing-token")
            assertEquals(endpoint, SetupFieldError.ORIGIN_REQUIRED, result.endpointError)
        }
    }

    @Test
    fun `requires token and caps draft size`() {
        assertEquals(
            SetupFieldError.TOKEN_REQUIRED,
            SetupDraftValidator.validate(
                "https://collector.example.test",
                "   ",
            ).tokenError,
        )
        assertEquals(
            SetupFieldError.TOKEN_TOO_LONG,
            SetupDraftValidator.validate(
                "https://collector.example.test",
                "x".repeat(4_097),
            ).tokenError,
        )
    }
}
