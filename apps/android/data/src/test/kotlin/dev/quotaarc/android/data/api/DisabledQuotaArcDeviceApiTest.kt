package dev.quotaarc.android.data.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DisabledQuotaArcDeviceApiTest {
    @Test
    fun `every release capability is gate closed and non-retryable`() = runTest {
        val calls = listOf(
            DisabledQuotaArcDeviceApi.health(),
            DisabledQuotaArcDeviceApi.fetchSummary(),
            DisabledQuotaArcDeviceApi.requestRefresh(),
        )
        calls.forEach { call ->
            val failure = (call as DeviceApiResult.Failure).failure
            assertEquals(DeviceApiFailureKind.GATE_CLOSED, failure.kind)
            assertEquals("transport_gate_closed", failure.code)
            assertFalse(failure.retryable)
        }
    }
}
