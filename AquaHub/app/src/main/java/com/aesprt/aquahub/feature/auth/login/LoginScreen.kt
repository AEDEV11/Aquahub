package com.aesprt.aquahub.feature.auth.login

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.R
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.ui.theme.*
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import android.widget.Toast

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToSubscription: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LoginEvent.LoginSuccess -> onLoginSuccess()
                LoginEvent.GoogleNeedsOnboarding -> onNavigateToSubscription()
                is LoginEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    LoginContent(
        uiState = uiState,
        onPhoneChange = viewModel::onPhoneChange,
        onEmailChange = viewModel::onEmailChange,
        onOtpChange = viewModel::onOtpChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onLoginClick = viewModel::login,
        onSelectPhoneLogin = { viewModel.selectMode(LoginMode.PHONE_OTP) },
        onRequestPhoneOtp = viewModel::requestOtp,
        onVerifyPhoneOtp = viewModel::verifyOtp,
        onSelectEmailLogin = { viewModel.selectMode(LoginMode.EMAIL) },
        onForgotPasswordClick = viewModel::openPasswordReset,
        onClosePasswordReset = viewModel::closePasswordReset,
        onResetPassword = viewModel::resetPassword,
        onOpenLocalDataClearConfirmation = viewModel::openLocalDataClearConfirmation,
        onCloseLocalDataClearConfirmation = viewModel::closeLocalDataClearConfirmation,
        onConfirmClearPreviousAccountData = viewModel::confirmClearPreviousAccountData,
        onGoogleSignInClick = {
            val webClientId = context.getString(R.string.default_web_client_id)
            if (webClientId.startsWith("YOUR_")) {
                viewModel.showError("Google sign-in is not configured yet. Add the Supabase Google OAuth client ID.")
            } else {
                coroutineScope.launch {
                    try {
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setServerClientId(webClientId)
                            .setFilterByAuthorizedAccounts(false)
                            .setAutoSelectEnabled(false)
                            .build()
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()
                        val result = credentialManager.getCredential(context, request)
                        val credential = result.credential
                        if (credential is CustomCredential &&
                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        ) {
                            viewModel.signInWithGoogle(
                                GoogleIdTokenCredential.createFrom(credential.data).idToken
                            )
                        } else {
                            viewModel.showError("Google returned an unsupported credential.")
                        }
                    } catch (error: GetCredentialException) {
                        viewModel.showError(error.message ?: "Google sign-in was cancelled")
                    }
                }
            }
        },
        onNavigateToSubscription = onNavigateToSubscription
    )
}

