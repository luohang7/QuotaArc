package dev.quotaarc.android.data.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

interface ConnectionStore {
    suspend fun read(): DeviceConnection?

    /**
     * Reads metadata and encrypted credentials from one authoritative DataStore
     * snapshot. Implementations that cannot distinguish an unavailable
     * credential key may use this safe default.
     */
    suspend fun readForRestore(): StoredConnectionRestoreResult =
        read()?.let(StoredConnectionRestoreResult::Ready)
            ?: StoredConnectionRestoreResult.Absent

    /**
     * Atomically replaces metadata and encrypted credential after encryption
     * succeeds. A failed encryption or Preferences transaction leaves the old
     * connection intact.
     */
    suspend fun replace(connection: DeviceConnection)

    suspend fun clear()
}

sealed interface StoredConnectionRestoreResult {
    data object Absent : StoredConnectionRestoreResult

    data class Ready(
        val connection: DeviceConnection,
    ) : StoredConnectionRestoreResult

    /**
     * Metadata was decoded from the authoritative atomic document, but the
     * Android Keystore key is explicitly missing or permanently invalidated.
     */
    data class CredentialUnavailable(
        val metadata: DeviceConnectionMetadata,
    ) : StoredConnectionRestoreResult
}

enum class ConnectionStoreFailureKind {
    MALFORMED_DOCUMENT,
    UNSUPPORTED_DOCUMENT_VERSION,
    INVALID_METADATA,
    INVALID_ENCRYPTED_CREDENTIAL,
    ENCRYPTION_FAILED,
    CREDENTIAL_KEY_UNAVAILABLE,
    DECRYPTION_FAILED,
    INVALID_DECRYPTED_CREDENTIAL,
}

class ConnectionStoreException(
    val kind: ConnectionStoreFailureKind,
    cause: Throwable? = null,
) : IllegalStateException("Invalid stored device connection: ${kind.name}", cause)

