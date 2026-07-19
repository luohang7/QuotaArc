package dev.quotaarc.android.data.testing

import dev.quotaarc.android.data.api.DeviceApiResult
import dev.quotaarc.android.data.api.QuotaArcDeviceApi
import java.util.concurrent.atomic.AtomicInteger

internal class ScriptedQuotaArcDeviceApi(
    healthSteps: List<suspend () -> DeviceApiResult<Unit>> =
        listOf({ DeviceApiResult.Success(Unit) }),
    refreshSteps: List<suspend () -> DeviceApiResult<Unit>> =
        listOf({ DeviceApiResult.Success(Unit) }),
    fetchSteps: List<suspend () -> DeviceApiResult<ByteArray>>,
) : QuotaArcDeviceApi {
    private val healthQueue = ArrayDeque(healthSteps)
    private val refreshQueue = ArrayDeque(refreshSteps)
    private val fetchQueue = ArrayDeque(fetchSteps)
    private val lock = Any()

    val healthCalls = AtomicInteger()
    val refreshCalls = AtomicInteger()
    val fetchCalls = AtomicInteger()
    val callOrder = mutableListOf<String>()

    override suspend fun health(): DeviceApiResult<Unit> {
        healthCalls.incrementAndGet()
        synchronized(lock) { callOrder += "health" }
        return take(healthQueue, "health").invoke()
    }

    override suspend fun fetchSummary(): DeviceApiResult<ByteArray> {
        fetchCalls.incrementAndGet()
        synchronized(lock) { callOrder += "summary" }
        return take(fetchQueue, "summary").invoke()
    }

    override suspend fun requestRefresh(): DeviceApiResult<Unit> {
        refreshCalls.incrementAndGet()
        synchronized(lock) { callOrder += "refresh" }
        return take(refreshQueue, "refresh").invoke()
    }

    private fun <T> take(
        queue: ArrayDeque<suspend () -> DeviceApiResult<T>>,
        name: String,
    ): suspend () -> DeviceApiResult<T> = synchronized(lock) {
        check(queue.isNotEmpty()) { "No scripted $name response remains" }
        if (queue.size == 1) queue.first() else queue.removeFirst()
    }
}
