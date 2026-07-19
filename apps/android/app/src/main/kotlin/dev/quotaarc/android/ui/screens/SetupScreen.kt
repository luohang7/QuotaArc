package dev.quotaarc.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.quotaarc.android.R
import dev.quotaarc.android.ui.model.SetupAttemptUi
import dev.quotaarc.android.ui.model.SetupDraftUi
import dev.quotaarc.android.ui.model.SetupFieldError

@Composable
internal fun SetupScreen(
    state: SetupDraftUi,
    onEndpointChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onToggleTokenVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.setup_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.endpoint,
            onValueChange = onEndpointChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.endpoint_label)) },
            supportingText = {
                Text(
                    state.endpointError?.let { stringResource(it.messageResource()) }
                        ?: stringResource(R.string.endpoint_supporting),
                )
            },
            isError = state.endpointError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        OutlinedTextField(
            value = state.token,
            onValueChange = onTokenChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.token_label)) },
            supportingText = {
                state.tokenError?.let {
                    Text(stringResource(it.messageResource()))
                }
            },
            isError = state.tokenError != null,
            visualTransformation = if (state.tokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                TextButton(onClick = onToggleTokenVisibility) {
                    Text(
                        if (state.tokenVisible) {
                            stringResource(R.string.hide_token)
                        } else {
                            stringResource(R.string.show_token)
                        },
                    )
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onTestConnection,
            ) {
                Text(stringResource(R.string.test_connection))
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = onSave,
            ) {
                Text(stringResource(R.string.save_connection))
            }
        }

        if (state.attempt == SetupAttemptUi.GateClosed) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.gate_closed_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.gate_closed_body))
                }
            }
        }

        Text(
            text = stringResource(R.string.setup_not_saved),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun SetupFieldError.messageResource(): Int = when (this) {
    SetupFieldError.ENDPOINT_REQUIRED -> R.string.endpoint_required
    SetupFieldError.HTTPS_REQUIRED -> R.string.endpoint_https_required
    SetupFieldError.ORIGIN_REQUIRED -> R.string.endpoint_origin_required
    SetupFieldError.INVALID_PORT -> R.string.endpoint_port_invalid
    SetupFieldError.TOKEN_REQUIRED -> R.string.token_required
    SetupFieldError.TOKEN_TOO_LONG -> R.string.token_too_long
}
