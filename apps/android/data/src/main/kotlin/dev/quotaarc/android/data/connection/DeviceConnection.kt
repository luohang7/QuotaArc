package dev.quotaarc.android.data.connection

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale

enum class DeviceCapability(
    val wireName: String,
) {
    SUMMARY_READ("summary.read"),
    REFRESH_WRITE("refresh.write"),
    ;

    internal companion object {
        fun fromWireName(value: String): DeviceCapability? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Non-secret connection information. Device credentials are deliberately kept
 * in [DeviceCredential], so this model is safe to expose to diagnostics and UI.
 */
data class DeviceConnectionMetadata(
    val endpoint: String,
    val collectorId: String,
    val certificateSha256: String,
    val deviceId: String,
    val scopes: Set<DeviceCapability>,
) {
    init {
        requireNotNull(DeviceConnectionRules.normalizeEndpoint(endpoint)) {
            "Endpoint must be an HTTPS origin"
        }
        require(DeviceConnectionRules.isCollectorId(collectorId)) {
            "Invalid collector identity"
        }
        require(DeviceConnectionRules.isCertificateSha256(certificateSha256)) {
            "Invalid collector certificate fingerprint"
        }
        require(DeviceConnectionRules.isDeviceId(deviceId)) {
            "Invalid device identity"
        }
        require(scopes.isNotEmpty() && DeviceCapability.SUMMARY_READ in scopes) {
            "summary.read capability is required"
        }
    }
}

/**
 * Secret-bearing connection value.
 *
 * The encoded token remains module-internal and this type intentionally
 * redacts its string representation so a [DeviceConnection] can be logged
 * without disclosing the credential.
 */
class DeviceCredential private constructor(
    val deviceId: String,
    internal val deviceToken: String,
) {
    override fun toString(): String = "DeviceCredential(deviceId=$deviceId, deviceToken=<redacted>)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceCredential || deviceId != other.deviceId) return false
        return MessageDigest.isEqual(
            deviceToken.toByteArray(Charsets.UTF_8),
            other.deviceToken.toByteArray(Charsets.UTF_8),
        )
    }

    override fun hashCode(): Int = deviceId.hashCode()

    internal companion object {
        fun parse(deviceToken: String): DeviceCredential {
            val match = DeviceConnectionRules.DEVICE_TOKEN.matchEntire(deviceToken)
                ?: throw IllegalArgumentException("Invalid device credential")
            return DeviceCredential(
                deviceId = match.groupValues[1],
                deviceToken = deviceToken,
            )
        }
    }
}

data class DeviceConnection(
    val metadata: DeviceConnectionMetadata,
    val credential: DeviceCredential,
) {
    init {
        require(metadata.deviceId == credential.deviceId) {
            "Credential device identity does not match connection metadata"
        }
    }
}

internal object DeviceConnectionRules {
    val COLLECTOR_ID = Regex("""^qac_[A-Za-z0-9_-]{22,64}$""")
    val CERTIFICATE_SHA256 = Regex("""^[A-F0-9]{64}$""")
    val DEVICE_ID = Regex("""^[A-Za-z0-9_-]{12,64}$""")
    val DEVICE_TOKEN =
        Regex("""^qa1\.([A-Za-z0-9_-]{12,64})\.([A-Za-z0-9_-]{32,128})$""")

    fun isCollectorId(value: String): Boolean = COLLECTOR_ID.matches(value)

    fun isCertificateSha256(value: String): Boolean =
        CERTIFICATE_SHA256.matches(value)

    fun isDeviceId(value: String): Boolean = DEVICE_ID.matches(value)

    /**
     * Returns the canonical origin (lower-case host and no default port), or
     * null when [raw] contains anything beyond an HTTPS origin.
     */
    fun normalizeEndpoint(raw: String): String? {
        if (raw.isEmpty() || raw != raw.trim()) return null
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.isOpaque || !uri.isAbsolute) return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        if (uri.port == 0 || uri.port < -1 || uri.port > 65_535) return null
        if (uri.rawAuthority?.endsWith(":") == true) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null

        val normalizedPort = if (uri.port == 443) -1 else uri.port
        return try {
            URI(
                "https",
                null,
                uri.host.lowercase(Locale.ROOT),
                normalizedPort,
                null,
                null,
                null,
            ).toASCIIString()
        } catch (_: URISyntaxException) {
            null
        }
    }
}
