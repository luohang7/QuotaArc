package dev.quotaarc.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.quotaarc.android.data.QuotaArcData
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class AppGraph private constructor(
    private val repository: QuotaArcRepository,
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
                return AppViewModel(repository) as T
            }
        }

    companion object {
        fun create(context: Context): AppGraph {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return AppGraph(
                repository = QuotaArcData.createGateClosedRepository(
                    context = context.applicationContext,
                    scope = applicationScope,
                ),
                applicationScope = applicationScope,
            )
        }
    }
}
