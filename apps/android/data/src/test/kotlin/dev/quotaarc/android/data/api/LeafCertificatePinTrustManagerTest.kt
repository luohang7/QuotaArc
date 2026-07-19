package dev.quotaarc.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.security.auth.x500.X500Principal

class LeafCertificatePinTrustManagerTest {
    private val now = Instant.parse("2026-07-19T10:00:00Z")
    private val encodedLeaf = "quotaarc-test-leaf".encodeToByteArray()
    private val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(encodedLeaf)
        .joinToString("") { "%02X".format(it.toInt() and 0xff) }

    @Test
    fun `matching valid leaf fingerprint is trusted`() {
        val manager = LeafCertificatePinTrustManager(fingerprint) { now }

        manager.checkServerTrusted(
            arrayOf(
                StubCertificate(
                    encoded = encodedLeaf,
                    notBefore = now.minus(1, ChronoUnit.DAYS),
                    notAfter = now.plus(1, ChronoUnit.DAYS),
                ),
            ),
            "RSA",
        )
    }

    @Test
    fun `wrong fingerprint and empty chain fail closed`() {
        val certificate = StubCertificate(
            encoded = encodedLeaf,
            notBefore = now.minus(1, ChronoUnit.DAYS),
            notAfter = now.plus(1, ChronoUnit.DAYS),
        )
        val mismatch = assertThrows(DevicePinnedCertificateException::class.java) {
            LeafCertificatePinTrustManager("A".repeat(64)) { now }
                .checkServerTrusted(arrayOf(certificate), "RSA")
        }
        assertEquals("tls.certificate_mismatch", mismatch.failureCode)

        val missing = assertThrows(DevicePinnedCertificateException::class.java) {
            LeafCertificatePinTrustManager(fingerprint) { now }
                .checkServerTrusted(emptyArray(), "RSA")
        }
        assertEquals("tls.certificate_missing", missing.failureCode)
    }

    @Test
    fun `expired and not-yet-valid certificates fail closed`() {
        for (certificate in listOf(
            StubCertificate(
                encoded = encodedLeaf,
                notBefore = now.minus(2, ChronoUnit.DAYS),
                notAfter = now.minus(1, ChronoUnit.DAYS),
            ),
            StubCertificate(
                encoded = encodedLeaf,
                notBefore = now.plus(1, ChronoUnit.DAYS),
                notAfter = now.plus(2, ChronoUnit.DAYS),
            ),
        )) {
            val failure = assertThrows(DevicePinnedCertificateException::class.java) {
                LeafCertificatePinTrustManager(fingerprint) { now }
                    .checkServerTrusted(arrayOf(certificate), "RSA")
            }
            assertEquals("tls.certificate_invalid", failure.failureCode)
        }
    }

    @Suppress("DEPRECATION")
    private class StubCertificate(
        private val encoded: ByteArray,
        private val notBefore: Instant,
        private val notAfter: Instant,
    ) : X509Certificate() {
        override fun checkValidity() = checkValidity(Date())

        override fun checkValidity(date: Date) {
            when {
                date.toInstant().isBefore(notBefore) ->
                    throw CertificateNotYetValidException()
                date.toInstant().isAfter(notAfter) ->
                    throw CertificateExpiredException()
            }
        }

        override fun getEncoded(): ByteArray = encoded.copyOf()
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = X500Principal("CN=QuotaArc")
        override fun getSubjectDN(): Principal = X500Principal("CN=QuotaArc")
        override fun getNotBefore(): Date = Date.from(notBefore)
        override fun getNotAfter(): Date = Date.from(notAfter)
        override fun getTBSCertificate(): ByteArray = encoded.copyOf()
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun getPublicKey(): PublicKey? = null
        override fun toString(): String = "StubCertificate"
    }
}
