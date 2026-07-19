package dev.quotaarc.android

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.quotaarc.android.data.QuotaArcData
import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import dev.quotaarc.android.data.connection.QuotaArcConnectionManager
import dev.quotaarc.android.ui.AppViewModel
import dev.quotaarc.android.widget.QuotaArcWidget
import dev.quotaarc.android.widget.WidgetSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

internal class AppGraph private constructor(
    private val context: Context,
    private val connectionManager: QuotaArcConnectionManager,
    @Suppress("unused")
    private val applicationScope: CoroutineScope,
) {
    val viewModelFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass.isAssignableFrom(AppViewModel::class.java)) {
                    "Unsupported ViewModel ${modelClass.name}"
                }
                return AppViewModel(
                    connectionManager = connectionManager,
                    onSnapshotChanged = {
                        QuotaArcWidget().updateAll(context)
                    },
                ) as T
            }
        }

    companion object {
        fun create(context: Context): AppGraph {
            val applicationContext = context.applicationContext
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connectionManager = QuotaArcData.createConnectionManager(
                context = applicationContext,
                scope = applicationScope,
            )
            applicationScope.launch {
                connectionManager.restoreState
                    .filterNotNull()
                    .collect { restored ->
                        when (restored) {
                            is ConnectionRestoreResult.Ready ->
                                WidgetSyncScheduler
                                    .schedulePeriodicForActiveConnection(applicationContext)
                            ConnectionRestoreResult.Absent,
                            is ConnectionRestoreResult.CredentialUnavailable,
                            ConnectionRestoreResult.Invalid,
                            -> WidgetSyncScheduler.cancelPeriodic(applicationContext)
                        }
                    }
            }
            return AppGraph(
                context = applicationContext,
                connectionManager = connectionManager,
                applicationScope = applicationScope,
            )
        }
    }
}
