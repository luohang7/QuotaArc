package dev.quotaarc.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DeviceRequestSignerTest {
    @Test
    fun `signature matches Collector known vector`() {
        val signer = DeviceRequestSigner(
            now = { Instant.parse("2026-07-19T10:00:00Z") },
            nonceGenerator = DeviceNonceGenerator {
                "abcdefghijklmnopqrstuv"
            },
        )

        val signed = signer.sign(
            deviceToken =
                "qa1.abcdefghijklmnop.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef",
            route = DeviceApiRoute.SUMMARY,
        )

        assertEquals(
            "QuotaArc-HMAC " +
                "abcdefghijklmnop:" +
                "U2Bl_PNSWf2L9rI2d9OioiGEH7afPNDRgvdW85UzAOE",
            signed.headers[DeviceRequestSigner.AUTHORIZATION_HEADER],
        )
        assertEquals(
            "1784455200",
            signed.headers[DeviceRequestSigner.TIMESTAMP_HEADER],
        )
        assertEquals(
            "abcdefghijklmnopqrstuv",
            signed.headers[DeviceRequestSigner.NONCE_HEADER],
        )
    }
}
