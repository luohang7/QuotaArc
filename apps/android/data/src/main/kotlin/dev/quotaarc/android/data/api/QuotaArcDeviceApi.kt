package dev.quotaarc.android.data.api

enum class DeviceApiFailureKind {
    GATE_CLOSED,
    OFFLINE,
    TIMEOUT,
    UNAUTHORIZED,
    FORBIDDEN,
    REMOTE,
    PROTOCOL,
}

data class DeviceApiFailure(
    val kind: DeviceApiFailureKind,
    val code: String,
    val retryable: Boolean,
)

sealed interface DeviceApiResult<out T> {
    data class Success<T>(val value: T) : DeviceApiResult<T>
    data class Failure(val failure: DeviceApiFailure) : DeviceApiResult<Nothing>
}

/**
 * The complete mobile wire capability. It cannot proxy arbitrary app-server
 * RPC and it intentionally does not expose generic HTTP methods.
 */
interface QuotaArcDeviceApi {
    suspend fun health(): DeviceApiResult<Unit>
    suspend fun fetchSummary(): DeviceApiResult<ByteArray>
    suspend fun requestRefresh(): DeviceApiResult<Unit>
}
