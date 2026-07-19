package dev.quotaarc.android.data.connection

import dev.quotaarc.android.data.api.DeviceApiConnectionConfig
import dev.quotaarc.android.data.api.DeviceApiFailure
import dev.quotaarc.android.data.api.DeviceApiFailureKind
import dev.quotaarc.android.data.api.DeviceApiResult
import dev.quotaarc.android.data.api.PinnedHttpsQuotaArcDeviceApi
import dev.quotaarc.android.data.api.QuotaArcDeviceApi
import dev.quotaarc.android.data.contract.ContractFailureKind
import dev.quotaarc.android.data.contract.QuotaArcContractException
import dev.quotaarc.android.data.contract.QuotaArcV1JsonCodec
import dev.quotaarc.android.data.repository.DeactivatableQuotaArcRepository
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.SwitchingQuotaArcRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ConnectionFailureKind {
    PAIRING_INVALID,
    OFFLINE,
    TIMEOUT,
    AUTH_REQUIRED,
    FORBIDDEN,
    REMOTE,
    PROTOCOL,
    CONTRACT_INVALID,
    PERSISTENCE,
}

data class ConnectionFailure(
    val kind: ConnectionFailureKind,
    val code: String,
    val retryable: Boolean,
)

sealed interface ConnectionTestResult {
    data class Success(
        val metadata: DeviceConnectionMetadata,
    ) : ConnectionTestResult

    data class Failure(
        val failure: ConnectionFailure,
    ) : ConnectionTestResult
}

sealed interface ConnectionActivationResult {
    data class Success(
        val metadata: DeviceConnectionMetadata,
    ) : ConnectionActivationResult

    data class Failure(
        val failure: ConnectionFailure,
    ) : ConnectionActivationResult
}

sealed interface ConnectionRestoreResult {
    data object Absent : ConnectionRestoreResult

    data class Ready(
        val metadata: DeviceConnectionMetadata,
    ) : ConnectionRestoreResult

    data class CredentialUnavailable(
        val metadata: DeviceConnectionMetadata,
    ) : ConnectionRestoreResult

    data object Invalid : ConnectionRestoreResult
}

internal fun interface DeviceApiFactory {
    fun create(connection: DeviceConnection): QuotaArcDeviceApi
}

internal fun interface ConnectedRepositoryFactory {
    fun create(
        connection: DeviceConnection,
        api: QuotaArcDeviceApi,
    ): QuotaArcRepository
}

internal fun interface UnavailableRepositoryFactory {
    fun create(metadata: DeviceConnectionMetadata): QuotaArcRepository
}

interface QuotaArcConnectionCoordinator {
    val repository: QuotaArcRepository

    suspend fun test(pairingJson: String): ConnectionTestResult

    suspend fun testAndSave(pairingJson: String): ConnectionActivationResult

    suspend fun awaitInitialRestore(): ConnectionRestoreResult
}

/**
 * Owns the two-phase pairing transition. Network probing is completed before
 * the encrypted document is replaced; activation happens only after that
 * atomic write succeeds.
 */
