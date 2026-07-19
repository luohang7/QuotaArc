package dev.quotaarc.android.data.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisabledQuotaArcDeviceApiTest {
    @Test
    fun `connection fallback uses actionable non-retryable reasons`() = runTest {
        for ((reason, code) in listOf(
            DisabledConnectionReason.NOT_CONFIGURED to "connection.not_configured",
            DisabledConnectionReason.CREDENTIAL_UNAVAILABLE to "credential.unavailable",
        )) {
            val result = DisabledQuotaArcDeviceApi
                .forConnectionFailure(reason)
                .fetchSummary()
            assertTrue(result is DeviceApiResult.Failure)
            val failure = (result as DeviceApiResult.Failure).failure
            assertEquals(DeviceApiFailureKind.UNAUTHORIZED, failure.kind)
            assertEquals(code, failure.code)
            assertFalse(failure.retryable)
        }
    }
}
