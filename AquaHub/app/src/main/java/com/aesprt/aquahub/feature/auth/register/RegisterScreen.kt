package com.aesprt.aquahub.feature.auth.register

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingUiState
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingViewModel
import com.aesprt.aquahub.feature.auth.onboarding.RegistrationAuthMethod
import com.aesprt.aquahub.ui.theme.*

@Composable
fun RegisterScreen(
    viewModel: OnboardingViewModel,
    planId: String? = null,
    billing: String? = null,
    onBackClick: () -> Unit,
    onContinueToSetup: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(planId, billing) {
        if (!planId.isNullOrBlank()) {
            viewModel.selectPlan(planId, billing)
        }
    }

    RegisterContent(
        uiState = uiState,
        onOwnerNameChange = viewModel::onOwnerNameChange,
        onOwnerPhoneChange = viewModel::onOwnerPhoneChange,
        onOwnerEmailChange = viewModel::onOwnerEmailChange,
        onSelectAuthMethod = viewModel::selectAuthMethod,
        onPhoneVerificationCodeChange = viewModel::onPhoneVerificationCodeChange,
        onResendPhoneOtp = viewModel::resendPhoneOtp,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onMarketingConsentChange = viewModel::onMarketingConsentChange,
        onBackClick = onBackClick,
        onSubmit = {
            when {
                uiState.authMethod == RegistrationAuthMethod.PHONE && !uiState.phoneVerificationSent ->
                    viewModel.requestPhoneOtp()
                uiState.authMethod == RegistrationAuthMethod.PHONE && !uiState.phoneVerified ->
                    viewModel.confirmPhoneOtp()
                viewModel.validateOwnerAccount() ->
                onContinueToSetup()
            }
        },
    )
}

