package dev.quotaarc.android.data.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class DeviceApiRoute(
    val method: String,
    val path: String,
) {
    HEALTH("GET", "/v1/health"),
    SUMMARY("GET", "/v1/summary"),
    REFRESH("POST", "/v1/refresh"),
}

internal data class SignedDeviceRequest(
    val headers: Map<String, String>,
)

internal fun interface DeviceNonceGenerator {
    fun nextNonce(): String
}

internal object SecureDeviceNonceGenerator : DeviceNonceGenerator {
    private val secureRandom = SecureRandom()

    override fun nextNonce(): String =
        ByteArray(NONCE_BYTES)
            .also(secureRandom::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private const val NONCE_BYTES = 18
}

/**
 * Signs only the three fixed QuotaArc device routes. The HMAC key is derived
 * from the token secret exactly as the Collector does; the token itself is
 * never placed in an HTTP header.
 */
internal class DeviceRequestSigner(
    private val now: () -> Instant = Instant::now,
    private val nonceGenerator: DeviceNonceGenerator = SecureDeviceNonceGenerator,
) {
    fun sign(
        deviceToken: String,
        route: DeviceApiRoute,
    ): SignedDeviceRequest {
        val token = parseDeviceToken(deviceToken)
        val timestamp = now().epochSecond.toString()
        val nonce = nonceGenerator.nextNonce()
        require(NONCE_PATTERN.matches(nonce)) { "Device request nonce is invalid" }

        val canonical = listOf(
            route.method,
            route.path,
            timestamp,
            nonce,
            EMPTY_BODY_SHA256,
        ).joinToString("\n")
        val secretBytes = token.secret.toByteArray(StandardCharsets.UTF_8)
        val verificationKey = MessageDigest.getInstance("SHA-256").digest(secretBytes)
        secretBytes.fill(0)
        val signatureBytes = try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(verificationKey, "HmacSHA256"))
                doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            }
        } finally {
            verificationKey.fill(0)
        }
        val signature = URL_ENCODER.encodeToString(signatureBytes)
        signatureBytes.fill(0)

        return SignedDeviceRequest(
            headers = mapOf(
                AUTHORIZATION_HEADER to "$AUTHORIZATION_SCHEME ${token.deviceId}:$signature",
                TIMESTAMP_HEADER to timestamp,
                NONCE_HEADER to nonce,
            ),
        )
    }

    private data class DeviceTokenParts(
        val deviceId: String,
        val secret: String,
    )

    private fun parseDeviceToken(token: String): DeviceTokenParts {
        require(DEVICE_TOKEN_PATTERN.matches(token)) { "Device token is invalid" }
        val parts = token.split('.')
        return DeviceTokenParts(
            deviceId = parts[1],
            secret = parts[2],
        )
    }

    internal companion object {
        const val EMPTY_BODY_SHA256 =
            "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"
        const val AUTHORIZATION_SCHEME = "QuotaArc-HMAC"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val TIMESTAMP_HEADER = "X-QuotaArc-Timestamp"
        const val NONCE_HEADER = "X-QuotaArc-Nonce"

        private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val NONCE_PATTERN = Regex("""^[A-Za-z0-9_-]{22,64}$""")
        private val DEVICE_TOKEN_PATTERN =
            Regex("""^qa1\.[A-Za-z0-9_-]{12,64}\.[A-Za-z0-9_-]{32,128}$""")
    }
}
