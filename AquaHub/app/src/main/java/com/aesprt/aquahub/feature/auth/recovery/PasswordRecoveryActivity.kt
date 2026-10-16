package com.aesprt.aquahub.feature.auth.recovery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.AdaptiveFormFrame
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.lifecycle.lifecycleScope
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.auth.SupabaseAuthDataSource
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import io.github.jan.supabase.auth.handleDeeplinks
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class PasswordRecoveryActivity : ComponentActivity() {
    private val authDataSource: SupabaseAuthDataSource by inject()
    private var state by mutableStateOf(RecoveryState.PROCESSING)
    private var errorMessage by mutableStateOf<String?>(null)
    private var isSaving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AquaHubTheme {
                PasswordRecoveryScreen(
                    state = state,
                    errorMessage = errorMessage,
                    isSaving = isSaving,
                    onSubmit = ::submitPassword,
                    onClose = { finish() }
                )
            }
        }
        handleRecoveryIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRecoveryIntent(intent)
    }

    private fun handleRecoveryIntent(intent: Intent?) {
        if (intent?.data == null) {
            state = RecoveryState.ERROR
            errorMessage = "The recovery link is missing or invalid. Request a new link."
            return
        }
        SupabaseClientProvider.client.handleDeeplinks(
            intent = intent,
            onSessionSuccess = {
                if (intent.data?.path == "/confirm") {
                    state = RecoveryState.COMPLETE
                    errorMessage = "Email verified. Return to AquaHub and sign in."
                } else {
                    state = RecoveryState.READY
                    errorMessage = null
                }
            },
            onError = { error ->
                state = RecoveryState.ERROR
                errorMessage = error.message ?: "The recovery link has expired. Request a new link."
            }
        )
    }

    private fun submitPassword(password: String, confirmation: String) {
        if (password != confirmation) {
            errorMessage = "Passwords do not match."
            return
        }
        lifecycleScope.launch {
            isSaving = true
            runCatching { authDataSource.completePasswordRecovery(password) }
                .onSuccess {
                    state = RecoveryState.COMPLETE
                    errorMessage = "Password updated. Return to AquaHub and sign in again."
                }
                .onFailure { error -> errorMessage = error.message ?: "Unable to update password." }
            isSaving = false
        }
    }
}

private enum class RecoveryState { PROCESSING, READY, ERROR, COMPLETE }

@Composable
private fun PasswordRecoveryScreen(
    state: RecoveryState,
    errorMessage: String?,
    isSaving: Boolean,
    onSubmit: (String, String) -> Unit,
    onClose: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AdaptiveFormFrame(modifier = Modifier.fillMaxSize(), maxWidth = 600.dp) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text("AquaHub password recovery", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            RecoveryState.PROCESSING -> Text("Verifying your secure recovery link…")
            RecoveryState.READY -> {
                Text("Choose a new password for this account.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !isSaving
                )
                Button(onClick = { onSubmit(password, confirmation) }, enabled = !isSaving) {
                    Text(if (isSaving) "Updating…" else "Update password")
                }
            }
            RecoveryState.ERROR, RecoveryState.COMPLETE -> {
                Text(errorMessage ?: "Recovery is unavailable.")
                Button(onClick = onClose) { Text("Close") }
            }
        }
        if (state == RecoveryState.READY && errorMessage != null) Text(errorMessage)
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun PasswordRecoveryScreenPreview() {
    AquaHubTheme {
        PasswordRecoveryScreen(
            state = RecoveryState.READY,
            errorMessage = null,
            isSaving = false,
            onSubmit = { _, _ -> },
            onClose = {}
        )
    }
}
