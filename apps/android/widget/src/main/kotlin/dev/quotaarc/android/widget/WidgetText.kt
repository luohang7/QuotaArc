package dev.quotaarc.android.widget

import android.content.Context

internal interface WidgetText {
    val title: String
    val refresh: String
    val refreshingShort: String
    val refreshAccessibility: String
    val refreshingAccessibility: String
    val noQuotaBuckets: String
    val noTodayActivity: String
    val readingCache: String
    val gateClosedEmpty: String
    val notConfiguredEmpty: String
    val authenticationEmpty: String
    val offlineEmpty: String
    val securityEmpty: String
    val incompatibleEmpty: String
    val noCache: String
    val waitFirstSuccess: String
    val refreshingStatus: String
    val gateClosedCached: String
    val notConfiguredCached: String
    val authenticationCached: String
    val offlineCached: String
    val securityCached: String
    val incompatibleCached: String
    val unavailableCached: String
    val sourceLastGood: String
    val quotaFallback: String
    val justUpdated: String
    val resetDatePattern: String

    fun available(percent: Int): String
    fun resetAt(value: String): String
    fun todayActivity(value: String): String
    fun minutesAgo(value: Long): String
    fun hoursAgo(value: Long): String
    fun daysAgo(value: Long): String
    fun cached(value: String): String
    fun sourceStale(value: String): String
    fun weeks(value: Long): String
    fun days(value: Long): String
    fun hours(value: Long): String
    fun minutes(value: Long): String
}

internal class AndroidWidgetText(
    private val context: Context,
) : WidgetText {
    override val title get() = string(R.string.widget_name)
    override val refresh get() = string(R.string.widget_refresh)
    override val refreshingShort get() = string(R.string.widget_refreshing_short)
    override val refreshAccessibility get() =
        string(R.string.widget_refresh_accessibility)
    override val refreshingAccessibility get() =
        string(R.string.widget_refreshing_accessibility)
    override val noQuotaBuckets get() = string(R.string.widget_no_quota_buckets)
    override val noTodayActivity get() = string(R.string.widget_no_today_activity)
    override val readingCache get() = string(R.string.widget_reading_cache)
    override val gateClosedEmpty get() = string(R.string.widget_gate_closed_empty)
    override val notConfiguredEmpty get() =
        string(R.string.widget_not_configured_empty)
    override val authenticationEmpty get() =
        string(R.string.widget_authentication_empty)
    override val offlineEmpty get() = string(R.string.widget_offline_empty)
    override val securityEmpty get() = string(R.string.widget_security_empty)
    override val incompatibleEmpty get() =
        string(R.string.widget_incompatible_empty)
    override val noCache get() = string(R.string.widget_no_cache)
    override val waitFirstSuccess get() = string(R.string.widget_wait_first_success)
    override val refreshingStatus get() = string(R.string.widget_refreshing_status)
    override val gateClosedCached get() = string(R.string.widget_gate_closed_cached)
    override val notConfiguredCached get() =
        string(R.string.widget_not_configured_cached)
    override val authenticationCached get() =
        string(R.string.widget_authentication_cached)
    override val offlineCached get() = string(R.string.widget_offline_cached)
    override val securityCached get() = string(R.string.widget_security_cached)
    override val incompatibleCached get() =
        string(R.string.widget_incompatible_cached)
    override val unavailableCached get() =
        string(R.string.widget_unavailable_cached)
    override val sourceLastGood get() = string(R.string.widget_source_last_good)
    override val quotaFallback get() = string(R.string.widget_quota_fallback)
    override val justUpdated get() = string(R.string.widget_just_updated)
    override val resetDatePattern get() = string(R.string.widget_reset_date_pattern)

    override fun available(percent: Int) =
        string(R.string.widget_available_format, percent)
    override fun resetAt(value: String) =
        string(R.string.widget_reset_format, value)
    override fun todayActivity(value: String) =
        string(R.string.widget_today_activity_format, value)
    override fun minutesAgo(value: Long) =
        string(R.string.widget_minutes_ago_format, value)
    override fun hoursAgo(value: Long) =
        string(R.string.widget_hours_ago_format, value)
    override fun daysAgo(value: Long) =
        string(R.string.widget_days_ago_format, value)
    override fun cached(value: String) =
        string(R.string.widget_cached_format, value)
    override fun sourceStale(value: String) =
        string(R.string.widget_source_stale_format, value)
    override fun weeks(value: Long) =
        string(R.string.widget_window_weeks_format, value)
    override fun days(value: Long) =
        string(R.string.widget_window_days_format, value)
    override fun hours(value: Long) =
        string(R.string.widget_window_hours_format, value)
    override fun minutes(value: Long) =
        string(R.string.widget_window_minutes_format, value)

    private fun string(id: Int, vararg values: Any): String =
        context.getString(id, *values)
}

internal object ZhHansWidgetText : WidgetText {
    override val title = "QuotaArc"
    override val refresh = "刷新"
    override val refreshingShort = "刷新中"
    override val refreshAccessibility = "刷新 QuotaArc 配额和用量"
    override val refreshingAccessibility = "QuotaArc 正在刷新"
    override val noQuotaBuckets = "暂无配额桶"
    override val noTodayActivity = "今日暂无本地活动"
    override val readingCache = "正在读取缓存…"
    override val gateClosedEmpty = "认证传输尚未开放"
    override val notConfiguredEmpty = "尚未连接 Collector"
    override val authenticationEmpty = "需要重新认证"
    override val offlineEmpty = "离线，暂无缓存"
    override val securityEmpty = "安全校验失败"
    override val incompatibleEmpty = "数据版本不兼容"
    override val noCache = "暂无缓存数据"
    override val waitFirstSuccess = "等待首次成功同步"
    override val refreshingStatus = "正在刷新…"
    override val gateClosedCached = "连接门禁关闭，显示缓存"
    override val notConfiguredCached = "未连接，显示缓存"
    override val authenticationCached = "认证失效，显示缓存"
    override val offlineCached = "离线，显示缓存"
    override val securityCached = "安全校验失败，显示缓存"
    override val incompatibleCached = "版本不兼容，显示缓存"
    override val unavailableCached = "同步失败，显示缓存"
    override val sourceLastGood = "部分来源使用上次成功值"
    override val quotaFallback = "配额"
    override val justUpdated = "刚刚更新"
    override val resetDatePattern = "M月d日 HH:mm"

    override fun available(percent: Int) = "$percent% 可用"
    override fun resetAt(value: String) = "$value 重置"
    override fun todayActivity(value: String) = "今日本地活动 $value Tokens"
    override fun minutesAgo(value: Long) = "${value}分钟前"
    override fun hoursAgo(value: Long) = "${value}小时前"
    override fun daysAgo(value: Long) = "${value}天前"
    override fun cached(value: String) = "缓存 · $value"
    override fun sourceStale(value: String) = "来源陈旧 · $value"
    override fun weeks(value: Long) = "${value}周"
    override fun days(value: Long) = "${value}天"
    override fun hours(value: Long) = "${value}小时"
    override fun minutes(value: Long) = "${value}分钟"
}
