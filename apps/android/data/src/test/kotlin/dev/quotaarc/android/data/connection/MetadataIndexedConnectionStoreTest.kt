package dev.quotaarc.android.data.connection

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataIndexedConnectionStoreTest {
    @Test
    fun `stale failed index write cannot replace authoritative restore identity`() = runTest {
        val collectorA = metadata("qac_aaaaaaaaaaaaaaaaaaaaaa")
        val collectorB = metadata("qac_bbbbbbbbbbbbbbbbbbbbbb")
        val preferences = InMemorySharedPreferences()
        writeIndex(preferences, collectorA)
        preferences.failCommits = true
        val store = MetadataIndexedConnectionStore(
            delegate = RestoreOnlyConnectionStore(
                StoredConnectionRestoreResult.CredentialUnavailable(collectorB),
            ),
            metadataPreferences = preferences,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val restored = store.readForRestore()

        assertEquals(
            collectorB,
            (restored as StoredConnectionRestoreResult.CredentialUnavailable).metadata,
        )
        assertEquals(collectorA.collectorId, preferences.getString("collector_id", null))
    }

    @Test
    fun `missing index is repaired from authoritative credential unavailable metadata`() =
        runTest {
            val collectorB = metadata("qac_bbbbbbbbbbbbbbbbbbbbbb")
            val preferences = InMemorySharedPreferences()
            val store = MetadataIndexedConnectionStore(
                delegate = RestoreOnlyConnectionStore(
                    StoredConnectionRestoreResult.CredentialUnavailable(collectorB),
                ),
                metadataPreferences = preferences,
                ioDispatcher = Dispatchers.Unconfined,
            )

            val restored = store.readForRestore()

            assertEquals(
                collectorB,
                (restored as StoredConnectionRestoreResult.CredentialUnavailable).metadata,
            )
            assertTrue(MetadataIndexedConnectionStore.hasCommittedMetadata(preferences))
            assertEquals(collectorB.collectorId, preferences.getString("collector_id", null))
        }

    private fun metadata(collectorId: String) = DeviceConnectionMetadata(
        endpoint = "https://collector.example:8443",
        collectorId = collectorId,
        certificateSha256 = "A".repeat(64),
        deviceId = "abcdefghijklmnop",
        scopes = setOf(
            DeviceCapability.SUMMARY_READ,
            DeviceCapability.REFRESH_WRITE,
        ),
    )

    private fun writeIndex(
        preferences: SharedPreferences,
        metadata: DeviceConnectionMetadata,
    ) {
        assertTrue(
            preferences.edit()
                .putInt("format_version", 1)
                .putString("endpoint", metadata.endpoint)
                .putString("collector_id", metadata.collectorId)
                .putString("certificate_sha256", metadata.certificateSha256)
                .putString("device_id", metadata.deviceId)
                .putStringSet(
                    "scopes",
                    metadata.scopes.mapTo(linkedSetOf(), DeviceCapability::wireName),
                )
                .commit(),
        )
        assertTrue(MetadataIndexedConnectionStore.hasCommittedMetadata(preferences))
    }
}

private class RestoreOnlyConnectionStore(
    private val restored: StoredConnectionRestoreResult,
) : ConnectionStore {
    override suspend fun read(): DeviceConnection? = error("read must not be used")

    override suspend fun readForRestore(): StoredConnectionRestoreResult = restored

    override suspend fun replace(connection: DeviceConnection) = error("not used")

    override suspend fun clear() = error("not used")
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()
    var failCommits = false

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = (values[key] as? Set<String>)?.toSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removed = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            stage(key, value)

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ): SharedPreferences.Editor = stage(key, values?.toSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            stage(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            stage(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            stage(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            stage(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            pending.remove(key)
            removed += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            pending.clear()
            removed.clear()
            return this
        }

        override fun commit(): Boolean {
            if (failCommits) return false
            applyChanges()
            return true
        }

        override fun apply() {
            if (!failCommits) applyChanges()
        }

        private fun stage(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            removed.remove(key)
            pending[key] = value
            return this
        }

        private fun applyChanges() {
            if (clear) values.clear()
            removed.forEach(values::remove)
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}
