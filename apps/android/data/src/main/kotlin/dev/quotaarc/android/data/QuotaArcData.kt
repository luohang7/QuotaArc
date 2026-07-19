package dev.quotaarc.android.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.quotaarc.android.data.api.DisabledQuotaArcDeviceApi
import dev.quotaarc.android.data.api.DisabledConnectionReason
import dev.quotaarc.android.data.cache.DataStoreSnapshotCache
import dev.quotaarc.android.data.cache.ReadOnlySnapshotCacheStore
import dev.quotaarc.android.data.connection.AndroidKeystoreCredentialCipher
import dev.quotaarc.android.data.connection.ConnectedRepositoryFactory
import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import dev.quotaarc.android.data.connection.DataStoreConnectionStore
import dev.quotaarc.android.data.connection.DeviceApiFactory
import dev.quotaarc.android.data.connection.MetadataIndexedConnectionStore
import dev.quotaarc.android.data.connection.QuotaArcConnectionManager
import dev.quotaarc.android.data.connection.UnavailableRepositoryFactory
import dev.quotaarc.android.data.connection.productionDeviceApi
import dev.quotaarc.android.data.repository.CollectorIdentity
import dev.quotaarc.android.data.repository.DefaultQuotaArcRepository
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.SwitchingQuotaArcRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.time.Duration

object QuotaArcData {
    @Volatile
    private var connectionManager: QuotaArcConnectionManager? = null

    /**
     * Fast scheduler hint used only before authoritative restore completes. It
     * reads a non-secret metadata index and never chooses UI or cache identity.
     */
    fun hasConnectionMetadata(context: Context): Boolean =
        MetadataIndexedConnectionStore.hasCommittedMetadata(
            metadataPreferences(context.applicationContext),
        )

    /**
     * Process-local authoritative result from the atomic DataStore document.
     * Null means restore has not completed; callers may then use the non-secret
     * metadata index only as a temporary scheduling hint.
     */
    fun authoritativeRestoreResult(): ConnectionRestoreResult? =
        connectionManager?.restoreState?.value

    /**
     * Returns a stable repository handle restored from encrypted credentials.
     * A missing connection or failed credential decryption remains fail-closed
     * through a reasoned Disabled API, never the obsolete transport gate.
     */
    fun createActiveRepository(
        context: Context,
        scope: CoroutineScope,
        staleAfter: Duration = Duration.ofMinutes(60),
    ): QuotaArcRepository =
        createConnectionManager(context, scope, staleAfter).repository

    fun createConnectionManager(
        context: Context,
        scope: CoroutineScope,
        staleAfter: Duration = Duration.ofMinutes(60),
    ): QuotaArcConnectionManager =
        connectionManager ?: synchronized(this) {
            connectionManager ?: buildConnectionManager(
                context = context.applicationContext,
                scope = scope,
                staleAfter = staleAfter,
            ).also { connectionManager = it }
        }

    private fun buildConnectionManager(
        context: Context,
        scope: CoroutineScope,
        staleAfter: Duration,
    ): QuotaArcConnectionManager {
        val snapshotCache = DataStoreSnapshotCache(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { context.preferencesDataStoreFile(CACHE_NAME) },
            ),
        )
        // No derived index may choose a cache identity. Until the authoritative
        // DataStore document has been read, this neutral identity cannot match a
        // real Collector's last-good envelope.
        val restorePendingRepository = DefaultQuotaArcRepository(
            api = DisabledQuotaArcDeviceApi.forConnectionFailure(
                DisabledConnectionReason.NOT_CONFIGURED,
            ),
            cache = ReadOnlySnapshotCacheStore(snapshotCache),
            collectorIdentity = CollectorIdentity.fromStableId(
                "connection-restore-pending",
            ),
            parentScope = scope,
            staleAfter = staleAfter,
        )
        val switching = SwitchingQuotaArcRepository(restorePendingRepository)
        val encryptedStore = DataStoreConnectionStore(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = {
                    context.preferencesDataStoreFile(CONNECTION_STORE_NAME)
                },
            ),
            credentialCipher = AndroidKeystoreCredentialCipher(),
        )
        val store = MetadataIndexedConnectionStore(
            delegate = encryptedStore,
            metadataPreferences = metadataPreferences(context),
        )
        val manager = QuotaArcConnectionManager(
            store = store,
            switchingRepository = switching,
            apiFactory = DeviceApiFactory(::productionDeviceApi),
            repositoryFactory = ConnectedRepositoryFactory { connection, api ->
                DefaultQuotaArcRepository(
                    api = api,
                    cache = snapshotCache,
                    collectorIdentity =
                        CollectorIdentity.fromStableId(connection.metadata.collectorId),
                    parentScope = scope,
                    staleAfter = staleAfter,
                )
            },
            unavailableRepositoryFactory =
                UnavailableRepositoryFactory { metadata ->
                    DefaultQuotaArcRepository(
                        api = DisabledQuotaArcDeviceApi.forConnectionFailure(
                            DisabledConnectionReason.CREDENTIAL_UNAVAILABLE,
                        ),
                        cache = ReadOnlySnapshotCacheStore(snapshotCache),
                        collectorIdentity =
                            CollectorIdentity.fromStableId(metadata.collectorId),
                        parentScope = scope,
                        staleAfter = staleAfter,
                    )
                },
        )
        // App and widget startup stay non-blocking. The stable switching
        // repository initially serves only the identity-bound read-only cache,
        // then activates authenticated transport after Keystore restore.
        val initialRestore = scope.async(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            manager.restore()
        }
        manager.attachInitialRestore(initialRestore)
        return manager
    }

    private fun metadataPreferences(context: Context) =
        context.getSharedPreferences(
            CONNECTION_METADATA_NAME,
            Context.MODE_PRIVATE,
        )

    private const val CACHE_NAME = "quotaarc_snapshot_cache"
    private const val CONNECTION_STORE_NAME = "quotaarc_device_connection"
    private const val CONNECTION_METADATA_NAME = "quotaarc_connection_metadata"
}
