package dev.quotaarc.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.quotaarc.android.ui.QuotaArcApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as QuotaArcApplication).graph
        setContent {
            QuotaArcApp(
                viewModel = viewModel(factory = graph.viewModelFactory),
            )
        }
    }
}
