package dev.quotaarc.android.data.api

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class DeviceHttpRequest(
    val route: DeviceApiRoute,
    val headers: Map<String, String>,
)

internal data class DeviceHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

internal fun interface DeviceHttpTransport {
    fun execute(request: DeviceHttpRequest): DeviceHttpResponse
}

internal class DeviceResponseTooLargeException : IOException("Device API response is too large")

internal class DevicePinnedCertificateException(
    val failureCode: String,
    cause: Throwable? = null,
) : CertificateException(failureCode, cause)

/**
 * A fixed-route HTTPS transport. Redirect following is disabled per
 * connection. The platform's normal endpoint-name verification remains
 * untouched while the socket factory replaces CA trust with the paired leaf
 * certificate fingerprint.
 */
internal class HttpsUrlConnectionDeviceTransport(
    endpoint: String,
    certificateSha256: String,
    private val maxResponseBytes: Int,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    now: () -> Instant = Instant::now,
) : DeviceHttpTransport {
    private val origin = URI(endpoint)
    private val socketFactory = SSLContext.getInstance("TLS").run {
        init(
            null,
            arrayOf(LeafCertificatePinTrustManager(certificateSha256, now)),
            null,
        )
        socketFactory
    }

    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
    }

    override fun execute(request: DeviceHttpRequest): DeviceHttpResponse {
        val target = URI(
            origin.scheme,
            null,
            origin.host,
            origin.port,
            request.route.path,
            null,
            null,
        )
        val connection = target.toURL().openConnection() as HttpsURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.allowUserInteraction = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = request.route.method
            connection.doInput = true
            connection.doOutput = false
            connection.sslSocketFactory = socketFactory
            connection.setRequestProperty("Accept", "application/json")
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }

            val statusCode = connection.responseCode
            val declaredLength = connection.contentLengthLong
            if (declaredLength > maxResponseBytes) {
                throw DeviceResponseTooLargeException()
            }
            val stream =
                if (statusCode >= 400) connection.errorStream else connection.inputStream
            DeviceHttpResponse(
                statusCode = statusCode,
                headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { checkNotNull(it.key) },
                body = readBoundedResponse(stream, maxResponseBytes),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 10_000
    }
}

internal fun readBoundedResponse(
    stream: InputStream?,
    maxBytes: Int,
): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (stream == null) return ByteArray(0)
    stream.use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw DeviceResponseTooLargeException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

@SuppressLint("CustomX509TrustManager")
internal class LeafCertificatePinTrustManager(
    certificateSha256: String,
    private val now: () -> Instant = Instant::now,
) : X509TrustManager {
    private val expectedFingerprint = decodeFingerprint(certificateSha256)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        if (chain.isNullOrEmpty() || authType.isNullOrBlank()) {
            throw DevicePinnedCertificateException("tls.certificate_missing")
        }
        try {
            val at = Date.from(now())
            chain.forEach { it.checkValidity(at) }
        } catch (error: CertificateException) {
            throw DevicePinnedCertificateException("tls.certificate_invalid", error)
        }
        val actual = try {
            MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)
        } catch (error: Exception) {
            throw DevicePinnedCertificateException("tls.certificate_invalid", error)
        }
        if (!MessageDigest.isEqual(expectedFingerprint, actual)) {
            actual.fill(0)
            throw DevicePinnedCertificateException("tls.certificate_mismatch")
        }
        actual.fill(0)
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        throw DevicePinnedCertificateException("tls.client_certificate_forbidden")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        private val FINGERPRINT_PATTERN = Regex("""^[A-F0-9]{64}$""")

        fun decodeFingerprint(value: String): ByteArray {
            require(FINGERPRINT_PATTERN.matches(value)) {
                "Certificate fingerprint must be uppercase SHA-256 hexadecimal"
            }
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
