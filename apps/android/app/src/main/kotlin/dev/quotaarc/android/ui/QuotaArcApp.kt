package dev.quotaarc.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quotaarc.android.R
import dev.quotaarc.android.ui.model.AppDestination
import dev.quotaarc.android.ui.model.RefreshUi
import dev.quotaarc.android.ui.screens.DetailScreen
import dev.quotaarc.android.ui.screens.SetupScreen
import dev.quotaarc.android.ui.theme.QuotaArcTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuotaArcApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    QuotaArcTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = stringResource(R.string.provisional_build),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (state.destination == AppDestination.DETAILS) {
                            TextButton(
                                enabled = state.refresh != RefreshUi.Running,
                                onClick = viewModel::refresh,
                            ) {
                                Text(
                                    text = if (state.refresh == RefreshUi.Running) {
                                        stringResource(R.string.refresh_in_progress)
                                    } else {
                                        stringResource(R.string.refresh)
                                    },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    DestinationItem(
                        selected = state.destination == AppDestination.SETUP,
                        label = stringResource(R.string.nav_setup),
                        onClick = { viewModel.navigate(AppDestination.SETUP) },
                    )
                    DestinationItem(
                        selected = state.destination == AppDestination.DETAILS,
                        label = stringResource(R.string.nav_details),
                        onClick = { viewModel.navigate(AppDestination.DETAILS) },
                    )
                }
            },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                when (state.destination) {
                    AppDestination.SETUP -> SetupScreen(
                        state = state.setup,
                        onEndpointChanged = viewModel::updateEndpoint,
                        onTokenChanged = viewModel::updateToken,
                        onToggleTokenVisibility = viewModel::toggleTokenVisibility,
                        onTestConnection = viewModel::attemptConnectionOrSave,
                        onSave = viewModel::attemptConnectionOrSave,
                    )

                    AppDestination.DETAILS -> DetailScreen(
                        model = state.detail,
                        refresh = state.refresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationItem(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    .clearAndSetSemantics {},
            )
        },
        label = { Text(label) },
    )
}
