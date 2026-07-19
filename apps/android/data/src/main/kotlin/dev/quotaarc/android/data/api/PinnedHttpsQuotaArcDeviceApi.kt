package dev.quotaarc.android.data.api

import dev.quotaarc.android.data.contract.QuotaArcV1JsonCodec
import dev.quotaarc.android.data.endpoint.EndpointDraftValidator
import dev.quotaarc.android.data.endpoint.EndpointValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal data class DeviceApiConnectionConfig(
    val endpoint: String,
    val collectorId: String,
    val certificateSha256: String,
    val deviceToken: String,
    val scopes: Set<String>,
)

/**
 * Production device client for the authenticated, fixed-route Collector API.
 * There is intentionally no generic request entry point.
 */
internal class PinnedHttpsQuotaArcDeviceApi(
    rawConfig: DeviceApiConnectionConfig,
    private val signer: DeviceRequestSigner = DeviceRequestSigner(),
    transportFactory: (DeviceApiConnectionConfig) -> DeviceHttpTransport = ::productionTransport,
    private val wireCodec: DeviceApiWireCodec = DeviceApiWireCodec(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QuotaArcDeviceApi {
    private val config = rawConfig.validated()
    private val transport = transportFactory(config)

    override suspend fun health(): DeviceApiResult<Unit> =
        request(DeviceApiRoute.HEALTH) { response ->
            val health = wireCodec.decodeHealth(response.body)
            if (
                health.collectorId != config.collectorId ||
                health.capabilities.toSet() != config.scopes
            ) {
                protocolFailure("response.collector_identity_mismatch")
            } else {
                DeviceApiResult.Success(Unit)
            }
        }

    override suspend fun fetchSummary(): DeviceApiResult<ByteArray> =
        request(DeviceApiRoute.SUMMARY) { response ->
            if (response.body.isEmpty()) {
                protocolFailure("response.invalid")
            } else {
                DeviceApiResult.Success(response.body)
            }
        }

    override suspend fun requestRefresh(): DeviceApiResult<Unit> {
        if (REFRESH_SCOPE !in config.scopes) {
            return DeviceApiResult.Failure(
                DeviceApiFailure(
                    kind = DeviceApiFailureKind.FORBIDDEN,
                    code = "auth.scope_denied",
                    retryable = false,
                ),
            )
        }
        return request(DeviceApiRoute.REFRESH) { response ->
            val receipt = wireCodec.decodeRefreshReceipt(response.body)
            if (receipt.collectorId != config.collectorId) {
                protocolFailure("response.collector_identity_mismatch")
            } else {
                DeviceApiResult.Success(Unit)
            }
        }
    }

    private suspend fun <T> request(
        route: DeviceApiRoute,
        onSuccess: (DeviceHttpResponse) -> DeviceApiResult<T>,
    ): DeviceApiResult<T> {
        val response = try {
            val signed = signer.sign(config.deviceToken, route)
            withContext(ioDispatcher) {
                transport.execute(
                    DeviceHttpRequest(
                        route = route,
                        headers = signed.headers,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return DeviceApiResult.Failure(error.toDeviceFailure())
        }

        if (response.body.size > MAX_RESPONSE_BYTES) {
            return protocolFailure("response.too_large")
        }
        if (response.statusCode in 300..399) {
            return protocolFailure("response.redirect_forbidden")
        }
        if (response.statusCode != HTTP_OK) {
            return response.toFailure()
        }
        if (!response.hasExpectedCollectorIdentity(config.collectorId)) {
            return protocolFailure("response.collector_identity_mismatch")
        }
        if (!response.hasJsonContentType()) {
            return protocolFailure("response.content_type_invalid")
        }
        return try {
            onSuccess(response)
        } catch (_: DeviceWireContractException) {
            protocolFailure("response.invalid")
        } catch (_: IllegalArgumentException) {
            protocolFailure("response.invalid")
        }
    }

    private fun DeviceHttpResponse.toFailure(): DeviceApiResult.Failure {
        if (statusCode !in 400..599) {
            return protocolFailure("response.status_invalid")
        }
        if (!hasJsonContentType()) {
            return protocolFailure("response.content_type_invalid")
        }
        val error = try {
            wireCodec.decodeError(body).error
        } catch (_: DeviceWireContractException) {
            return protocolFailure("response.invalid_error")
        }
        val kind = when (statusCode) {
            401 -> DeviceApiFailureKind.UNAUTHORIZED
            403 -> DeviceApiFailureKind.FORBIDDEN
            else -> DeviceApiFailureKind.REMOTE
        }
        return DeviceApiResult.Failure(
            DeviceApiFailure(
                kind = kind,
                code = error.code,
                retryable = error.retryable,
            ),
        )
    }

    private fun DeviceHttpResponse.hasExpectedCollectorIdentity(
        expected: String,
    ): Boolean {
        val values = headers.entries
            .filter { (name) -> name.equals(COLLECTOR_ID_HEADER, ignoreCase = true) }
            .flatMap { it.value }
        return values.size == 1 && values.single() == expected
    }

    private fun DeviceHttpResponse.hasJsonContentType(): Boolean {
        val values = headers.entries
            .filter { (name) -> name.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
            .flatMap { it.value }
        if (values.size != 1) return false
        return values.single()
            .substringBefore(';')
            .trim()
            .equals(JSON_MEDIA_TYPE, ignoreCase = true)
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_RESPONSE_BYTES = QuotaArcV1JsonCodec.DEFAULT_MAX_PAYLOAD_BYTES
        const val COLLECTOR_ID_HEADER = "X-QuotaArc-Collector-Id"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val JSON_MEDIA_TYPE = "application/json"
        const val SUMMARY_SCOPE = "summary.read"
        const val REFRESH_SCOPE = "refresh.write"
        val ALLOWED_SCOPES = setOf(SUMMARY_SCOPE, REFRESH_SCOPE)

        fun productionTransport(config: DeviceApiConnectionConfig): DeviceHttpTransport =
            HttpsUrlConnectionDeviceTransport(
                endpoint = config.endpoint,
                certificateSha256 = config.certificateSha256,
                maxResponseBytes = MAX_RESPONSE_BYTES,
            )
    }
}

private fun DeviceApiConnectionConfig.validated(): DeviceApiConnectionConfig {
    val validatedEndpoint = when (val result = EndpointDraftValidator.validate(endpoint)) {
        is EndpointValidationResult.Valid -> result.endpoint.baseUrl
        is EndpointValidationResult.Invalid ->
            throw IllegalArgumentException("Device API endpoint is invalid")
    }
    require(COLLECTOR_ID_PATTERN.matches(collectorId)) {
        "Collector identity is invalid"
    }
    require(CERTIFICATE_PATTERN.matches(certificateSha256)) {
        "Collector certificate fingerprint is invalid"
    }
    require(DEVICE_TOKEN_PATTERN.matches(deviceToken)) {
        "Device token is invalid"
    }
    require(scopes.isNotEmpty() && scopes.all { it in ALLOWED_DEVICE_SCOPES }) {
        "Device scopes are invalid"
    }
    require(SUMMARY_READ_SCOPE in scopes) {
        "summary.read is required"
    }
    return copy(endpoint = validatedEndpoint, scopes = scopes.toSet())
}

private fun Exception.toDeviceFailure(): DeviceApiFailure {
    val pinnedCertificate = causeChain()
        .filterIsInstance<DevicePinnedCertificateException>()
        .firstOrNull()
    return when {
        this is DeviceResponseTooLargeException -> DeviceApiFailure(
            kind = DeviceApiFailureKind.PROTOCOL,
            code = "response.too_large",
            retryable = false,
        )
        pinnedCertificate != null -> DeviceApiFailure(
            kind = DeviceApiFailureKind.PROTOCOL,
            code = pinnedCertificate.failureCode,
            retryable = false,
        )
        this is SocketTimeoutException -> DeviceApiFailure(
            kind = DeviceApiFailureKind.TIMEOUT,
            code = "transport.timeout",
            retryable = true,
        )
        this is UnknownHostException ||
            this is ConnectException ||
            this is NoRouteToHostException -> DeviceApiFailure(
            kind = DeviceApiFailureKind.OFFLINE,
            code = "transport.offline",
            retryable = true,
        )
        this is SSLException -> DeviceApiFailure(
            kind = DeviceApiFailureKind.PROTOCOL,
            code = "tls.handshake_failed",
            retryable = false,
        )
        this is IOException -> DeviceApiFailure(
            kind = DeviceApiFailureKind.OFFLINE,
            code = "transport.io",
            retryable = true,
        )
        else -> DeviceApiFailure(
            kind = DeviceApiFailureKind.PROTOCOL,
            code = "transport.invalid",
            retryable = false,
        )
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { current ->
        current.cause?.takeUnless { it === current }
    }

private fun protocolFailure(code: String): DeviceApiResult.Failure =
    DeviceApiResult.Failure(
        DeviceApiFailure(
            kind = DeviceApiFailureKind.PROTOCOL,
            code = code,
            retryable = false,
        ),
    )

private val COLLECTOR_ID_PATTERN = Regex("""^qac_[A-Za-z0-9_-]{22,64}$""")
private val CERTIFICATE_PATTERN = Regex("""^[A-F0-9]{64}$""")
private val DEVICE_TOKEN_PATTERN =
    Regex("""^qa1\.[A-Za-z0-9_-]{12,64}\.[A-Za-z0-9_-]{32,128}$""")
private val ALLOWED_DEVICE_SCOPES = setOf("summary.read", "refresh.write")
private const val SUMMARY_READ_SCOPE = "summary.read"
