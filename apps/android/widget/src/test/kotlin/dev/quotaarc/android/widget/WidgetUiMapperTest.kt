package dev.quotaarc.android.widget

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUiMapperTest {
    private val now = Instant.parse("2026-07-19T09:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `compact primary is the lowest remaining bucket while medium keeps all`() {
        val ui = map(
            buckets = listOf(
                bucket("codex", "Codex", 10_080, 81.0, "2026-07-25T00:00:00Z"),
                bucket("model", "Model", 300, 20.0, "2026-07-19T12:00:00Z"),
                bucket("other", "Other", 60, 55.0, "2026-07-19T10:00:00Z"),
            ),
        )

        assertEquals("Model · 5小时", ui.primaryBucket?.title)
        assertEquals("20% 可用", ui.primaryBucket?.remainingText)
        assertEquals(3, ui.buckets.size)
        assertTrue(ui.accessibilityText.contains("Codex · 1周"))
        assertTrue(ui.accessibilityText.contains("Other · 1小时"))
    }

    @Test
    fun `reset uses local absolute time and freshness uses injected clock`() {
        val ui = map(
            generatedAt = Instant.parse("2026-07-19T08:30:00Z"),
            buckets = listOf(
                bucket("codex", "Codex", 10_080, 81.0, "2026-07-25T00:00:00Z"),
            ),
        )

        assertEquals("7月25日 08:00 重置", ui.primaryBucket?.resetText)
        assertEquals("30分钟前", ui.freshnessText)
    }

    @Test
    fun `cached offline data stays visible and is labelled independently`() {
        val ui = map(
            cacheState = WidgetCacheState.CACHED,
            failure = WidgetFailureKind.OFFLINE,
            buckets = listOf(bucket("codex", "Codex", 60, 60.0)),
        )

        assertFalse(ui.isEmpty)
        assertEquals("离线，显示缓存", ui.statusText)
        assertTrue(ui.freshnessText.startsWith("缓存 · "))
        assertEquals(WidgetStatusTone.WARNING, ui.statusTone)
    }

    @Test
    fun `collector stale is not confused with phone transport failure`() {
        val ui = map(
            collectorStale = true,
            buckets = listOf(bucket("codex", "Codex", 60, 60.0)),
        )

        assertEquals("部分来源使用上次成功值", ui.statusText)
        assertTrue(ui.freshnessText.startsWith("来源陈旧 · "))
        assertEquals(WidgetStatusTone.WARNING, ui.statusTone)
    }

    @Test
    fun `collector stale and offline phone cache remain visible together`() {
        val ui = map(
            collectorStale = true,
            cacheState = WidgetCacheState.CACHED,
            failure = WidgetFailureKind.OFFLINE,
            buckets = listOf(bucket("codex", "Codex", 60, 60.0)),
        )

        assertEquals(
            "离线，显示缓存 · 部分来源使用上次成功值",
            ui.statusText,
        )
        assertTrue(ui.freshnessText.startsWith("缓存 · 来源陈旧 · "))
        assertTrue(ui.accessibilityText.contains("离线，显示缓存"))
        assertTrue(ui.accessibilityText.contains("部分来源使用上次成功值"))
    }

    @Test
    fun `gate closed without cache is a safe empty state`() {
        val ui = WidgetUiMapper.map(
            input = WidgetMapperInput(
                snapshot = null,
                phoneCacheState = WidgetCacheState.EMPTY,
                lastFailure = WidgetFailureKind.GATE_CLOSED,
                isRefreshing = false,
            ),
            now = now,
            zoneId = zone,
        )

        assertTrue(ui.isEmpty)
        assertNull(ui.primaryBucket)
        assertEquals("认证传输尚未开放", ui.statusText)
        assertFalse(ui.accessibilityText.contains("http"))
        assertFalse(ui.accessibilityText.contains("Bearer"))
    }

    @Test
    fun `refreshing state has explicit visual and accessibility text`() {
        val ui = map(
            refreshing = true,
            buckets = listOf(bucket("codex", "Codex", 60, 60.0)),
        )

        assertTrue(ui.isRefreshing)
        assertEquals("正在刷新…", ui.statusText)
        assertTrue(ui.accessibilityText.contains("正在刷新"))
    }

    @Test
    fun `unsafe label is replaced before display`() {
        val ui = map(
            buckets = listOf(
                bucket("codex", "/Users/example/private", 60, 60.0),
            ),
        )

        assertEquals("配额 · 1小时", ui.primaryBucket?.title)
        assertFalse(ui.accessibilityText.contains("/Users"))
    }

    @Test
    fun `today activity excludes reasoning double count and formats compactly`() {
        val ui = map(
            activity = WidgetActivityInput(
                newInputTokens = 4_200,
                cachedInputTokens = 2_100,
                outputTokens = 1_600,
                reasoningTokens = 700,
            ),
            buckets = listOf(bucket("codex", "Codex", 60, 60.0)),
        )

        assertEquals("今日本地活动 7.9K Tokens", ui.todayActivityText)
    }

    @Test
    fun `valid snapshot without quota remains renderable and shows zero activity`() {
        val ui = map(buckets = emptyList())

        assertFalse(ui.isEmpty)
        assertNull(ui.primaryBucket)
        assertEquals("暂无配额桶", ui.noQuotaText)
        assertEquals("今日本地活动 0 Tokens", ui.todayActivityText)
    }

    private fun map(
        generatedAt: Instant = Instant.parse("2026-07-19T08:59:30Z"),
        collectorStale: Boolean = false,
        cacheState: WidgetCacheState = WidgetCacheState.FRESH,
        failure: WidgetFailureKind? = null,
        refreshing: Boolean = false,
        activity: WidgetActivityInput = WidgetActivityInput(0, 0, 0, 0),
        buckets: List<WidgetBucketInput>,
    ): WidgetUiModel = WidgetUiMapper.map(
        input = WidgetMapperInput(
            snapshot = WidgetSnapshotInput(
                generatedAt = generatedAt,
                collectorStale = collectorStale,
                buckets = buckets,
                todayActivity = activity,
            ),
            phoneCacheState = cacheState,
            lastFailure = failure,
            isRefreshing = refreshing,
        ),
        now = now,
        zoneId = zone,
        locale = Locale.SIMPLIFIED_CHINESE,
    )

    private fun bucket(
        id: String,
        name: String?,
        minutes: Long,
        remaining: Double,
        resetsAt: String = "2026-07-20T00:00:00Z",
    ) = WidgetBucketInput(
        limitId = id,
        limitName = name,
        windowMinutes = minutes,
        remainingPercent = remaining,
        resetsAt = Instant.parse(resetsAt),
    )
}
