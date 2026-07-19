package dev.quotaarc.android.data.api

object DisabledQuotaArcDeviceApi {
    fun forConnectionFailure(reason: DisabledConnectionReason): QuotaArcDeviceApi =
        ReasonedDisabledDeviceApi(reason)
}

enum class DisabledConnectionReason(
    internal val code: String,
) {
    NOT_CONFIGURED("connection.not_configured"),
    CREDENTIAL_UNAVAILABLE("credential.unavailable"),
}

private class ReasonedDisabledDeviceApi(
    reason: DisabledConnectionReason,
) : QuotaArcDeviceApi {
    private val result = DeviceApiResult.Failure(
        DeviceApiFailure(
            kind = DeviceApiFailureKind.UNAUTHORIZED,
            code = reason.code,
            retryable = false,
        ),
    )

    override suspend fun health(): DeviceApiResult<Unit> = result
    override suspend fun fetchSummary(): DeviceApiResult<ByteArray> = result
    override suspend fun requestRefresh(): DeviceApiResult<Unit> = result
}
