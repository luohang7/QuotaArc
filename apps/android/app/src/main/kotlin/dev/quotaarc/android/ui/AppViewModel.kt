package dev.quotaarc.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val repository: QuotaArcRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
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
    }

    fun navigate(destination: AppDestination) {
        mutableState.update { it.copy(destination = destination) }
    }

    fun updateEndpoint(value: String) {
        mutableState.update { current ->
            current.copy(
                setup = current.setup.copy(
                    endpoint = value,
                    endpointError = null,
                    attempt = SetupAttemptUi.None,
                ),
            )
        }
    }

    fun updateToken(value: String) {
        mutableState.update { current ->
            current.copy(
                setup = current.setup.copy(
                    token = value,
                    tokenError = null,
                    attempt = SetupAttemptUi.None,
                ),
            )
        }
    }

    fun toggleTokenVisibility() {
        mutableState.update { current ->
            current.copy(
                setup = current.setup.copy(tokenVisible = !current.setup.tokenVisible),
            )
        }
    }

    fun attemptConnectionOrSave() {
        val draft = mutableState.value.setup
        val validation = SetupDraftValidator.validate(draft.endpoint, draft.token)
        if (!validation.isValid) {
            mutableState.update { current ->
                current.copy(
                    setup = current.setup.copy(
                        endpointError = validation.endpointError,
                        tokenError = validation.tokenError,
                        attempt = SetupAttemptUi.None,
                    ),
                )
            }
            return
        }

        // The draft is intentionally not passed to a transport or persistence
        // API. Clear the secret immediately after reporting the closed gate.
        mutableState.update { current ->
            current.copy(
                setup = current.setup.copy(
                    token = "",
                    tokenVisible = false,
                    endpointError = null,
                    tokenError = null,
                    attempt = SetupAttemptUi.GateClosed,
                ),
            )
        }
    }

    fun refresh() {
        if (mutableState.value.refresh == RefreshUi.Running) return

        viewModelScope.launch {
            mutableState.update { it.copy(refresh = RefreshUi.Running) }
            val result = try {
                repository.refresh(RefreshTrigger.MANUAL)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            mutableState.update { current ->
                when (result) {
                    is RefreshResult.Updated -> current.copy(
                        detail = result.content.toDetail(),
                        refresh = RefreshUi.Success,
                    )

                    is RefreshResult.UsingCache -> current.copy(
                        detail = result.content.toDetail(),
                        refresh = if (result.failure.kind == RefreshFailureKind.GATE_CLOSED) {
                            RefreshUi.GateClosed
                        } else {
                            RefreshUi.StaleFallback
                        },
                    )

                    is RefreshResult.Failed -> current.copy(
                        refresh = if (result.failure.kind == RefreshFailureKind.GATE_CLOSED) {
                            RefreshUi.GateClosed
                        } else {
                            RefreshUi.Failed(result.failure.code)
                        },
                    )

                    null -> current.copy(refresh = RefreshUi.Failed("refresh_unavailable"))
                }
            }
        }
    }

    private fun applyRepositoryState(repositoryState: RepositoryState) {
        mutableState.update { current ->
            when (repositoryState) {
                RepositoryState.Empty -> current.copy(detail = DetailUiModel.empty())
                is RepositoryState.Content -> current.copy(detail = repositoryState.toDetail())
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