class QuotaArcConnectionManager internal constructor(
    private val store: ConnectionStore,
    private val switchingRepository: SwitchingQuotaArcRepository,
    private val apiFactory: DeviceApiFactory,
    private val repositoryFactory: ConnectedRepositoryFactory,
    private val unavailableRepositoryFactory: UnavailableRepositoryFactory,
    private val summaryCodec: QuotaArcV1JsonCodec = QuotaArcV1JsonCodec(),
) : QuotaArcConnectionCoordinator {
    private val transitionMutex = Mutex()
    private val mutableRestoreState = MutableStateFlow<ConnectionRestoreResult?>(null)

    @Volatile
    private var initialRestore: Deferred<ConnectionRestoreResult>? = null

    val restoreState: StateFlow<ConnectionRestoreResult?> =
        mutableRestoreState.asStateFlow()

    override val repository: QuotaArcRepository
        get() = switchingRepository

    override suspend fun test(pairingJson: String): ConnectionTestResult {
        val connection = parse(pairingJson)
            ?: return ConnectionTestResult.Failure(PAIRING_FAILURE)
        return when (val probe = probe(connection)) {
            null -> ConnectionTestResult.Success(connection.metadata)
            else -> ConnectionTestResult.Failure(probe)
        }
    }

    override suspend fun testAndSave(pairingJson: String): ConnectionActivationResult =
        transitionMutex.withLock {
            val connection = parse(pairingJson)
                ?: return@withLock ConnectionActivationResult.Failure(PAIRING_FAILURE)
            val probeFailure = probe(connection)
            if (probeFailure != null) {
                return@withLock ConnectionActivationResult.Failure(probeFailure)
            }

            val api = apiFactory.create(connection)
            val repository = repositoryFactory.create(connection, api)
            // The network probe above remains cancellable. Once the
            // authoritative encrypted document starts committing, persistence
            // and in-memory activation are one non-cancellable boundary: a
            // cancelled UI caller must not leave disk on the new Collector
            // while the running process still serves the old one.
            withContext(NonCancellable) {
                try {
                    store.replace(connection)
                    switchingRepository.activate(repository)
                    mutableRestoreState.value =
                        ConnectionRestoreResult.Ready(connection.metadata)
                    ConnectionActivationResult.Success(connection.metadata)
                } catch (_: Exception) {
                    (repository as? DeactivatableQuotaArcRepository)?.deactivate()
                    ConnectionActivationResult.Failure(
                        ConnectionFailure(
                            kind = ConnectionFailureKind.PERSISTENCE,
                            code = "connection.persist_failed",
                            retryable = true,
                        ),
                    )
                }
            }
        }

    override suspend fun awaitInitialRestore(): ConnectionRestoreResult =
        initialRestore?.await()
            ?: mutableRestoreState.value
            ?: ConnectionRestoreResult.Absent

    internal fun attachInitialRestore(
        restore: Deferred<ConnectionRestoreResult>,
    ) {
        synchronized(this) {
            check(initialRestore == null) { "Initial restore is already attached" }
            initialRestore = restore
        }
        restore.start()
    }

    /**
     * Restores a previously validated connection without requiring the
     * Collector to be online. A missing or permanently invalidated Keystore key
     * activates an identity-bound read-only cache. Malformed documents, invalid
     * ciphertext, and AEAD failures remain fully fail-closed.
     */
    internal suspend fun restore(): ConnectionRestoreResult =
        transitionMutex.withLock {
            val outcome = try {
                when (val stored = store.readForRestore()) {
                    StoredConnectionRestoreResult.Absent ->
                        ConnectionRestoreResult.Absent
                    is StoredConnectionRestoreResult.Ready -> {
                        val connection = stored.connection
                        val api = apiFactory.create(connection)
                        switchingRepository.activate(
                            repositoryFactory.create(connection, api),
                        )
                        ConnectionRestoreResult.Ready(connection.metadata)
                    }
                    is StoredConnectionRestoreResult.CredentialUnavailable -> {
                        switchingRepository.activate(
                            unavailableRepositoryFactory.create(stored.metadata),
                        )
                        ConnectionRestoreResult.CredentialUnavailable(
                            stored.metadata,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ConnectionRestoreResult.Invalid
            }
            mutableRestoreState.value = outcome
            outcome
        }

    private fun parse(pairingJson: String): DeviceConnection? =
        try {
            DevicePairingCodec.decode(pairingJson)
        } catch (_: DevicePairingException) {
            null
        }

    private suspend fun probe(connection: DeviceConnection): ConnectionFailure? {
        val api = try {
            apiFactory.create(connection)
        } catch (_: Exception) {
            return ConnectionFailure(
                kind = ConnectionFailureKind.PAIRING_INVALID,
                code = "pairing.invalid",
                retryable = false,
            )
        }
        when (val health = api.health()) {
            is DeviceApiResult.Failure -> return health.failure.toConnectionFailure()
            is DeviceApiResult.Success -> Unit
        }
        val summary = when (val result = api.fetchSummary()) {
            is DeviceApiResult.Failure -> return result.failure.toConnectionFailure()
            is DeviceApiResult.Success -> result.value
        }
        try {
            summaryCodec.decode(summary)
        } catch (error: QuotaArcContractException) {
            return ConnectionFailure(
                kind = ConnectionFailureKind.CONTRACT_INVALID,
                code = when (error.kind) {
                    ContractFailureKind.UNSUPPORTED_SCHEMA ->
                        "contract.unsupported_schema"
                    else -> "contract.invalid"
                },
                retryable = false,
            )
        }
        return null
    }

    private companion object {
        val PAIRING_FAILURE = ConnectionFailure(
            kind = ConnectionFailureKind.PAIRING_INVALID,
            code = "pairing.invalid",
            retryable = false,
        )
    }
}

private fun DeviceApiFailure.toConnectionFailure(): ConnectionFailure =
    ConnectionFailure(
        kind = when (kind) {
            DeviceApiFailureKind.GATE_CLOSED -> ConnectionFailureKind.PROTOCOL
            DeviceApiFailureKind.OFFLINE -> ConnectionFailureKind.OFFLINE
            DeviceApiFailureKind.TIMEOUT -> ConnectionFailureKind.TIMEOUT
            DeviceApiFailureKind.UNAUTHORIZED -> ConnectionFailureKind.AUTH_REQUIRED
            DeviceApiFailureKind.FORBIDDEN -> ConnectionFailureKind.FORBIDDEN
            DeviceApiFailureKind.REMOTE -> ConnectionFailureKind.REMOTE
            DeviceApiFailureKind.PROTOCOL -> ConnectionFailureKind.PROTOCOL
        },
        code = code,
        retryable = retryable,
    )

internal fun DeviceConnection.toApiConfig(): DeviceApiConnectionConfig =
    DeviceApiConnectionConfig(
        endpoint = metadata.endpoint,
        collectorId = metadata.collectorId,
        certificateSha256 = metadata.certificateSha256,
        deviceToken = credential.deviceToken,
        scopes = metadata.scopes.mapTo(linkedSetOf(), DeviceCapability::wireName),
    )

internal fun productionDeviceApi(connection: DeviceConnection): QuotaArcDeviceApi =
    PinnedHttpsQuotaArcDeviceApi(connection.toApiConfig())
