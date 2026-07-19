package dev.quotaarc.android.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePairingCodecTest {
    @Test
    fun `valid pairing bundle produces normalized split connection`() {
        val connection = DevicePairingCodec.decode(
            validPairing(
                endpoint = "https://COLLECTOR.example:443/",
                scopes = """["refresh.write","summary.read"]""",
            ),
        )

        assertEquals("https://collector.example", connection.metadata.endpoint)
        assertEquals(COLLECTOR_ID, connection.metadata.collectorId)
        assertEquals(CERTIFICATE_SHA256, connection.metadata.certificateSha256)
        assertEquals(DEVICE_ID, connection.metadata.deviceId)
        assertEquals(
            setOf(DeviceCapability.SUMMARY_READ, DeviceCapability.REFRESH_WRITE),
            connection.metadata.scopes,
        )
        assertEquals(DEVICE_TOKEN, connection.credential.deviceToken)
        assertFalse(connection.toString().contains(DEVICE_TOKEN))
        assertFalse(connection.toString().contains(DEVICE_SECRET))
        assertTrue(connection.toString().contains("<redacted>"))
    }

    @Test
    fun `byte decoder rejects malformed utf8`() {
        val error = assertThrows(DevicePairingException::class.java) {
            DevicePairingCodec.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }

        assertEquals(DevicePairingFailureKind.MALFORMED, error.kind)
    }

    @Test
    fun `unknown or missing fields are rejected`() {
        val unknownField = validPairing().dropLast(1) + ""","unexpected":true}"""
        assertPairingFailure(DevicePairingFailureKind.MALFORMED, unknownField)
        val missingCertificate = validPairing()
            .lineSequence()
            .filterNot { it.contains("certificateSha256") }
            .joinToString("\n")
        assertPairingFailure(
            DevicePairingFailureKind.MALFORMED,
            missingCertificate,
        )
    }

    @Test
    fun `unsupported pairing version is rejected`() {
        assertPairingFailure(
            DevicePairingFailureKind.UNSUPPORTED_PAIRING_VERSION,
            validPairing().replace(""""pairingVersion": 1""", """"pairingVersion": 2"""),
        )
    }

    @Test
    fun `pairing input is bounded before decoding`() {
        assertPairingFailure(
            DevicePairingFailureKind.MALFORMED,
            " ".repeat(DevicePairingCodec.MAX_PAIRING_BYTES + 1),
        )
        val error = assertThrows(DevicePairingException::class.java) {
            DevicePairingCodec.decode(
                ByteArray(DevicePairingCodec.MAX_PAIRING_BYTES + 1),
            )
        }
        assertEquals(DevicePairingFailureKind.MALFORMED, error.kind)
    }

    @Test
    fun `endpoint must be an https origin`() {
        listOf(
            "http://collector.example",
            "https://user@collector.example",
            "https://collector.example/v1",
            "https://collector.example?query=1",
            "https://collector.example#fragment",
            "https://collector.example:0",
            "https://",
            " https://collector.example",
        ).forEach { endpoint ->
            assertPairingFailure(
                DevicePairingFailureKind.INVALID_ENDPOINT,
                validPairing(endpoint = endpoint),
            )
        }
    }

    @Test
    fun `collector certificate and token formats are exact`() {
        assertPairingFailure(
            DevicePairingFailureKind.INVALID_COLLECTOR_ID,
            validPairing(collectorId = "qac_too-short"),
        )
        assertPairingFailure(
            DevicePairingFailureKind.INVALID_CERTIFICATE_FINGERPRINT,
            validPairing(certificateSha256 = CERTIFICATE_SHA256.lowercase()),
        )
        assertPairingFailure(
            DevicePairingFailureKind.INVALID_DEVICE_TOKEN,
            validPairing(deviceToken = "qa1.short.$DEVICE_SECRET"),
        )
        assertPairingFailure(
            DevicePairingFailureKind.INVALID_DEVICE_TOKEN,
            validPairing(deviceToken = "qa1.$DEVICE_ID.${DEVICE_SECRET.dropLast(1)}!"),
        )
    }

    @Test
    fun `scopes must be known unique nonempty and include summary read`() {
        listOf(
            "[]",
            """["refresh.write"]""",
            """["summary.read","summary.read"]""",
            """["summary.read","admin.write"]""",
        ).forEach { scopes ->
            assertPairingFailure(
                DevicePairingFailureKind.INVALID_SCOPES,
                validPairing(scopes = scopes),
            )
        }
    }

    private fun assertPairingFailure(
        expected: DevicePairingFailureKind,
        json: String,
    ) {
        val error = assertThrows(DevicePairingException::class.java) {
            DevicePairingCodec.decode(json)
        }
        assertEquals(expected, error.kind)
        assertFalse(error.message.orEmpty().contains(DEVICE_TOKEN))
        assertFalse(error.message.orEmpty().contains(DEVICE_SECRET))
    }

    private fun validPairing(
        endpoint: String = "https://collector.example:8443",
        collectorId: String = COLLECTOR_ID,
        certificateSha256: String = CERTIFICATE_SHA256,
        deviceToken: String = DEVICE_TOKEN,
        scopes: String = """["summary.read","refresh.write"]""",
    ): String =
        """
        {
          "pairingVersion": 1,
          "endpoint": "$endpoint",
          "collectorId": "$collectorId",
          "certificateSha256": "$certificateSha256",
          "deviceToken": "$deviceToken",
          "scopes": $scopes
        }
        """.trimIndent()

    private companion object {
        const val COLLECTOR_ID = "qac_abcdefghijklmnopqrstuv"
        const val CERTIFICATE_SHA256 =
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
        const val DEVICE_ID = "abcdefghijkl"
        const val DEVICE_SECRET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"
        const val DEVICE_TOKEN = "qa1.$DEVICE_ID.$DEVICE_SECRET"
    }
}
