package dev.quotaarc.android.data.repository

import java.time.Instant

fun interface QuotaArcClock {
    fun now(): Instant
}

internal object SystemQuotaArcClock : QuotaArcClock {
    override fun now(): Instant = Instant.now()
}
