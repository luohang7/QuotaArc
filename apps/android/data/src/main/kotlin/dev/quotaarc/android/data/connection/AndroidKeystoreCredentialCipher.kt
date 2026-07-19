package dev.quotaarc.android.data.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM credential protection backed by a non-exportable Android Keystore
 * key. Cipher-generated random 96-bit IVs are stored with the ciphertext.
 */
class AndroidKeystoreCredentialCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialCipher {
    init {
        require(keyAlias.isNotBlank()) { "Android Keystore alias must not be blank" }
    }

    override suspend fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedCredential = withContext(ioDispatcher) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == EncryptedCredential.GCM_IV_BYTES) {
            "Android Keystore returned an unsupported AES-GCM IV length"
        }
        EncryptedCredential(iv = iv, ciphertext = ciphertext)
    }

    override suspend fun decrypt(
        encryptedCredential: EncryptedCredential,
        associatedData: ByteArray,
    ): ByteArray = withContext(ioDispatcher) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadExistingKey(),
                GCMParameterSpec(GCM_TAG_BITS, encryptedCredential.iv),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(encryptedCredential.ciphertext)
        } catch (error: CredentialKeyUnavailableException) {
            throw error
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw CredentialKeyUnavailableException(error)
        } catch (error: UnrecoverableKeyException) {
            throw CredentialKeyUnavailableException(error)
        }
    }

    private fun loadOrCreateKey(): SecretKey = synchronized(KEY_CREATION_LOCK) {
        val keyStore = loadKeyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: run {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            keyGenerator.generateKey()
        }
    }

    private fun loadExistingKey(): SecretKey {
        val key = loadKeyStore().getKey(keyAlias, null)
        return key as? SecretKey
            ?: throw CredentialKeyUnavailableException()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_KEY_ALIAS = "dev.quotaarc.android.device-credential.v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private val KEY_CREATION_LOCK = Any()
    }
}
