package dev.quotaarc.android.data.connection

import java.security.MessageDigest

/**
 * Ciphertext representation used by [ConnectionStore]. The arrays are copied
 * at the boundary so callers cannot mutate an encrypted value after creation.
 */
class EncryptedCredential(
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val ivBytes = iv.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    init {
        require(ivBytes.size == GCM_IV_BYTES) {
            "AES-GCM IV must be 96 bits"
        }
        require(ciphertextBytes.size >= GCM_TAG_BYTES) {
            "AES-GCM ciphertext must contain an authentication tag"
        }
    }

    val iv: ByteArray
        get() = ivBytes.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is EncryptedCredential &&
            MessageDigest.isEqual(ivBytes, other.ivBytes) &&
            MessageDigest.isEqual(ciphertextBytes, other.ciphertextBytes)

    override fun hashCode(): Int =
        31 * ivBytes.contentHashCode() + ciphertextBytes.contentHashCode()

    override fun toString(): String =
        "EncryptedCredential(ivBytes=${ivBytes.size}, ciphertextBytes=${ciphertextBytes.size})"

    companion object {
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
    }
}

/**
 * Credential encryption boundary. [associatedData] binds a token to the
 * connection metadata stored alongside it.
 */
interface CredentialCipher {
    suspend fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedCredential

    suspend fun decrypt(
        encryptedCredential: EncryptedCredential,
        associatedData: ByteArray,
    ): ByteArray
}

/**
 * The encrypted document is structurally intact, but its Android Keystore key
 * no longer exists or was permanently invalidated. Callers may still use the
 * plaintext metadata from that same atomic document to select a read-only,
 * identity-bound cache; authentication remains disabled.
 */
class CredentialKeyUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("Android Keystore credential key is unavailable", cause)
