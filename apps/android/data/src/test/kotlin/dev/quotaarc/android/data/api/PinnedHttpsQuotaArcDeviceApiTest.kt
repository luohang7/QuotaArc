package dev.quotaarc.android.data.api

import dev.quotaarc.android.data.testing.canonicalFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PinnedHttpsQuotaArcDeviceApiTest {
    @Test
    fun `redirect is rejected without following it`() = runTest {
        val transport = RecordingTransport(
            response = DeviceHttpResponse(
                statusCode = 302,
                headers = mapOf("Location" to listOf("https://attacker.invalid/v1/summary")),
                body = ByteArray(0),
            ),
        )
        val api = api(transport)

        assertFailure(
            api.fetchSummary(),
            DeviceApiFailureKind.PROTOCOL,
            "response.redirect_forbidden",
        )
        assertEquals(1, transport.requests.size)
        assertEquals(DeviceApiRoute.SUMMARY, transport.requests.single().route)
    }

    @Test
    fun `oversized response is rejected before repository decode`() = runTest {
        val transport = RecordingTransport(
            response = successResponse(
                body = ByteArray(256 * 1024 + 1),
            ),
        )
        val api = api(transport)

        assertFailure(
            api.fetchSummary(),
            DeviceApiFailureKind.PROTOCOL,
            "response.too_large",
        )
    }

    @Test
    fun `collector response identity must match pairing`() = runTest {
        val transport = RecordingTransport(
            response = successResponse(
                body = canonicalFixture("summary.ok.json"),
                collectorId = "qac_zyxwvutsrqponmlkjihgfe",
            ),
        )
        val api = api(transport)

        assertFailure(
            api.fetchSummary(),
            DeviceApiFailureKind.PROTOCOL,
            "response.collector_identity_mismatch",
        )
    }

    @Test
    fun `health and refresh bodies are strict and identity bound`() = runTest {
        val transport = RecordingTransport(
            response = successResponse(
                body = healthJson().encodeToByteArray(),
            ),
        )
        val api = api(transport)
        assertTrue(api.health() is DeviceApiResult.Success)

        transport.response = successResponse(
            body = refreshJson().encodeToByteArray(),
        )
        assertTrue(api.requestRefresh() is DeviceApiResult.Success)

        transport.response = successResponse(
            body = healthJson()
                .dropLast(1)
                .plus(""","rawRpc":true}""")
                .encodeToByteArray(),
        )
        assertFailure(
            api.health(),
            DeviceApiFailureKind.PROTOCOL,
            "response.invalid",
        )
    }

    @Test
    fun `strict remote error maps status and retryability`() = runTest {
        val transport = RecordingTransport(
            response = DeviceHttpResponse(
                statusCode = 401,
                headers = mapOf(
                    "Content-Type" to listOf("application/json"),
                ),
                body =
                    """
                    {
                      "schemaVersion": 1,
                      "error": {
                        "code": "auth.expired",
                        "message": "Authentication expired",
                        "retryable": false
                      }
                    }
                    """.trimIndent().encodeToByteArray(),
            ),
        )
        val api = api(transport)

        assertFailure(
            api.fetchSummary(),
            DeviceApiFailureKind.UNAUTHORIZED,
            "auth.expired",
        )
    }

    @Test
    fun `bounded reader rejects stream without declared length`() {
        val error = runCatching {
            readBoundedResponse(
                ByteArrayInputStream(ByteArray(33)),
                maxBytes = 32,
            )
        }.exceptionOrNull()

        assertTrue(error is DeviceResponseTooLargeException)
    }

    private fun api(transport: DeviceHttpTransport): PinnedHttpsQuotaArcDeviceApi =
        PinnedHttpsQuotaArcDeviceApi(
            rawConfig = validConfig(),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun validConfig() = DeviceApiConnectionConfig(
        endpoint = "https://collector.example:9443",
        collectorId = COLLECTOR_ID,
        certificateSha256 = "A".repeat(64),
        deviceToken =
            "qa1.abcdefghijklmnop.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef",
        scopes = setOf("summary.read", "refresh.write"),
    )

    private fun successResponse(
        body: ByteArray,
        collectorId: String = COLLECTOR_ID,
    ) = DeviceHttpResponse(
        statusCode = 200,
        headers = mapOf(
            "Content-Type" to listOf("application/json; charset=utf-8"),
            "X-QuotaArc-Collector-Id" to listOf(collectorId),
        ),
        body = body,
    )

    private fun healthJson(): String =
        """
        {
          "schemaVersion": 1,
          "collectorId": "$COLLECTOR_ID",
          "generatedAt": "2026-07-19T10:00:00Z",
          "capabilities": ["summary.read", "refresh.write"]
        }
        """.trimIndent()

    private fun refreshJson(): String =
        """
        {
          "schemaVersion": 1,
          "collectorId": "$COLLECTOR_ID",
          "requestId": "qar_abcdefghijklmnop",
          "acceptedAt": "2026-07-19T10:00:00Z",
          "completedAt": "2026-07-19T10:00:01Z",
          "status": "refreshed",
          "summaryGeneratedAt": "2026-07-19T10:00:01Z"
        }
        """.trimIndent()

    private fun assertFailure(
        result: DeviceApiResult<*>,
        kind: DeviceApiFailureKind,
        code: String,
    ) {
        assertTrue(result is DeviceApiResult.Failure)
        val failure = (result as DeviceApiResult.Failure).failure
        assertEquals(kind, failure.kind)
        assertEquals(code, failure.code)
    }

    private class RecordingTransport(
        var response: DeviceHttpResponse,
    ) : DeviceHttpTransport {
        val requests = mutableListOf<DeviceHttpRequest>()

        override fun execute(request: DeviceHttpRequest): DeviceHttpResponse {
            requests += request
            return response
        }
    }

    private companion object {
        const val COLLECTOR_ID = "qac_abcdefghijklmnopqrstuv"
    }
}
