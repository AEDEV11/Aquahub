package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Owner
import com.aesprt.aquahub.domain.model.Station
import kotlinx.coroutines.flow.Flow

/**
 * Supabase authenticated the Google identity, but AquaHub has not created
 * its owner/business/station profile yet. The UI should continue through the
 * registration flow instead of showing this as a sign-in failure.
 */
class GoogleAccountNeedsSetupException : Exception()

/** A verified sign-in was rejected because this device still holds another account's local cache. */
class LocalAccountSwitchConfirmationRequiredException : Exception(
    "This device has saved business data from another account. Recover that account, or review the option to clear its data from this device."
)

interface AuthRepository {
    /**
     * Clears cached tenant data before starting a brand-new account registration.
     *
     * A device with pending changes from a previous tenant must report a
     * recoverable failure instead of throwing from the registration coroutine.
     */
    suspend fun prepareForRegistration(): Result<Unit> = Result.success(Unit)
    suspend fun loginWithEmail(email: String, password: String): Result<Owner>
    /** Restores the local tenant cache after a passwordless Supabase session is established. */
    suspend fun restoreCurrentSession(): Result<Owner> =
        Result.failure(UnsupportedOperationException("Session restore is unavailable"))
    /** Clears a known previous account's local cache after an explicit user confirmation. */
    suspend fun clearLocalDataForAccountSwitch(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Account switching is unavailable"))
    suspend fun loginWithGoogle(idToken: String): Result<Owner> =
        Result.failure(UnsupportedOperationException("Google sign-in is unavailable"))
    suspend fun register(
        owner: Owner,
        password: String,
        station: Station? = null,
        marketingConsent: Boolean = false
    ): Result<Owner>
    suspend fun resetPassword(email: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Password recovery is unavailable"))
    suspend fun deleteAccount(): Result<Unit> = Result.failure(UnsupportedOperationException("Account deletion is unavailable"))
    suspend fun reauthenticate(password: String): Result<Unit> = Result.failure(UnsupportedOperationException("Password reauthentication is unavailable"))
    suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> = Result.failure(UnsupportedOperationException("Google reauthentication is unavailable"))
    suspend fun logout()
    suspend fun isLoggedIn(): Boolean
    suspend fun hasCompletedOnboarding(): Boolean
    suspend fun setCompletedOnboarding(completed: Boolean)
    fun observeCurrentOwner(): Flow<Owner?>
    suspend fun getCurrentOwner(): Owner?
}
