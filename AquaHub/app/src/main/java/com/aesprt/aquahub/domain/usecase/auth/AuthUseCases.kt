package com.aesprt.aquahub.domain.usecase.auth

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.domain.model.Owner
import com.aesprt.aquahub.domain.repository.AuthRepository

class LoginUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<Owner> {
        if (email.isBlank()) return Result.failure(AppError.ValidationError("Email address is required"))
        if (password.isBlank()) return Result.failure(AppError.ValidationError("Password is required"))
        if (!email.contains("@")) return Result.failure(AppError.ValidationError("Enter a valid email address"))
        return authRepository.loginWithEmail(email, password)
    }
}

class LoginWithGoogleUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(idToken: String): Result<Owner> {
        if (idToken.isBlank()) return Result.failure(AppError.AuthenticationError("Google sign-in was cancelled"))
        return authRepository.loginWithGoogle(idToken)
    }
}

class ResetPasswordUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(email: String): Result<Unit> {
        if (email.isBlank() || !email.contains("@")) {
            return Result.failure(AppError.ValidationError("Enter the email address linked to your owner account"))
        }
        return authRepository.resetPassword(email.trim())
    }
}

class DeleteAccountUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): Result<Unit> = authRepository.deleteAccount()
}

class ReauthenticateUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(password: String): Result<Unit> = authRepository.reauthenticate(password)
}

class ReauthenticateWithGoogleUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(idToken: String): Result<Unit> = authRepository.reauthenticateWithGoogle(idToken)
}

class LogoutUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke() = authRepository.logout()
}

class GetCurrentSessionUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): Boolean = authRepository.isLoggedIn()
}

class ObserveCurrentOwnerUseCase(private val authRepository: AuthRepository) {
    operator fun invoke() = authRepository.observeCurrentOwner()
}

class GetCurrentOwnerUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): Owner? = authRepository.getCurrentOwner()
}

class HasCompletedOnboardingUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): Boolean = authRepository.hasCompletedOnboarding()
}

class SetCompletedOnboardingUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(completed: Boolean = true) = authRepository.setCompletedOnboarding(completed)
}
