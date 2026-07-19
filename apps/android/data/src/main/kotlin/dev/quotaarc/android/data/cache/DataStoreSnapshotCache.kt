package dev.quotaarc.android.data.cache

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DataStoreSnapshotCache(
    private val dataStore: DataStore<Preferences>,
) : SnapshotCacheStore {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    override fun observe(): Flow<SnapshotCacheEnvelope> =
        dataStore.data.map(::decode)

    override suspend fun read(): SnapshotCacheEnvelope = decode(dataStore.data.first())

    override suspend fun update(
        transform: (SnapshotCacheEnvelope) -> SnapshotCacheEnvelope,
    ): SnapshotCacheEnvelope {
        var committed: SnapshotCacheEnvelope? = null
        dataStore.edit { preferences ->
            val updated = transform(decode(preferences)).copy(
                cacheFormatVersion = SnapshotCacheEnvelope.CACHE_FORMAT_VERSION,
            )
            preferences[ENVELOPE_KEY] = json.encodeToString(updated)
            committed = updated
        }
        return checkNotNull(committed)
    }

    private fun decode(preferences: Preferences): SnapshotCacheEnvelope {
        val encoded = preferences[ENVELOPE_KEY] ?: return SnapshotCacheEnvelope.EMPTY
        val envelope = try {
            json.decodeFromString<SnapshotCacheEnvelope>(encoded)
        } catch (_: SerializationException) {
            return SnapshotCacheEnvelope.EMPTY
        } catch (_: IllegalArgumentException) {
            return SnapshotCacheEnvelope.EMPTY
        }
        return if (envelope.cacheFormatVersion == SnapshotCacheEnvelope.CACHE_FORMAT_VERSION) {
            envelope
        } else {
            SnapshotCacheEnvelope.EMPTY
        }
    }

    private companion object {
        val ENVELOPE_KEY = stringPreferencesKey("snapshot_cache_envelope_v1")
    }
}
