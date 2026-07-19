package dev.quotaarc.android.data.api

/**
 * The only release transport until the authenticated Collector-to-phone ADR,
 * pairing, revocation, and endpoint contracts are accepted.
 */
object DisabledQuotaArcDeviceApi : QuotaArcDeviceApi {
    private val result = DeviceApiResult.Failure(
        DeviceApiFailure(
            kind = DeviceApiFailureKind.GATE_CLOSED,
            code = "transport_gate_closed",
            retryable = false,
        ),
    )

    override suspend fun health(): DeviceApiResult<Unit> = result

    override suspend fun fetchSummary(): DeviceApiResult<ByteArray> = result

    override suspend fun requestRefresh(): DeviceApiResult<Unit> = result
}
