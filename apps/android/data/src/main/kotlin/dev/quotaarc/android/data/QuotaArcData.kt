package dev.quotaarc.android.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.quotaarc.android.data.api.DisabledQuotaArcDeviceApi
import dev.quotaarc.android.data.cache.DataStoreSnapshotCache
import dev.quotaarc.android.data.repository.DefaultQuotaArcRepository
import dev.quotaarc.android.data.repository.QuotaArcRepository
import kotlinx.coroutines.CoroutineScope
import java.time.Duration

enum class DeviceTransportGate {
    CLOSED,
}

object QuotaArcData {
    val transportGate: DeviceTransportGate = DeviceTransportGate.CLOSED

    @Volatile
    private var gateClosedRepository: QuotaArcRepository? = null

    /**
     * Creates the sole process-local repository for the current release.
     *
     * It persists last-good sanitized snapshots, but its transport is
     * deliberately disabled. No endpoint or credential can be partially saved
     * while the authenticated device API gate is closed.
     */
    fun createGateClosedRepository(
        context: Context,
        scope: CoroutineScope,
        staleAfter: Duration = Duration.ofMinutes(60),
    ): QuotaArcRepository =
        gateClosedRepository ?: synchronized(this) {
            gateClosedRepository ?: DefaultQuotaArcRepository(
                api = DisabledQuotaArcDeviceApi,
                cache = DataStoreSnapshotCache(
                    PreferenceDataStoreFactory.create(
                        scope = scope,
                        produceFile = {
                            context.applicationContext.preferencesDataStoreFile(CACHE_NAME)
                        },
                    ),
                ),
                parentScope = scope,
                staleAfter = staleAfter,
            ).also { gateClosedRepository = it }
        }

    private const val CACHE_NAME = "quotaarc_snapshot_cache"
}
