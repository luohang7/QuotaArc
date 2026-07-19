package dev.quotaarc.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceApiWireCodecTest {
    private val codec = DeviceApiWireCodec()

    @Test
    fun `health and refresh enforce exact device contracts`() {
        val health = codec.decodeHealth(
            """
            {
              "schemaVersion": 1,
              "collectorId": "qac_abcdefghijklmnopqrstuv",
              "generatedAt": "2026-07-19T10:00:00Z",
              "capabilities": ["summary.read", "refresh.write"]
            }
            """.trimIndent().encodeToByteArray(),
        )
        assertEquals(
            listOf("summary.read", "refresh.write"),
            health.capabilities,
        )

        assertThrows(DeviceWireContractException::class.java) {
            codec.decodeHealth(
                """
                {
                  "schemaVersion": 1,
                  "collectorId": "qac_abcdefghijklmnopqrstuv",
                  "generatedAt": "2026-07-19T10:00:00Z",
                  "capabilities": ["summary.read"],
                  "rawRpc": true
                }
                """.trimIndent().encodeToByteArray(),
            )
        }
        assertThrows(DeviceWireContractException::class.java) {
            codec.decodeRefreshReceipt(
                refreshReceipt(
                    acceptedAt = "2026-07-19T10:00:01Z",
                    completedAt = "2026-07-19T10:00:00Z",
                ).encodeToByteArray(),
            )
        }
    }

    @Test
    fun `error body rejects unsafe client text`() {
        assertThrows(DeviceWireContractException::class.java) {
            codec.decodeError(
                """
                {
                  "schemaVersion": 1,
                  "error": {
                    "code": "server.failed",
                    "message": "Read /Users/private/session.jsonl",
                    "retryable": false
                  }
                }
                """.trimIndent().encodeToByteArray(),
            )
        }
    }

    private fun refreshReceipt(
        acceptedAt: String,
        completedAt: String,
    ): String =
        """
        {
          "schemaVersion": 1,
          "collectorId": "qac_abcdefghijklmnopqrstuv",
          "requestId": "qar_abcdefghijklmnop",
          "acceptedAt": "$acceptedAt",
          "completedAt": "$completedAt",
          "status": "refreshed",
          "summaryGeneratedAt": "2026-07-19T10:00:00Z"
        }
        """.trimIndent()
}
