package dev.quotaarc.android.data.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreConnectionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun `replace stores one encrypted document and round trips connection`() = runTest {
        val dataStore = createDataStore(backgroundScope)
        val store = DataStoreConnectionStore(dataStore, TestAesGcmCredentialCipher())
        val connection = connection()

        store.replace(connection)

        val persistedValues = dataStore.data.first().asMap().values
        assertEquals(1, persistedValues.size)
        val persistedDocument = persistedValues.single() as String
        assertFalse(persistedDocument.contains(DEVICE_TOKEN))
        assertFalse(persistedDocument.contains(DEVICE_SECRET))
        assertEquals(connection, store.read())

        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `failed encryption leaves prior connection atomically intact`() = runTest {
        val dataStore = createDataStore(backgroundScope)
        val cipher = TestAesGcmCredentialCipher()
        val store = DataStoreConnectionStore(dataStore, cipher)
        val first = connection()
        store.replace(first)
        val before = dataStore.data.first().asMap()

        cipher.failEncryption = true
        assertStoreFailure(ConnectionStoreFailureKind.ENCRYPTION_FAILED) {
            store.replace(connection(endpoint = "https://replacement.example"))
        }

        assertEquals(before, dataStore.data.first().asMap())
        cipher.failEncryption = false
        assertEquals(first, store.read())
    }

    @Test
    fun `stored document rejects unknown keys and unsupported versions`() = runTest {
        val dataStore = createDataStore(backgroundScope)
        val store = DataStoreConnectionStore(dataStore, TestAesGcmCredentialCipher())
        store.replace(connection())
        val key = stringPreferencesKey("device_connection_document_v1")
        val valid = dataStore.data.first()[key]!!

        dataStore.edit { preferences ->
            preferences[key] = valid.dropLast(1) + ""","unknown":true}"""
        }
        assertStoreFailure(ConnectionStoreFailureKind.MALFORMED_DOCUMENT) {
            store.read()
        }

        dataStore.edit { preferences ->
            preferences[key] = valid.replace(
                """"documentVersion":1""",
                """"documentVersion":2""",
            )
        }
        assertStoreFailure(ConnectionStoreFailureKind.UNSUPPORTED_DOCUMENT_VERSION) {
            store.read()
        }
    }

    @Test
    fun `stored document requires canonical base64url ciphertext`() = runTest {
        val dataStore = createDataStore(backgroundScope)
        val store = DataStoreConnectionStore(dataStore, TestAesGcmCredentialCipher())
        store.replace(connection())
        val key = stringPreferencesKey("device_connection_document_v1")
        val valid = dataStore.data.first()[key]!!
        val ciphertextPattern = Regex(""""ciphertext":"([^"]+)"""")
        val ciphertext = requireNotNull(ciphertextPattern.find(valid)).groupValues[1]

        dataStore.edit { preferences ->
            preferences[key] = valid.replace(
                """"ciphertext":"$ciphertext"""",
                """"ciphertext":"$ciphertext="""",
            )
        }

        assertStoreFailure(ConnectionStoreFailureKind.INVALID_ENCRYPTED_CREDENTIAL) {
            store.read()
        }
    }

    @Test
    fun `metadata is authenticated as aes gcm associated data`() = runTest {
        val dataStore = createDataStore(backgroundScope)
        val store = DataStoreConnectionStore(dataStore, TestAesGcmCredentialCipher())
        store.replace(connection())
        val key = stringPreferencesKey("device_connection_document_v1")
        val valid = dataStore.data.first()[key]!!

        dataStore.edit { preferences ->
            preferences[key] = valid.replace(
                "https://collector.example",
                "https://attacker.example",
            )
        }

        assertStoreFailure(ConnectionStoreFailureKind.DECRYPTION_FAILED) {
            store.readForRestore()
        }
    }

    @Test
    fun `explicitly unavailable key preserves authoritative metadata only`() = runTest {
        val dataStore = createDataStore(backgroundScope)
        val cipher = TestAesGcmCredentialCipher()
        val store = DataStoreConnectionStore(dataStore, cipher)
        val expected = connection()
        store.replace(expected)

        cipher.keyUnavailable = true
        val restored = store.readForRestore()

        assertEquals(
            expected.metadata,
            (restored as StoredConnectionRestoreResult.CredentialUnavailable).metadata,
        )
        assertStoreFailure(ConnectionStoreFailureKind.CREDENTIAL_KEY_UNAVAILABLE) {
            store.read()
        }
    }

    private fun createDataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = {
                temporaryFolder.newFile("connection-${System.nanoTime()}.preferences_pb")
                    .also { it.delete() }
            },
        )

    private fun connection(
        endpoint: String = "https://collector.example",
    ): DeviceConnection {
        val credential = DeviceCredential.parse(DEVICE_TOKEN)
        return DeviceConnection(
            metadata = DeviceConnectionMetadata(
                endpoint = endpoint,
                collectorId = COLLECTOR_ID,
                certificateSha256 = CERTIFICATE_SHA256,
                deviceId = DEVICE_ID,
                scopes = setOf(
                    DeviceCapability.SUMMARY_READ,
                    DeviceCapability.REFRESH_WRITE,
                ),
            ),
            credential = credential,
        )
    }

    private suspend fun assertStoreFailure(
        expected: ConnectionStoreFailureKind,
        block: suspend () -> Unit,
    ) {
        val error = try {
            block()
            throw AssertionError("Expected ConnectionStoreException")
        } catch (error: ConnectionStoreException) {
            error
        }
        assertEquals(expected, error.kind)
    }

    private companion object {
        const val COLLECTOR_ID = "qac_abcdefghijklmnopqrstuv"
        const val CERTIFICATE_SHA256 =
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
        const val DEVICE_ID = "abcdefghijkl"
        const val DEVICE_SECRET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"
        const val DEVICE_TOKEN = "qa1.$DEVICE_ID.$DEVICE_SECRET"
    }
}

private class TestAesGcmCredentialCipher : CredentialCipher {
    var failEncryption: Boolean = false
    var keyUnavailable: Boolean = false
    private var nextIvByte: Byte = 1
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    override suspend fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedCredential {
        if (failEncryption) throw IllegalStateException("injected encryption failure")
        val iv = ByteArray(EncryptedCredential.GCM_IV_BYTES) { nextIvByte }
        nextIvByte = (nextIvByte + 1).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return EncryptedCredential(
            iv = iv,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override suspend fun decrypt(
        encryptedCredential: EncryptedCredential,
        associatedData: ByteArray,
    ): ByteArray {
        if (keyUnavailable) throw CredentialKeyUnavailableException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, encryptedCredential.iv),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(encryptedCredential.ciphertext)
    }
}
