package dev.quotaarc.android.data.connection

import android.annotation.SuppressLint
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Adds a non-secret synchronous index beside the encrypted atomic document.
 * The index is written only after [delegate] commits successfully and is used
 * solely to decide whether background work should be scheduled; it is never
 * sufficient to construct an authenticated client.
 */
@SuppressLint("ApplySharedPref")
internal class MetadataIndexedConnectionStore(
    private val delegate: ConnectionStore,
    private val metadataPreferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionStore {
    override suspend fun read(): DeviceConnection? =
        when (val restored = readForRestore()) {
            StoredConnectionRestoreResult.Absent -> null
            is StoredConnectionRestoreResult.Ready -> restored.connection
            is StoredConnectionRestoreResult.CredentialUnavailable ->
                throw ConnectionStoreException(
                    ConnectionStoreFailureKind.CREDENTIAL_KEY_UNAVAILABLE,
                )
        }

    override suspend fun readForRestore(): StoredConnectionRestoreResult {
        val restored = delegate.readForRestore()
        repairMetadataIndex(
            when (restored) {
                StoredConnectionRestoreResult.Absent -> null
                is StoredConnectionRestoreResult.Ready ->
                    restored.connection.metadata
                is StoredConnectionRestoreResult.CredentialUnavailable ->
                    restored.metadata
            },
        )
        return restored
    }

    override suspend fun replace(connection: DeviceConnection) {
        delegate.replace(connection)
        // This is a derived scheduling index. The encrypted DataStore document
        // above is the authoritative atomic metadata+credential commit. Once
        // that succeeds, index failure must not turn a committed save into a
        // reported failure or keep the previous in-memory repository active.
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                runCatching { writeMetadataIndex(connection) }
            }
        }
    }

    private fun writeMetadataIndex(connection: DeviceConnection) =
        writeMetadataIndex(connection.metadata)

    private fun writeMetadataIndex(metadata: DeviceConnectionMetadata) {
        metadataPreferences.edit()
            .clear()
            .putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
            .putString(KEY_ENDPOINT, metadata.endpoint)
            .putString(KEY_COLLECTOR_ID, metadata.collectorId)
            .putString(KEY_CERTIFICATE_SHA256, metadata.certificateSha256)
            .putString(KEY_DEVICE_ID, metadata.deviceId)
            .putStringSet(
                KEY_SCOPES,
                metadata.scopes.mapTo(linkedSetOf(), DeviceCapability::wireName),
            )
            .commit()
    }

    override suspend fun clear() {
        delegate.clear()
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                runCatching { metadataPreferences.edit().clear().commit() }
            }
        }
    }

    private suspend fun repairMetadataIndex(metadata: DeviceConnectionMetadata?) {
        try {
            withContext(ioDispatcher) {
                if (metadata == null) {
                    metadataPreferences.edit().clear().commit()
                } else {
                    writeMetadataIndex(metadata)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The encrypted document remains authoritative. A later process
            // restore or successful save retries this derived index repair.
        }
    }

    companion object {
        fun hasCommittedMetadata(preferences: SharedPreferences): Boolean {
            if (preferences.getInt(KEY_FORMAT_VERSION, 0) != FORMAT_VERSION) {
                return false
            }
            val endpoint = preferences.getString(KEY_ENDPOINT, null) ?: return false
            val collectorId =
                preferences.getString(KEY_COLLECTOR_ID, null) ?: return false
            val certificate =
                preferences.getString(KEY_CERTIFICATE_SHA256, null) ?: return false
            val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return false
            val scopes = preferences.getStringSet(KEY_SCOPES, null) ?: return false
            return DeviceConnectionRules.normalizeEndpoint(endpoint) == endpoint &&
                DeviceConnectionRules.isCollectorId(collectorId) &&
                DeviceConnectionRules.isCertificateSha256(certificate) &&
                DeviceConnectionRules.isDeviceId(deviceId) &&
                DeviceCapability.SUMMARY_READ.wireName in scopes &&
                scopes.all { DeviceCapability.fromWireName(it) != null }
        }

        private const val FORMAT_VERSION = 1
        private const val KEY_FORMAT_VERSION = "format_version"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_COLLECTOR_ID = "collector_id"
        private const val KEY_CERTIFICATE_SHA256 = "certificate_sha256"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SCOPES = "scopes"
    }
}
