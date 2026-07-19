package dev.quotaarc.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.quotaarc.android.data.connection.ConnectionActivationResult
import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import dev.quotaarc.android.data.connection.ConnectionTestResult
import dev.quotaarc.android.data.connection.QuotaArcConnectionCoordinator
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.RefreshFailureKind
import dev.quotaarc.android.data.repository.RefreshResult
import dev.quotaarc.android.data.repository.RefreshTrigger
import dev.quotaarc.android.data.repository.RepositoryState
import dev.quotaarc.android.ui.model.AppDestination
import dev.quotaarc.android.ui.model.AppUiState
import dev.quotaarc.android.ui.model.DetailUiModel
import dev.quotaarc.android.ui.model.RefreshUi
import dev.quotaarc.android.ui.model.SetupAttemptUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class AppViewModel(
    private val connectionManager: QuotaArcConnectionCoordinator,
    private val onConnectionActivated: () -> Unit = {},
    private val onSnapshotChanged: suspend () -> Unit = {},
) : ViewModel() {
    private val repository: QuotaArcRepository = connectionManager.repository
    private val mutableState = MutableStateFlow(AppUiState())
    private var connectionTransitionRevision = 0L
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe()
                .catch {
                    mutableState.update { current ->
                        current.copy(refresh = RefreshUi.Failed("cache.observe_failed"))
                    }
                }
                .collect(::applyRepositoryState)
        }
        val initialRestoreRevision = connectionTransitionRevision
        viewModelScope.launch {
            val restored = try {
                connectionManager.awaitInitialRestore()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ConnectionRestoreResult.Invalid
            }
            if (initialRestoreRevision != connectionTransitionRevision) {
                return@launch
            }
            when (restored) {
                is ConnectionRestoreResult.Ready,
                is ConnectionRestoreResult.CredentialUnavailable,
                -> {
                    val metadata = when (restored) {
                        is ConnectionRestoreResult.Ready -> restored.metadata
                        is ConnectionRestoreResult.CredentialUnavailable ->
                            restored.metadata
                    }
                    mutableState.update { current ->
                        current.copy(
                            destination = if (
                                current.setup.pairingJson.isEmpty() &&
                                current.setup.attempt == SetupAttemptUi.None
                            ) {
                                AppDestination.DETAILS
                            } else {
                                current.destination
                            },
                            collectorId = metadata.collectorId,
                            refresh = RefreshUi.Running,
                        )
                    }
                    applyRefreshResult(callRefresh(RefreshTrigger.FOREGROUND))
                    runSnapshotCallback()
                }
                ConnectionRestoreResult.Absent -> Unit
                ConnectionRestoreResult.Invalid -> {
                    mutableState.update { current ->
                        current.copy(
                            destination = AppDestination.SETUP,
                            collectorId = null,
                            refresh = RefreshUi.Failed("connection.restore_invalid"),
                        )
                    }
                }
            }
        }
    }

    fun navigate(destination: AppDestination) {
        mutableState.update { it.copy(destination = destination) }
    }

    fun updatePairingJson(value: String) {
        if (isSetupBusy()) return
        mutableState.update { current ->
            current.copy(
                setup = current.setup.copy(
                    pairingJson = value,
                    pairingError = null,
                    attempt = SetupAttemptUi.None,
                ),
            )
        }
    }

    fun togglePairingVisibility() {
        if (isSetupBusy()) return
        mutableState.update { current ->
            current.copy(
                setup = current.setup.copy(
                    pairingVisible = !current.setup.pairingVisible,
                ),
            )
        }
    }

    fun testConnection() {
        if (isSetupBusy()) return
        val pairingJson = validatedPairingDraft() ?: return
        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(
                    setup = current.setup.copy(attempt = SetupAttemptUi.Testing),
                )
            }
            val result = try {
                connectionManager.test(pairingJson)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            mutableState.update { current ->
                current.copy(
                    setup = current.setup.copy(
                        attempt = when (result) {
                            is ConnectionTestResult.Success ->
                                SetupAttemptUi.TestSucceeded(
                                    collectorId = result.metadata.collectorId,
                                )
                            is ConnectionTestResult.Failure ->
                                SetupAttemptUi.Failed(result.failure.code)
                            null ->
                                SetupAttemptUi.Failed("connection.test_failed")
                        },
                    ),
                )
            }
        }
    }

    fun saveConnection() {
        if (isSetupBusy()) return
        val pairingJson = validatedPairingDraft() ?: return
        connectionTransitionRevision += 1
        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(
                    setup = current.setup.copy(attempt = SetupAttemptUi.Saving),
                )
            }
            val result = try {
                connectionManager.testAndSave(pairingJson)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            when (result) {
                is ConnectionActivationResult.Success -> {
                    mutableState.update { current ->
                        current.copy(
                            destination = AppDestination.DETAILS,
                            collectorId = result.metadata.collectorId,
                            setup = current.setup.copy(
                                pairingJson = "",
                                pairingError = null,
                                pairingVisible = false,
                                attempt = SetupAttemptUi.Saved(
                                    collectorId = result.metadata.collectorId,
                                ),
                            ),
                            refresh = RefreshUi.Running,
                        )
                    }
                    runCatching(onConnectionActivated)
                    applyRefreshResult(callRefresh(RefreshTrigger.FOREGROUND))
                    runSnapshotCallback()
                }
                is ConnectionActivationResult.Failure -> {
                    mutableState.update { current ->
                        current.copy(
                            setup = current.setup.copy(
                                attempt = SetupAttemptUi.Failed(result.failure.code),
                            ),
                        )
                    }
                }
                null -> {
                    mutableState.update { current ->
                        current.copy(
                            setup = current.setup.copy(
                                attempt = SetupAttemptUi.Failed(
                                    "connection.save_failed",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        if (mutableState.value.refresh == RefreshUi.Running) return

        viewModelScope.launch {
            mutableState.update { it.copy(refresh = RefreshUi.Running) }
            applyRefreshResult(callRefresh(RefreshTrigger.MANUAL))
            runSnapshotCallback()
        }
    }

    private fun validatedPairingDraft(): String? {
        val draft = mutableState.value.setup.pairingJson
        val validation = SetupDraftValidator.validate(draft)
        if (!validation.isValid) {
            mutableState.update { current ->
                current.copy(
                    setup = current.setup.copy(
                        pairingError = validation.pairingError,
                        attempt = SetupAttemptUi.None,
                    ),
                )
            }
            return null
        }
        return draft
    }

    private fun isSetupBusy(): Boolean =
        when (mutableState.value.setup.attempt) {
            SetupAttemptUi.Testing,
            SetupAttemptUi.Saving,
            -> true
            else -> false
        }

    private suspend fun callRefresh(trigger: RefreshTrigger): RefreshResult? =
        try {
            repository.refresh(trigger)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun applyRefreshResult(result: RefreshResult?) {
        mutableState.update { current ->
            when (result) {
                is RefreshResult.Updated -> current.copy(
                    detail = result.content.toDetail(),
                    refresh = RefreshUi.Success,
                )
                is RefreshResult.UsingCache -> current.copy(
                    detail = result.content.toDetail(),
                    refresh = if (
                        result.failure.kind == RefreshFailureKind.GATE_CLOSED
                    ) {
                        RefreshUi.GateClosed
                    } else {
                        RefreshUi.StaleFallback
                    },
                )
                is RefreshResult.Failed -> current.copy(
                    refresh = if (
                        result.failure.kind == RefreshFailureKind.GATE_CLOSED
                    ) {
                        RefreshUi.GateClosed
                    } else {
                        RefreshUi.Failed(result.failure.code)
                    },
                )
                null -> current.copy(
                    refresh = RefreshUi.Failed("refresh_unavailable"),
                )
            }
        }
    }

    private suspend fun runSnapshotCallback() {
        try {
            onSnapshotChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Widget invalidation is best effort and cannot change repository
            // or connection state.
        }
    }

    private fun applyRepositoryState(repositoryState: RepositoryState) {
        mutableState.update { current ->
            when (repositoryState) {
                RepositoryState.Empty -> current.copy(detail = DetailUiModel.empty())
                is RepositoryState.Content -> current.copy(
                    detail = repositoryState.toDetail(),
                )
                is RepositoryState.Error -> current.copy(
                    detail = DetailUiModel.empty(),
                    refresh = if (
                        repositoryState.failure.kind == RefreshFailureKind.GATE_CLOSED
                    ) {
                        RefreshUi.GateClosed
                    } else {
                        RefreshUi.Failed(repositoryState.failure.code)
                    },
                )
            }
        }
    }

    private fun RepositoryState.Content.toDetail(): DetailUiModel =
        DetailUiMapper.map(
            candidate = snapshot.summary,
            phoneCacheState = phoneCacheState,
        )
}
