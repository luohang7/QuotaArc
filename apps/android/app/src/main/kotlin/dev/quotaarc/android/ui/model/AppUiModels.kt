package dev.quotaarc.android.ui.model

internal enum class AppDestination {
    SETUP,
    DETAILS,
}

internal data class SetupDraftUi(
    val endpoint: String = "",
    val token: String = "",
    val endpointError: SetupFieldError? = null,
    val tokenError: SetupFieldError? = null,
    val tokenVisible: Boolean = false,
    val attempt: SetupAttemptUi = SetupAttemptUi.None,
)

internal enum class SetupFieldError {
    ENDPOINT_REQUIRED,
    HTTPS_REQUIRED,
    ORIGIN_REQUIRED,
    INVALID_PORT,
    TOKEN_REQUIRED,
    TOKEN_TOO_LONG,
}

internal sealed interface SetupAttemptUi {
    data object None : SetupAttemptUi
    data object GateClosed : SetupAttemptUi
}

internal sealed interface RefreshUi {
    data object Idle : RefreshUi
    data object Running : RefreshUi
    data object Success : RefreshUi
    data object StaleFallback : RefreshUi
    data object GateClosed : RefreshUi
    data class Failed(val safeMessage: String) : RefreshUi
}

internal data class AppUiState(
    val destination: AppDestination = AppDestination.SETUP,
    val setup: SetupDraftUi = SetupDraftUi(),
    val detail: DetailUiModel = DetailUiModel.empty(),
    val refresh: RefreshUi = RefreshUi.Idle,
)

internal data class DetailUiModel(
    val hasSnapshot: Boolean,
    val generatedAtText: String?,
    val stale: Boolean,
    val phoneCacheState: PhoneCacheUiState,
    val limits: List<LimitUi>,
    val officialDaily: List<DailyUsageUi>,
    val localModels: List<UsageBreakdownUi>,
    val localProjects: List<UsageBreakdownUi>,
    val sources: List<SourceDiagnosticUi>,
) {
    companion object {
        fun empty(): DetailUiModel = DetailUiModel(
            hasSnapshot = false,
            generatedAtText = null,
            stale = false,
            phoneCacheState = PhoneCacheUiState.NO_SNAPSHOT,
            limits = emptyList(),
            officialDaily = emptyList(),
            localModels = emptyList(),
            localProjects = emptyList(),
            sources = listOf(
                SourceDiagnosticUi.gateClosed(SourceUiKind.QUOTA),
                SourceDiagnosticUi.gateClosed(SourceUiKind.ACCOUNT_USAGE),
                SourceDiagnosticUi.gateClosed(SourceUiKind.LOCAL_USAGE),
            ),
        )
    }
}

internal enum class PhoneCacheUiState {
    NO_SNAPSHOT,
    CURRENT,
    AGED,
    FALLBACK,
    STALE_FALLBACK,
}

internal data class LimitUi(
    val id: String,
    val name: String?,
    val windows: List<LimitWindowUi>,
)

internal data class LimitWindowUi(
    val windowMinutes: Long,
    val usedPercent: Double,
    val remainingPercent: Double,
    val resetText: String,
)

internal data class DailyUsageUi(
    val dateText: String,
    val tokensText: String,
)

internal data class UsageBreakdownUi(
    val id: String,
    val label: String,
    val processedTokensText: String,
    val newInputTokensText: String,
    val cachedInputTokensText: String,
    val outputTokensText: String,
    val reasoningTokensText: String,
)

internal enum class SourceUiKind {
    QUOTA,
    ACCOUNT_USAGE,
    LOCAL_USAGE,
}

internal enum class SourceUiStatus {
    OK,
    STALE,
    UNAVAILABLE,
    UNSUPPORTED,
    ERROR,
}

internal data class SourceDiagnosticUi(
    val kind: SourceUiKind,
    val status: SourceUiStatus,
    val collectedAtText: String?,
    val errorCode: String?,
    val safeErrorMessage: String?,
    val coverage: CoverageUi?,
) {
    companion object {
        fun gateClosed(kind: SourceUiKind): SourceDiagnosticUi =
            SourceDiagnosticUi(
                kind = kind,
                status = SourceUiStatus.UNAVAILABLE,
                collectedAtText = null,
                errorCode = "transport_gate_closed",
                safeErrorMessage = null,
                coverage = if (kind == SourceUiKind.LOCAL_USAGE) {
                    CoverageUi(files = 0, firstEventAtText = null, lastEventAtText = null)
                } else {
                    null
                },
            )
    }
}

internal data class CoverageUi(
    val files: Long,
    val firstEventAtText: String?,
    val lastEventAtText: String?,
)
