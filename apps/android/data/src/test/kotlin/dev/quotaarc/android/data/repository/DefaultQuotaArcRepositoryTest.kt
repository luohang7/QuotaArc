package dev.quotaarc.android.data.repository

import dev.quotaarc.android.data.api.DeviceApiFailure
import dev.quotaarc.android.data.api.DeviceApiFailureKind
import dev.quotaarc.android.data.api.DeviceApiResult
import dev.quotaarc.android.data.contract.QuotaArcV1JsonCodec
import dev.quotaarc.android.data.contract.SourceStatus
import dev.quotaarc.android.data.testing.InMemorySnapshotCache
import dev.quotaarc.android.data.testing.ScriptedQuotaArcDeviceApi
import dev.quotaarc.android.data.testing.canonicalFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultQuotaArcRepositoryTest {
    private val codec = QuotaArcV1JsonCodec()
    private val initialNow = Instant.parse("2026-07-19T09:01:00Z")
    private val collectorA = CollectorIdentity.fromStableId("collector-a")
    private val collectorB = CollectorIdentity.fromStableId("collector-b")

    @Test
    fun `fresh valid response is promoted atomically`() = runTest {
        val api = apiWithFetch(canonicalFixture("summary.ok.json"))
        val repository = repository(api)

        val result = repository.refresh(RefreshTrigger.PERIODIC)
        val content = (result as RefreshResult.Updated).content

        assertEquals(PhoneCacheState.CURRENT, content.phoneCacheState)
        assertFalse(content.snapshot.summary.stale)
        assertNull(content.lastFailure)
        assertEquals(1, api.fetchCalls.get())
        assertEquals(content, repository.current())
    }

    @Test
    fun `Collector stale is independent from a current phone cache`() = runTest {
        val repository = repository(apiWithFetch(canonicalFixture("summary.degraded.json")))

        val content =
            (repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.Updated).content

        assertTrue(content.snapshot.summary.stale)
        assertEquals(PhoneCacheState.CURRENT, content.phoneCacheState)
        assertNull(content.lastFailure)
    }

    @Test
    fun `offline refresh never overwrites last good`() = runTest {
        val failure = DeviceApiResult.Failure(
            DeviceApiFailure(
                kind = DeviceApiFailureKind.OFFLINE,
                code = "transport.offline",
                retryable = true,
            ),
        )
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf(
                { DeviceApiResult.Success(canonicalFixture("summary.ok.json")) },
                { failure },
            ),
        )
        val repository = repository(api)
        val first =
            (repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.Updated)
                .content.snapshot

        val fallback =
            (repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.UsingCache)
                .content

        assertEquals(first, fallback.snapshot)
        assertEquals(PhoneCacheState.FALLBACK, fallback.phoneCacheState)
        assertEquals(RefreshFailureKind.OFFLINE, fallback.lastFailure?.kind)
    }

    @Test
    fun `switching Collector identity cannot expose or reuse another Collector cache`() =
        runTest {
            val sharedCache = InMemorySnapshotCache()
            val repositoryA = repository(
                api = apiWithFetch(canonicalFixture("summary.ok.json")),
                cache = sharedCache,
                collectorIdentity = collectorA,
            )
            val snapshotA =
                (repositoryA.refresh(RefreshTrigger.PERIODIC) as RefreshResult.Updated)
                    .content.snapshot
            assertEquals(snapshotA, (repositoryA.current() as RepositoryState.Content).snapshot)

            val offline = DeviceApiResult.Failure(
                DeviceApiFailure(
                    kind = DeviceApiFailureKind.OFFLINE,
                    code = "transport.offline",
                    retryable = true,
                ),
            )
            val repositoryB = repository(
                api = ScriptedQuotaArcDeviceApi(fetchSteps = listOf({ offline })),
                cache = sharedCache,
                collectorIdentity = collectorB,
            )

            assertEquals(RepositoryState.Empty, repositoryB.current())
            val switched = repositoryB.refresh(RefreshTrigger.PERIODIC)

            assertTrue(switched is RefreshResult.Failed)
            assertEquals(
                RefreshFailureKind.OFFLINE,
                (repositoryB.current() as RepositoryState.Error).failure.kind,
            )
            assertEquals(RepositoryState.Empty, repositoryA.current())
            assertEquals(collectorB.stableId, sharedCache.read().collectorIdentity)
            assertNull(sharedCache.read().lastGood)
        }

    @Test
    fun `aged successful cache and aged fallback remain distinct`() = runTest {
        val clock = MutableClock(initialNow)
        val failure = DeviceApiResult.Failure(
            DeviceApiFailure(DeviceApiFailureKind.TIMEOUT, "transport.timeout", true),
        )
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf(
                { DeviceApiResult.Success(canonicalFixture("summary.ok.json")) },
                { failure },
            ),
        )
        val repository = repository(api, clock = clock, staleAfter = Duration.ofMinutes(30))
        repository.refresh(RefreshTrigger.PERIODIC)

        clock.now = clock.now.plus(Duration.ofHours(1))
        assertEquals(
            PhoneCacheState.AGED,
            (repository.current() as RepositoryState.Content).phoneCacheState,
        )

        val fallback =
            (repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.UsingCache).content
        assertEquals(PhoneCacheState.STALE_FALLBACK, fallback.phoneCacheState)
    }

    @Test
    fun `malformed and unsupported responses preserve cache`() = runTest {
        val good = canonicalFixture("summary.ok.json")
        val schemaTwo = good.decodeToString()
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
            .encodeToByteArray()
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf(
                { DeviceApiResult.Success(good) },
                { DeviceApiResult.Success("{not-json".encodeToByteArray()) },
                { DeviceApiResult.Success(schemaTwo) },
            ),
        )
        val repository = repository(api)
        val original =
            (repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.Updated)
                .content.snapshot

        val malformed =
            repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.UsingCache
        val unsupported =
            repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.UsingCache

        assertEquals(original, malformed.content.snapshot)
        assertEquals(RefreshFailureKind.CONTRACT_INVALID, malformed.failure.kind)
        assertEquals(original, unsupported.content.snapshot)
        assertEquals(RefreshFailureKind.UNSUPPORTED_SCHEMA, unsupported.failure.kind)
    }

    @Test
    fun `valid but non-renderable and out-of-order responses do not replace last good`() =
        runTest {
            val good = codec.decode(canonicalFixture("summary.ok.json"))
            val nonRenderable = good.copy(
                stale = false,
                sources = good.sources.copy(
                    quota = good.sources.quota.copy(
                        status = SourceStatus.UNAVAILABLE,
                        collectedAt = null,
                        error = null,
                    ),
                    accountUsage = good.sources.accountUsage.copy(
                        status = SourceStatus.UNAVAILABLE,
                        collectedAt = null,
                        error = null,
                    ),
                    localUsage = good.sources.localUsage.copy(
                        status = SourceStatus.UNAVAILABLE,
                        collectedAt = null,
                        error = null,
                    ),
                ),
            )
            val older = good.copy(generatedAt = good.generatedAt.minusSeconds(60))
            val api = ScriptedQuotaArcDeviceApi(
                fetchSteps = listOf(
                    { DeviceApiResult.Success(codec.encode(good).encodeToByteArray()) },
                    {
                        DeviceApiResult.Success(
                            codec.encode(nonRenderable).encodeToByteArray(),
                        )
                    },
                    { DeviceApiResult.Success(codec.encode(older).encodeToByteArray()) },
                ),
            )
            val repository = repository(api)
            val first =
                (repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.Updated)
                    .content.snapshot

            val noData =
                repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.UsingCache
            val outOfOrder =
                repository.refresh(RefreshTrigger.PERIODIC) as RefreshResult.UsingCache

            assertEquals(first, noData.content.snapshot)
            assertEquals(RefreshFailureKind.NO_RENDERABLE_DATA, noData.failure.kind)
            assertEquals(first, outOfOrder.content.snapshot)
            assertEquals(RefreshFailureKind.OUT_OF_ORDER, outOfOrder.failure.kind)
        }

    @Test
    fun `manual and setup triggers call only their fixed API capabilities`() = runTest {
        val manualApi = apiWithFetch(canonicalFixture("summary.ok.json"))
        val manualRepository = repository(manualApi)
        manualRepository.refresh(RefreshTrigger.MANUAL)
        assertEquals(listOf("refresh", "summary"), manualApi.callOrder)

        val setupApi = apiWithFetch(canonicalFixture("summary.ok.json"))
        val setupRepository = repository(setupApi)
        setupRepository.refresh(RefreshTrigger.SETUP)
        assertEquals(listOf("health", "summary"), setupApi.callOrder)
    }

    @Test
    fun `concurrent callers share one in-flight refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf({
                release.await()
                DeviceApiResult.Success(canonicalFixture("summary.ok.json"))
            }),
        )
        val repository = repository(api)

        val callers = List(40) {
            async { repository.refresh(RefreshTrigger.PERIODIC) }
        }
        runCurrent()
        assertEquals(1, api.fetchCalls.get())
        release.complete(Unit)

        val results = callers.awaitAll()
        assertTrue(results.all { it is RefreshResult.Updated })
        assertSame(results.first(), results.last())
        assertEquals(1, api.fetchCalls.get())
    }

    @Test
    fun `manual intent queues one follow-up behind an in-flight periodic refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf(
                {
                    release.await()
                    DeviceApiResult.Success(canonicalFixture("summary.ok.json"))
                },
                { DeviceApiResult.Success(canonicalFixture("summary.ok.json")) },
            ),
        )
        val repository = repository(api)

        val periodic = async {
            repository.refresh(RefreshTrigger.PERIODIC)
        }
        runCurrent()
        assertEquals(1, api.fetchCalls.get())

        val manualCallers = List(20) {
            async { repository.refresh(RefreshTrigger.MANUAL) }
        }
        runCurrent()
        assertEquals(1, api.fetchCalls.get())
        assertEquals(0, api.refreshCalls.get())

        release.complete(Unit)
        assertTrue(periodic.await() is RefreshResult.Updated)
        val manualResults = manualCallers.awaitAll()

        assertTrue(manualResults.all { it is RefreshResult.Updated })
        assertSame(manualResults.first(), manualResults.last())
        assertEquals(1, api.refreshCalls.get())
        assertEquals(2, api.fetchCalls.get())
        assertEquals(listOf("summary", "refresh", "summary"), api.callOrder)
    }

    @Test
    fun `cancelling one periodic waiter does not cancel its queued manual follow-up`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf({
                release.await()
                DeviceApiResult.Success(canonicalFixture("summary.ok.json"))
            }),
        )
        val repository = repository(api)

        val cancelledWaiter = async {
            repository.refresh(RefreshTrigger.PERIODIC)
        }
        runCurrent()
        assertEquals(1, api.fetchCalls.get())
        cancelledWaiter.cancelAndJoin()

        val survivingManual = async {
            repository.refresh(RefreshTrigger.MANUAL)
        }
        runCurrent()
        assertEquals(1, api.fetchCalls.get())
        assertEquals(0, api.refreshCalls.get())

        release.complete(Unit)
        assertTrue(survivingManual.await() is RefreshResult.Updated)
        assertEquals(1, api.refreshCalls.get())
        assertEquals(2, api.fetchCalls.get())
    }

    @Test
    fun `failure without cache is explicit and retains no fabricated summary`() = runTest {
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf({
                DeviceApiResult.Failure(
                    DeviceApiFailure(
                        DeviceApiFailureKind.UNAUTHORIZED,
                        "auth.rejected",
                        false,
                    ),
                )
            }),
        )
        val repository = repository(api)

        val result = repository.refresh(RefreshTrigger.PERIODIC)
        val state = repository.current()

        assertEquals(RefreshFailureKind.AUTH_REQUIRED, (result as RefreshResult.Failed).failure.kind)
        assertEquals(RefreshFailureKind.AUTH_REQUIRED, (state as RepositoryState.Error).failure.kind)
    }

    private fun apiWithFetch(bytes: ByteArray): ScriptedQuotaArcDeviceApi =
        ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf({ DeviceApiResult.Success(bytes) }),
        )

    private fun kotlinx.coroutines.test.TestScope.repository(
        api: ScriptedQuotaArcDeviceApi,
        cache: InMemorySnapshotCache = InMemorySnapshotCache(),
        collectorIdentity: CollectorIdentity = collectorA,
        clock: MutableClock = MutableClock(initialNow),
        staleAfter: Duration = Duration.ofMinutes(60),
    ): DefaultQuotaArcRepository =
        DefaultQuotaArcRepository(
            api = api,
            cache = cache,
            collectorIdentity = collectorIdentity,
            parentScope = backgroundScope,
            clock = clock,
            staleAfter = staleAfter,
        )

    private class MutableClock(var now: Instant) : QuotaArcClock {
        override fun now(): Instant = now
    }
}
