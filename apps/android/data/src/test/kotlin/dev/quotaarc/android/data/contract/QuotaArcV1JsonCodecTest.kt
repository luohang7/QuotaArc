package dev.quotaarc.android.data.contract

import dev.quotaarc.android.data.testing.canonicalFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaArcV1JsonCodecTest {
    private val codec = QuotaArcV1JsonCodec()

    @Test
    fun `canonical examples decode and round-trip`() {
        for (name in listOf("summary.ok.json", "summary.degraded.json")) {
            val decoded = codec.decode(canonicalFixture(name))
            assertEquals(QUOTA_ARC_SCHEMA_VERSION, decoded.schemaVersion)
            assertEquals(decoded, codec.decode(codec.encode(decoded)))
        }
    }

    @Test
    fun `unknown fields and malformed timestamps fail closed`() {
        val parsed = Json.parseToJsonElement(
            canonicalFixture("summary.ok.json").decodeToString(),
        ).jsonObject
        val withUnknown = JsonObject(parsed + ("turns" to JsonPrimitive("private"))).toString()
        assertEquals(
            ContractFailureKind.MALFORMED,
            assertThrows(QuotaArcContractException::class.java) {
                codec.decode(withUnknown)
            }.kind,
        )

        val badTimestamp = canonicalFixture("summary.ok.json")
            .decodeToString()
            .replace("2026-07-19T08:30:00Z", "2026-02-31T08:30:00Z")
        assertEquals(
            ContractFailureKind.MALFORMED,
            assertThrows(QuotaArcContractException::class.java) {
                codec.decode(badTimestamp)
            }.kind,
        )

        for (nonContractTimestamp in listOf(
            "2026-07-19T08:30:00+08:00:30",
            "2026-07-19 08:30:00Z",
            "2026-07-19T08:30Z",
        )) {
            val candidate = canonicalFixture("summary.ok.json")
                .decodeToString()
                .replace("2026-07-19T08:30:00Z", nonContractTimestamp)
            assertEquals(
                nonContractTimestamp,
                ContractFailureKind.MALFORMED,
                assertThrows(QuotaArcContractException::class.java) {
                    codec.decode(candidate)
                }.kind,
            )
        }

        for (nonContractDate in listOf("+2026-07-17", "2026-7-17", "20260717")) {
            val candidate = canonicalFixture("summary.ok.json")
                .decodeToString()
                .replace("2026-07-17", nonContractDate)
            assertEquals(
                nonContractDate,
                ContractFailureKind.MALFORMED,
                assertThrows(QuotaArcContractException::class.java) {
                    codec.decode(candidate)
                }.kind,
            )
        }
    }

    @Test
    fun `payload limit and unsupported schema are distinct`() {
        assertEquals(
            ContractFailureKind.PAYLOAD_TOO_LARGE,
            assertThrows(QuotaArcContractException::class.java) {
                QuotaArcV1JsonCodec(maxPayloadBytes = 8).decode(canonicalFixture("summary.ok.json"))
            }.kind,
        )

        val unsupported = codec.decode(canonicalFixture("summary.ok.json"))
            .copy(schemaVersion = 2)
        assertEquals(
            ContractFailureKind.UNSUPPORTED_SCHEMA,
            assertThrows(QuotaArcContractException::class.java) {
                codec.decode(
                    Json.parseToJsonElement(codec.encode(unsupported.copy(schemaVersion = 1)))
                        .jsonObject
                        .let { JsonObject(it + ("schemaVersion" to JsonPrimitive(2))) }
                        .toString(),
                )
            }.kind,
        )
    }

    @Test
    fun `semantic invariants mirror the TypeScript boundary`() {
        val good = codec.decode(canonicalFixture("summary.ok.json"))

        assertFalse(QuotaArcV1Validator.validate(good.copy(stale = true)).isEmpty())

        val badPercent = good.copy(
            quota = good.quota.copy(
                limits = good.quota.limits.mapIndexed { index, limit ->
                    if (index == 0) {
                        limit.copy(
                            windows = limit.windows.mapIndexed { windowIndex, window ->
                                if (windowIndex == 0) window.copy(remainingPercent = 80.0) else window
                            },
                        )
                    } else {
                        limit
                    }
                },
            ),
        )
        assertTrue(
            QuotaArcV1Validator.validate(badPercent)
                .any { it.path.endsWith(".remainingPercent") },
        )

        val badTotals = good.copy(
            localUsage = good.localUsage.copy(
                models = good.localUsage.models.mapIndexed { index, item ->
                    if (index == 0) item.copy(outputTokens = 1) else item
                },
            ),
        )
        assertTrue(
            QuotaArcV1Validator.validate(badTotals)
                .any { it.path == "$.localUsage.models" },
        )
    }

    @Test
    fun `paths and credential-shaped strings are rejected`() {
        val good = codec.decode(canonicalFixture("summary.ok.json"))
        val unsafeValues = listOf(
            "model /opt/company/private-project",
            "/etc/passwd",
            """C:\company\private\model""",
            """\\server\share\model""",
            "file:///Users/example/model",
            "~/private/model",
            "Bearer abcdefghijklmnop",
            "sk-abcdefghijklmnop",
            "sess-abcdefghijklmnop",
        )

        unsafeValues.forEach { unsafe ->
            val candidate = good.copy(
                localUsage = good.localUsage.copy(
                    models = good.localUsage.models.mapIndexed { index, item ->
                        if (index == 0) item.copy(label = unsafe) else item
                    },
                ),
            )
            assertTrue(
                unsafe,
                QuotaArcV1Validator.validate(candidate)
                    .any { it.path == "$.localUsage.models[0].label" },
            )
        }
    }

    @Test
    fun `source coupling uniqueness dates and safe integers are enforced`() {
        val good = codec.decode(canonicalFixture("summary.ok.json"))
        val invalidSource = good.copy(
            sources = good.sources.copy(
                quota = good.sources.quota.copy(
                    status = SourceStatus.OK,
                    collectedAt = null,
                    error = SourceError("bad.code", "Safe diagnostic", retryable = true),
                ),
            ),
        )
        val invalidLimits = good.copy(
            quota = good.quota.copy(limits = listOf(good.quota.limits.first(), good.quota.limits.first())),
        )
        val invalidDates = good.copy(
            accountUsage = good.accountUsage.copy(
                dailyTokens = good.accountUsage.dailyTokens.reversed(),
            ),
        )
        val invalidCounts = good.copy(
            localUsage = good.localUsage.copy(newInputTokens = -1),
        )

        assertTrue(QuotaArcV1Validator.validate(invalidSource).size >= 2)
        assertTrue(QuotaArcV1Validator.validate(invalidLimits).any { it.message == "must be unique" })
        assertTrue(
            QuotaArcV1Validator.validate(invalidDates)
                .any { it.message.contains("strictly ascending") },
        )
        assertTrue(
            QuotaArcV1Validator.validate(invalidCounts)
                .any { it.path == "$.localUsage.newInputTokens" },
        )
    }
}