@Composable
fun LoginContent(
    uiState: LoginUiState,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit = {},
    onOtpChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onLoginClick: () -> Unit,
    onSelectPhoneLogin: () -> Unit = {},
    onRequestPhoneOtp: () -> Unit = {},
    onVerifyPhoneOtp: () -> Unit = {},
    onSelectEmailLogin: () -> Unit = {},
    onForgotPasswordClick: () -> Unit = {},
    onClosePasswordReset: () -> Unit = {},
    onResetPassword: (String) -> Unit = {},
    onOpenLocalDataClearConfirmation: () -> Unit = {},
    onCloseLocalDataClearConfirmation: () -> Unit = {},
    onConfirmClearPreviousAccountData: () -> Unit = {},
    onGoogleSignInClick: () -> Unit = {},
    onNavigateToSubscription: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val emailError = when {
        uiState.email.isBlank() -> "Email address is required"
        !isValidEmail(uiState.email) -> "Enter a valid email address"
        else -> null
    }.takeIf { uiState.email.isNotBlank() }
    val passwordError = when {
        uiState.password.isBlank() -> "Password is required"
        uiState.password.length < 8 -> "Use at least 8 characters"
        else -> null
    }.takeIf { uiState.password.isNotBlank() }
    val phoneError = when {
        uiState.phone.isBlank() -> "Phone number is required"
        !isValidPhilippinePhone(uiState.phone) -> "Enter a valid Philippine mobile number"
        else -> null
    }.takeIf { uiState.phone.isNotBlank() }
    val otpError = when {
        uiState.otp.length != 6 -> "Enter the 6-digit code"
        else -> null
    }.takeIf { uiState.otp.isNotBlank() }
    val loginFormValid = if (uiState.mode == LoginMode.EMAIL) {
        isValidEmail(uiState.email) && uiState.password.length >= 8
    } else {
        isValidPhilippinePhone(uiState.phone) && (!uiState.otpRequested || uiState.otp.length == 6)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveFormFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 640.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AquaHubSpacing.large)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
            Spacer(modifier = Modifier.height(AquaHubSpacing.large))

            // Enhanced Logo with Dark-Mode Contrast Backing & Glowing Shadow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(180.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = CircleShape,
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                GlassBorderAqua
                            )
                        ),
                        shape = CircleShape
                    )
                    .padding(12.dp)
            ) {
                AquaHubLogo(modifier = Modifier.fillMaxSize())
            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.large))

            // Glassmorphic Form Container
            AquaHubGlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Welcome back",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Sign in to manage your station operations",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumLarge))

                if (uiState.mode == LoginMode.EMAIL) {
                    AquaHubTextField(
                        value = uiState.email,
                        onValueChange = onEmailChange,
                        label = "Email address",
                        placeholder = "you@example.com",
                        leadingIcon = Icons.Rounded.Email,
                        isError = emailError != null,
                        errorMessage = emailError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

                    AquaHubPasswordField(
                        value = uiState.password,
                        onValueChange = onPasswordChange,
                        label = "Password",
                        isError = passwordError != null,
                        errorMessage = passwordError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); onLoginClick() })
                    )

                    TextButton(
                        onClick = onForgotPasswordClick,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Forgot password?") }
                } else {
                    AquaHubTextField(
                        value = uiState.phone,
                        onValueChange = onPhoneChange,
                        label = "Phone number",
                        placeholder = "09XX XXX XXXX",
                        leadingIcon = Icons.Rounded.Phone,
                        isError = phoneError != null,
                        errorMessage = phoneError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); if (uiState.otpRequested) onVerifyPhoneOtp() else onRequestPhoneOtp() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.otpRequested) {
                        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))
                        AquaHubTextField(
                            value = uiState.otp,
                            onValueChange = onOtpChange,
                            label = "Verification code",
                            placeholder = "6-digit code",
                            leadingIcon = Icons.Rounded.Lock,
                            isError = otpError != null,
                            errorMessage = otpError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); onVerifyPhoneOtp() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    TextButton(
                        onClick = onSelectEmailLogin,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Use email and password instead") }
                }

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AquaHubShapes.chip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(AquaHubSpacing.mediumSmall),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (uiState.canClearPreviousAccountData) {
                    TextButton(
                        onClick = onOpenLocalDataClearConfirmation,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Clear old account data from this device")
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.large))

                // Sign In Button
                AquaHubPrimaryButton(
                    text = when {
                        uiState.mode == LoginMode.EMAIL -> "Sign In"
                        uiState.otpRequested -> "Verify phone number"
                        else -> "Send verification code"
                    },
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = {
                        focusManager.clearFocus()
                        if (uiState.mode == LoginMode.EMAIL) onLoginClick()
                        else if (uiState.otpRequested) onVerifyPhoneOtp() else onRequestPhoneOtp()
                    },
                    enabled = loginFormValid,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                if (uiState.mode == LoginMode.EMAIL) {
                    OutlinedButton(
                        onClick = onGoogleSignInClick,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = AquaHubShapes.button,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = "G",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Continue with Google")
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    AquaHubOutlinedButton(
                        text = "Continue with phone",
                        onClick = onSelectPhoneLogin,
                        enabled = !uiState.isLoading,
                        icon = Icons.Rounded.Phone,
                        borderColor = MaterialTheme.colorScheme.outline,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    )
                }

                if (uiState.infoMessage != null) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = AquaHubShapes.chip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.infoMessage,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(AquaHubSpacing.mediumSmall),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Don’t have an account?",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    TextButton(
                        onClick = onNavigateToSubscription,
                        enabled = !uiState.isLoading,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Register & subscribe",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

            Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

            Spacer(modifier = Modifier.height(AquaHubSpacing.large))
            }
        }
    }

    if (uiState.showPasswordResetDialog) {
        var email by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onClosePasswordReset,
            icon = { Icon(Icons.Rounded.Email, contentDescription = null) },
            title = { Text("Reset your password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                    Text("Enter the recovery email linked during owner registration. We’ll send a secure Supabase reset link.")
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Recovery email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onResetPassword(email) },
                    enabled = email.contains("@") && !uiState.isLoading
                ) { Text("Send reset link") }
            },
            dismissButton = { TextButton(onClick = onClosePasswordReset) { Text("Cancel") } }
        )
    }

    if (uiState.showLocalDataClearDialog) {
        AlertDialog(
            onDismissRequest = onCloseLocalDataClearConfirmation,
            title = { Text("Clear saved business data?") },
            text = {
                Text(
                    "This removes saved AquaHub business data from this device so you can sign in with another account. " +
                        "It does not delete your previous account or its cloud data. Any local-only records that never reached the cloud will be lost."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmClearPreviousAccountData,
                    enabled = !uiState.isLoading,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (uiState.isLoading) "Clearing…" else "Clear device data")
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseLocalDataClearConfirmation, enabled = !uiState.isLoading) {
                    Text("Keep data")
                }
            }
        )
    }
}

private fun isValidEmail(value: String): Boolean =
    Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value.trim())

private fun isValidPhilippinePhone(value: String): Boolean {
    val digits = value.filter(Char::isDigit)
    return (digits.length == 11 && digits.startsWith("09")) ||
        (digits.length == 12 && digits.startsWith("639"))
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun LoginScreenPreview() {
    AquaHubTheme {
        LoginContent(
            uiState = LoginUiState(),
            onPhoneChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onLoginClick = {},
        )
    }
}