class DataStoreConnectionStore(
    private val dataStore: DataStore<Preferences>,
    private val credentialCipher: CredentialCipher,
) : ConnectionStore {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
    }

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
        val encoded = dataStore.data.first()[DOCUMENT_KEY]
            ?: return StoredConnectionRestoreResult.Absent
        val document = decodeDocument(encoded)
        val metadata = decodeMetadata(document.metadata)
        val aad = encodeMetadata(document.metadata)
        val encryptedCredential = try {
            EncryptedCredential(
                iv = decodeBase64Url(document.encryptedDeviceToken.iv),
                ciphertext = decodeBase64Url(document.encryptedDeviceToken.ciphertext),
            )
        } catch (error: IllegalArgumentException) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.INVALID_ENCRYPTED_CREDENTIAL,
                error,
            )
        }
        val plaintext = try {
            credentialCipher.decrypt(encryptedCredential, aad)
        } catch (error: CancellationException) {
            throw error
        } catch (_: CredentialKeyUnavailableException) {
            return StoredConnectionRestoreResult.CredentialUnavailable(metadata)
        } catch (error: Exception) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.DECRYPTION_FAILED,
                error,
            )
        }
        try {
            val token = decodeUtf8(plaintext)
            val credential = try {
                DeviceCredential.parse(token)
            } catch (error: IllegalArgumentException) {
                throw ConnectionStoreException(
                    ConnectionStoreFailureKind.INVALID_DECRYPTED_CREDENTIAL,
                    error,
                )
            }
            if (credential.deviceId != metadata.deviceId) {
                throw ConnectionStoreException(
                    ConnectionStoreFailureKind.INVALID_DECRYPTED_CREDENTIAL,
                )
            }
            return StoredConnectionRestoreResult.Ready(
                DeviceConnection(metadata = metadata, credential = credential),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun replace(connection: DeviceConnection) {
        val storedMetadata = connection.metadata.toStored()
        val aad = encodeMetadata(storedMetadata)
        val plaintext = connection.credential.deviceToken.toByteArray(StandardCharsets.UTF_8)
        val encryptedCredential = try {
            try {
                credentialCipher.encrypt(plaintext, aad)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ConnectionStoreException(
                    ConnectionStoreFailureKind.ENCRYPTION_FAILED,
                    error,
                )
            }
        } finally {
            plaintext.fill(0)
        }
        val document = StoredConnectionDocument(
            documentVersion = DOCUMENT_VERSION,
            metadata = storedMetadata,
            encryptedDeviceToken = StoredEncryptedCredential(
                algorithm = ENCRYPTION_ALGORITHM,
                iv = encodeBase64Url(encryptedCredential.iv),
                ciphertext = encodeBase64Url(encryptedCredential.ciphertext),
            ),
        )
        val encoded = json.encodeToString(document)
        dataStore.edit { preferences ->
            preferences[DOCUMENT_KEY] = encoded
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(DOCUMENT_KEY)
        }
    }

    private fun decodeDocument(encoded: String): StoredConnectionDocument {
        val document = try {
            json.decodeFromString<StoredConnectionDocument>(encoded)
        } catch (error: SerializationException) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.MALFORMED_DOCUMENT,
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.MALFORMED_DOCUMENT,
                error,
            )
        }
        if (document.documentVersion != DOCUMENT_VERSION) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.UNSUPPORTED_DOCUMENT_VERSION,
            )
        }
        if (document.encryptedDeviceToken.algorithm != ENCRYPTION_ALGORITHM) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.INVALID_ENCRYPTED_CREDENTIAL,
            )
        }
        return document
    }

    private fun decodeMetadata(stored: StoredConnectionMetadata): DeviceConnectionMetadata {
        val endpoint = DeviceConnectionRules.normalizeEndpoint(stored.endpoint)
        if (endpoint == null || endpoint != stored.endpoint) {
            throw ConnectionStoreException(ConnectionStoreFailureKind.INVALID_METADATA)
        }
        if (!DeviceConnectionRules.isCollectorId(stored.collectorId) ||
            !DeviceConnectionRules.isCertificateSha256(stored.certificateSha256) ||
            !DeviceConnectionRules.isDeviceId(stored.deviceId)
        ) {
            throw ConnectionStoreException(ConnectionStoreFailureKind.INVALID_METADATA)
        }
        if (stored.scopes.isEmpty() || stored.scopes.toSet().size != stored.scopes.size) {
            throw ConnectionStoreException(ConnectionStoreFailureKind.INVALID_METADATA)
        }
        val scopes = stored.scopes.mapTo(linkedSetOf()) { value ->
            DeviceCapability.fromWireName(value)
                ?: throw ConnectionStoreException(
                    ConnectionStoreFailureKind.INVALID_METADATA,
                )
        }
        if (DeviceCapability.SUMMARY_READ !in scopes) {
            throw ConnectionStoreException(ConnectionStoreFailureKind.INVALID_METADATA)
        }
        return try {
            DeviceConnectionMetadata(
                endpoint = endpoint,
                collectorId = stored.collectorId,
                certificateSha256 = stored.certificateSha256,
                deviceId = stored.deviceId,
                scopes = scopes,
            )
        } catch (error: IllegalArgumentException) {
            throw ConnectionStoreException(
                ConnectionStoreFailureKind.INVALID_METADATA,
                error,
            )
        }
    }

    private fun encodeMetadata(metadata: StoredConnectionMetadata): ByteArray =
        json.encodeToString(metadata).toByteArray(StandardCharsets.UTF_8)

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw ConnectionStoreException(
            ConnectionStoreFailureKind.INVALID_DECRYPTED_CREDENTIAL,
            error,
        )
    }

    private companion object {
        const val DOCUMENT_VERSION = 1
        const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
        val DOCUMENT_KEY = stringPreferencesKey("device_connection_document_v1")
        val BASE64_URL = Regex("""^[A-Za-z0-9_-]+$""")

        fun encodeBase64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun decodeBase64Url(value: String): ByteArray {
            require(BASE64_URL.matches(value)) { "Invalid Base64URL encoding" }
            val decoded = Base64.getUrlDecoder().decode(value)
            require(encodeBase64Url(decoded) == value) {
                "Non-canonical Base64URL encoding"
            }
            return decoded
        }
    }
}

private fun DeviceConnectionMetadata.toStored(): StoredConnectionMetadata {
    val normalizedEndpoint = requireNotNull(DeviceConnectionRules.normalizeEndpoint(endpoint)) {
        "Endpoint must be an HTTPS origin"
    }
    return StoredConnectionMetadata(
        endpoint = normalizedEndpoint,
        collectorId = collectorId,
        certificateSha256 = certificateSha256,
        deviceId = deviceId,
        scopes = scopes.map(DeviceCapability::wireName).sorted(),
    )
}

@Serializable
private data class StoredConnectionDocument(
    val documentVersion: Int,
    val metadata: StoredConnectionMetadata,
    val encryptedDeviceToken: StoredEncryptedCredential,
)

@Serializable
private data class StoredConnectionMetadata(
    val endpoint: String,
    val collectorId: String,
    val certificateSha256: String,
    val deviceId: String,
    val scopes: List<String>,
)

@Serializable
private data class StoredEncryptedCredential(
    val algorithm: String,
    val iv: String,
    val ciphertext: String,
)