@Composable
fun RegisterContent(
    uiState: OnboardingUiState,
    onOwnerNameChange: (String) -> Unit,
    onOwnerPhoneChange: (String) -> Unit,
    onOwnerEmailChange: (String) -> Unit,
    onSelectAuthMethod: (RegistrationAuthMethod) -> Unit,
    onPhoneVerificationCodeChange: (String) -> Unit,
    onResendPhoneOtp: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onMarketingConsentChange: (Boolean) -> Unit = {},
    onBackClick: () -> Unit,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val nameError = when {
        uiState.ownerName.isBlank() -> "Full name is required"
        uiState.ownerName.trim().length < 2 -> "Enter at least 2 characters"
        else -> null
    }.takeIf { uiState.ownerName.isNotBlank() || uiState.errorMessage != null }
    val phoneError = when {
        uiState.ownerPhone.isBlank() -> "Mobile phone is required"
        !isValidPhilippinePhone(uiState.ownerPhone) -> "Enter a valid Philippine mobile number"
        else -> null
    }.takeIf { uiState.ownerPhone.isNotBlank() || uiState.errorMessage != null }
    val emailRequired = uiState.authMethod == RegistrationAuthMethod.EMAIL && !uiState.isGoogleOnboarding
    val emailError = when {
        uiState.ownerEmail.isBlank() && emailRequired -> "Email address is required"
        uiState.ownerEmail.isNotBlank() && !isValidEmail(uiState.ownerEmail) -> "Enter a valid email address"
        else -> null
    }.takeIf { uiState.ownerEmail.isNotBlank() || emailRequired && uiState.errorMessage != null }
    val passwordError = when {
        uiState.password.isBlank() -> "Password is required"
        uiState.password.length < 8 -> "Use at least 8 characters"
        else -> null
    }.takeIf { uiState.authMethod == RegistrationAuthMethod.EMAIL && !uiState.isGoogleOnboarding && uiState.password.isNotBlank() }
    val confirmPasswordError = when {
        uiState.confirmPassword.isBlank() -> "Confirm your password"
        uiState.confirmPassword != uiState.password -> "Passwords do not match"
        else -> null
    }.takeIf { uiState.authMethod == RegistrationAuthMethod.EMAIL && !uiState.isGoogleOnboarding && uiState.confirmPassword.isNotBlank() }
    val otpError = when {
        uiState.phoneVerificationCode.length != 6 -> "Enter the 6-digit code"
        else -> null
    }.takeIf { uiState.authMethod == RegistrationAuthMethod.PHONE && uiState.phoneVerificationSent && uiState.phoneVerificationCode.isNotBlank() }

    Scaffold(
        topBar = {
            OnboardingStepperHeader(
                currentStep = 1,
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveFormFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 760.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AquaHubSpacing.medium)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            // Registration Form
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Personal Information",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Choose one secure way to sign in and manage your station",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                // Full Name
                AquaHubTextField(
                    value = uiState.ownerName,
                    onValueChange = onOwnerNameChange,
                    label = if (uiState.isGoogleOnboarding) "Full Name (from Google) *" else "Full Name *",
                    placeholder = "e.g. Juan Dela Cruz",
                    leadingIcon = Icons.Rounded.Person,
                    isError = nameError != null,
                    errorMessage = nameError,
                    readOnly = uiState.isGoogleOnboarding && uiState.ownerName.isNotBlank(),
                    supportingText = if (uiState.isGoogleOnboarding && uiState.ownerName.isNotBlank()) {
                        "Prefilled from your Google account"
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                Text(
                    text = "Sign-in method",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(AquaHubSpacing.extraSmall))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = AquaHubShapes.cardSecondary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RegistrationMethodOption(
                            label = "Email",
                            selected = uiState.authMethod == RegistrationAuthMethod.EMAIL,
                            enabled = !uiState.isLoading,
                            onClick = { onSelectAuthMethod(RegistrationAuthMethod.EMAIL) },
                            modifier = Modifier.weight(1f)
                        )
                        RegistrationMethodOption(
                            label = "Phone + OTP",
                            selected = uiState.authMethod == RegistrationAuthMethod.PHONE,
                            enabled = !uiState.isLoading,
                            onClick = { onSelectAuthMethod(RegistrationAuthMethod.PHONE) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                AquaHubPhoneField(
                    value = uiState.ownerPhone,
                    onValueChange = onOwnerPhoneChange,
                    label = "Mobile Phone *",
                    placeholder = "09XX XXX XXXX",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    isError = phoneError != null,
                    errorMessage = phoneError
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Email Address
                AquaHubTextField(
                    value = uiState.ownerEmail,
                    onValueChange = onOwnerEmailChange,
                    label = when {
                        uiState.isGoogleOnboarding -> "Google Email"
                        uiState.authMethod == RegistrationAuthMethod.EMAIL -> "Email Address *"
                        else -> "Email Address (Optional)"
                    },
                    placeholder = "e.g. juan@gmail.com",
                    leadingIcon = Icons.Rounded.Email,
                    readOnly = uiState.isGoogleOnboarding && uiState.ownerEmail.isNotBlank(),
                    supportingText = if (uiState.isGoogleOnboarding && uiState.ownerEmail.isNotBlank()) {
                        "From your Google account"
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    isError = emailError != null,
                    errorMessage = emailError
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                if (uiState.authMethod == RegistrationAuthMethod.PHONE) {
                    if (uiState.phoneVerificationSent) {
                        AquaHubTextField(
                            value = uiState.phoneVerificationCode,
                            onValueChange = onPhoneVerificationCodeChange,
                            label = "Verification code",
                            placeholder = "6-digit code",
                            leadingIcon = Icons.Rounded.VerifiedUser,
                            isError = otpError != null,
                            errorMessage = otpError,
                            supportingText = if (otpError == null) "Enter the 6-digit code sent by SMS" else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); onSubmit() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (uiState.phoneOtpCooldownSeconds > 0) {
                            Text(
                                text = "Use the latest SMS code. You can request another in ${uiState.phoneOtpCooldownSeconds}s.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(top = AquaHubSpacing.extraSmall)
                            )
                        } else {
                            TextButton(
                                onClick = onResendPhoneOtp,
                                enabled = !uiState.isLoading,
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("Resend code") }
                        }
                    } else {
                        Text(
                            text = "We’ll verify this number with a one-time code. No password is needed.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                } else if (uiState.isGoogleOnboarding) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = AquaHubShapes.cardSecondary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "You’ll continue signing in with your Google account. No password is required.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                } else {
                    AquaHubPasswordField(
                        value = uiState.password,
                        onValueChange = onPasswordChange,
                        label = "Password * (min 8 chars)",
                        isError = passwordError != null,
                        errorMessage = passwordError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    AquaHubPasswordField(
                        value = uiState.confirmPassword,
                        onValueChange = onConfirmPasswordChange,
                        label = "Confirm Password *",
                        isError = confirmPasswordError != null,
                        errorMessage = confirmPasswordError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                onSubmit()
                            }
                        )
                    )
                }

                if (isValidEmail(uiState.ownerEmail)) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = uiState.marketingConsent,
                                enabled = !uiState.isLoading,
                                role = Role.Checkbox,
                                onValueChange = onMarketingConsentChange
                            )
                    ) {
                        Checkbox(
                            checked = uiState.marketingConsent,
                            onCheckedChange = null,
                            enabled = !uiState.isLoading
                        )
                        Spacer(modifier = Modifier.width(AquaHubSpacing.extraSmall))
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Text(
                                text = "Optional: receive AquaHub updates by email",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "I agree to receive product news, helpful tips, and promotional offers. I can change this in Settings at any time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AquaHubShapes.chip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(AquaHubSpacing.small),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

                // Submit Button
                AquaHubPrimaryButton(
                    text = when {
                        uiState.authMethod == RegistrationAuthMethod.PHONE && !uiState.phoneVerificationSent -> "Send verification code"
                        uiState.authMethod == RegistrationAuthMethod.PHONE && !uiState.phoneVerified -> "Verify phone number"
                        else -> "Continue to Station Setup"
                    },
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                    isLoading = uiState.isLoading,
                    loadingText = when {
                        uiState.authMethod == RegistrationAuthMethod.PHONE && !uiState.phoneVerificationSent -> "Sending code..."
                        uiState.authMethod == RegistrationAuthMethod.PHONE && !uiState.phoneVerified -> "Verifying..."
                        else -> "Continuing..."
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

                Spacer(modifier = Modifier.height(AquaHubSpacing.extraLarge))
            }
        }

    }
}

private fun isValidEmail(value: String): Boolean =
    Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value.trim())

private fun isValidPhilippinePhone(value: String): Boolean {
    val digits = value.filter(Char::isDigit)
    return (digits.length == 11 && digits.startsWith("09")) ||
        (digits.length == 12 && digits.startsWith("639"))
}

@Composable
private fun RegistrationMethodOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = AquaHubShapes.chip,
        modifier = modifier
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun RegisterScreenPreview() {
    AquaHubTheme {
        RegisterContent(
            uiState = OnboardingUiState(),
            onOwnerNameChange = {},
            onOwnerPhoneChange = {},
            onOwnerEmailChange = {},
            onSelectAuthMethod = {},
            onPhoneVerificationCodeChange = {},
            onResendPhoneOtp = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onTogglePasswordVisibility = {},
            onBackClick = {},
            onSubmit = {}
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun RegisterGoogleScreenPreview() {
    AquaHubTheme {
        RegisterContent(
            uiState = OnboardingUiState(
                ownerName = "Juan Dela Cruz",
                ownerPhone = "+639171234567",
                ownerEmail = "juan@example.com",
                isGoogleOnboarding = true
            ),
            onOwnerNameChange = {},
            onOwnerPhoneChange = {},
            onOwnerEmailChange = {},
            onSelectAuthMethod = {},
            onPhoneVerificationCodeChange = {},
            onResendPhoneOtp = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onTogglePasswordVisibility = {},
            onBackClick = {},
            onSubmit = {}
        )
    }
}
