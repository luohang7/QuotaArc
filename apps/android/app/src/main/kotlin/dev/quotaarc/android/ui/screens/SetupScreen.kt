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
    onPairingJsonChanged: (String) -> Unit,
    onTogglePairingVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
) {
    val busy =
        state.attempt == SetupAttemptUi.Testing ||
            state.attempt == SetupAttemptUi.Saving

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
            value = state.pairingJson,
            onValueChange = onPairingJsonChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            minLines = 6,
            maxLines = 12,
            label = { Text(stringResource(R.string.pairing_bundle_label)) },
            supportingText = {
                Text(
                    state.pairingError?.let {
                        stringResource(it.messageResource())
                    } ?: stringResource(R.string.pairing_bundle_supporting),
                )
            },
            isError = state.pairingError != null,
            visualTransformation = if (state.pairingVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                TextButton(
                    enabled = !busy,
                    onClick = onTogglePairingVisibility,
                ) {
                    Text(
                        if (state.pairingVisible) {
                            stringResource(R.string.hide_pairing_bundle)
                        } else {
                            stringResource(R.string.show_pairing_bundle)
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
                enabled = !busy,
                onClick = onTestConnection,
            ) {
                Text(
                    if (state.attempt == SetupAttemptUi.Testing) {
                        stringResource(R.string.testing_connection)
                    } else {
                        stringResource(R.string.test_connection)
                    },
                )
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onSave,
            ) {
                Text(
                    if (state.attempt == SetupAttemptUi.Saving) {
                        stringResource(R.string.saving_connection)
                    } else {
                        stringResource(R.string.save_connection)
                    },
                )
            }
        }

        SetupAttemptCard(state.attempt)

        Text(
            text = stringResource(R.string.pairing_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupAttemptCard(attempt: SetupAttemptUi) {
    val card = when (attempt) {
        SetupAttemptUi.None -> return
        SetupAttemptUi.Testing ->
            Triple(
                R.string.testing_connection,
                R.string.connection_probe_body,
                false,
            )
        SetupAttemptUi.Saving ->
            Triple(
                R.string.saving_connection,
                R.string.connection_save_body,
                false,
            )
        is SetupAttemptUi.TestSucceeded ->
            Triple(
                R.string.connection_test_succeeded,
                R.string.connection_test_succeeded_body,
                false,
            )
        is SetupAttemptUi.Saved ->
            Triple(
                R.string.connection_saved,
                R.string.connection_saved_body,
                false,
            )
        is SetupAttemptUi.Failed ->
            Triple(
                R.string.connection_failed,
                R.string.connection_failed_body,
                true,
            )
    }
    val body = when (attempt) {
        is SetupAttemptUi.TestSucceeded ->
            stringResource(card.second, attempt.collectorId)
        is SetupAttemptUi.Saved ->
            stringResource(card.second, attempt.collectorId)
        is SetupAttemptUi.Failed ->
            stringResource(card.second, attempt.safeCode)
        else -> stringResource(card.second)
    }
    Card(
        colors = if (card.third) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(card.first),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(body)
        }
    }
}

private fun SetupFieldError.messageResource(): Int = when (this) {
    SetupFieldError.PAIRING_REQUIRED -> R.string.pairing_bundle_required
    SetupFieldError.PAIRING_TOO_LARGE -> R.string.pairing_bundle_too_large
}
