package com.aesprt.aquahub.data.supabase.auth

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Supabase-only authentication boundary for the owner application. */
class SupabaseAuthDataSource {
    private val supabase get() = SupabaseClientProvider.client
    private val _state = MutableStateFlow(AuthSessionState.SIGNED_OUT)
    val state: StateFlow<AuthSessionState> = _state.asStateFlow()

    val currentUserId: String? get() = supabase.auth.currentUserOrNull()?.id
    val currentUserEmail: String? get() = supabase.auth.currentUserOrNull()?.email
    /** The primary sign-in provider recorded by Supabase Auth for this account. */
    val currentUserProvider: String?
        get() = supabase.auth.currentUserOrNull()
            ?.appMetadata
            ?.get("provider")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val currentUserPhone: String?
        get() = supabase.auth.currentUserOrNull()?.let { user ->
            firstNonBlank(
                user.phone,
                user.userMetadata?.stringValue("phone"),
                user.userMetadata?.stringValue("phone_number"),
                user.googleIdentityData()?.stringValue("phone"),
                user.googleIdentityData()?.stringValue("phone_number")
            )
        }
    val currentUserDisplayName: String?
        get() = supabase.auth.currentUserOrNull()?.let { user ->
            val metadata = user.userMetadata
            val identity = user.googleIdentityData()
            firstNonBlank(
                metadata?.stringValue("display_name"),
                metadata?.stringValue("full_name"),
                metadata?.stringValue("name"),
                identity?.stringValue("full_name"),
                identity?.stringValue("name"),
                joinNameParts(
                    identity?.stringValue("given_name"),
                    identity?.stringValue("family_name")
                ),
                joinNameParts(
                    metadata?.stringValue("given_name"),
                    metadata?.stringValue("family_name")
                )
            )
        }
    val isGoogleUser: Boolean
        get() = supabase.auth.currentUserOrNull()?.let { user ->
            val provider = user.appMetadata?.get("provider")?.jsonPrimitive?.contentOrNull
            provider.equals("google", ignoreCase = true) ||
                user.identities?.any { it.provider.equals("google", ignoreCase = true) } == true
        } == true

    suspend fun signInWithGoogle(idToken: String): String {
        require(idToken.isNotBlank()) { "Google sign-in did not return a valid token" }
        supabase.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
        }
        return requireCurrentUserId()
    }

    suspend fun sendPasswordReset(email: String) {
        supabase.auth.resetPasswordForEmail(email.trim(), redirectUrl = SupabaseAuthRedirects.PASSWORD_RECOVERY)
    }

    suspend fun resendEmailVerification() {
        val email = requireNotNull(currentUserEmail) { "No email address is linked to this account." }
        check(!currentUserEmailVerified) { "This email address is already verified." }
        supabase.auth.resendEmail(
            type = OtpType.Email.SIGNUP,
            email = email,
            redirectUrl = SupabaseAuthRedirects.EMAIL_CONFIRMATION
        )
    }

    val currentUserEmailVerified: Boolean
        get() = supabase.auth.currentUserOrNull()?.emailConfirmedAt != null

    suspend fun requestPhoneOtp(phone: String) {
        supabase.auth.signInWith(OTP) { this.phone = PhoneNumberNormalizer.normalizePhilippines(phone) }
    }

    suspend fun verifyPhoneOtp(phone: String, token: String, displayName: String? = null): SupabaseProfileRecord {
        require(token.length in 4..10) { "Enter the verification code sent to your phone." }
        val normalizedPhone = PhoneNumberNormalizer.normalizePhilippines(phone)
        try {
            supabase.auth.verifyPhoneOtp(
                type = OtpType.Phone.SMS,
                phone = normalizedPhone,
                token = token.trim()
            )
        } catch (error: Throwable) {
            throw PhoneOtpRejectedException(error)
        }
        supabase.auth.updateUser {
            data {
                displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("display_name", JsonPrimitive(it)) }
                put("phone", JsonPrimitive(normalizedPhone))
            }
        }
        return try {
            provisionVerifiedProfile()
        } catch (error: Throwable) {
            throw PhoneProfileProvisioningException(error)
        }
    }

    suspend fun provisionVerifiedProfile(): SupabaseProfileRecord {
        val result = supabase.postgrest.rpc("provision_verified_profile")
        return runCatching { result.decodeList<SupabaseProfileRecord>().singleOrNull() }
            .getOrNull() ?: result.decodeAs<SupabaseProfileRecord>()
    }

    suspend fun signInWithEmail(email: String, password: String): SupabaseProfileRecord {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        return provisionVerifiedProfile()
    }

    suspend fun registerWithEmail(
        email: String,
        password: String,
        displayName: String? = null,
        phone: String? = null
    ) {
        require(email.trim().contains("@")) { "Enter a valid email address." }
        require(password.length >= 8) { "Password must be at least 8 characters." }
        val normalizedPhone = phone?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(PhoneNumberNormalizer::normalizePhilippines)
        supabase.auth.signUpWith(Email, redirectUrl = SupabaseAuthRedirects.EMAIL_CONFIRMATION) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("display_name", JsonPrimitive(it)) }
                normalizedPhone?.let { put("phone", JsonPrimitive(it)) }
            }
        }
        // Email confirmation is intentionally a later account-settings action.
        // The session returned by Supabase is required to create the owner's
        // workspace immediately after registration.
        provisionVerifiedProfile()
    }

    suspend fun createEmailUser(
        email: String,
        password: String,
        displayName: String? = null,
        phone: String? = null
    ): String {
        registerWithEmail(email, password, displayName, phone)
        if (supabase.auth.currentUserOrNull() == null || supabase.auth.currentAccessTokenOrNull() == null) {
            throw IllegalStateException("Supabase did not return an authenticated email session.")
        }
        return requireCurrentUserId()
    }

    suspend fun completePasswordRecovery(newPassword: String) {
        require(newPassword.length >= 8) { "Password must be at least 8 characters." }
        check(supabase.auth.currentUserOrNull() != null) { "Recovery session is missing or expired." }
        supabase.auth.updateUser { password = newPassword }
        signOut()
    }

    suspend fun reauthenticate(email: String, password: String, expectedUserId: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        try {
            requireSameReauthenticationIdentity(expectedUserId, currentUserId)
        } catch (error: ReauthenticationIdentityMismatchException) {
            // Supabase sign-in replaces the active session. Do not leave a
            // different account active while the settings screen still shows
            // the original owner's data.
            signOut()
            throw error
        }
    }
    suspend fun reauthenticateWithGoogle(idToken: String, expectedUserId: String) {
        signInWithGoogle(idToken)
        try {
            requireSameReauthenticationIdentity(expectedUserId, currentUserId)
        } catch (error: ReauthenticationIdentityMismatchException) {
            // A valid Google token can still belong to another AquaHub user.
            // Clear the switched session and fail closed before deletion runs.
            signOut()
            throw error
        }
    }

    suspend fun restoreSession(): Boolean {
        val loaded = supabase.auth.loadFromStorage()
        if (loaded && supabase.auth.currentUserOrNull() != null) _state.value = AuthSessionState.AUTHENTICATED
        return loaded && supabase.auth.currentUserOrNull() != null
    }

    suspend fun loadCurrentProfile(): SupabaseProfileRecord? {
        val user = supabase.auth.currentUserOrNull() ?: return null
        return supabase.from("profiles").select { filter { eq("id", user.id) } }
            .decodeList<SupabaseProfileRecord>().firstOrNull()
    }

    suspend fun persistAccountProfile(displayName: String, phone: String?) {
        val user = requireNotNull(supabase.auth.currentUserOrNull()) { "Supabase session could not be restored." }
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Display name is required." }
        val normalizedPhone = phone?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(PhoneNumberNormalizer::normalizePhilippines)
        supabase.auth.updateUser {
            data {
                put("display_name", JsonPrimitive(name))
                normalizedPhone?.let { put("phone", JsonPrimitive(it)) }
            }
        }
        supabase.from("profiles").update(update = {
            set("display_name", name)
            if (normalizedPhone != null) set("phone", normalizedPhone)
        }) { filter { eq("id", user.id) } }
    }

    suspend fun currentIdentity(): CurrentUserIdentity? {
        val user = supabase.auth.currentUserOrNull() ?: return null
        val profile = loadCurrentProfile()
        _state.value = AuthSessionState.AUTHENTICATED
        return CurrentUserIdentity(
            supabaseUserId = user.id,
            role = profile?.role,
            phone = profile?.phone ?: user.phone,
            email = profile?.email ?: user.email,
            verificationState = if (profile?.phoneVerified == true || profile?.emailVerified == true) VerificationState.VERIFIED else VerificationState.UNVERIFIED,
            accountState = profile?.accountState ?: AccountState.VERIFIED_NOT_PROVISIONED
        )
    }

    suspend fun signOut() {
        runCatching { supabase.auth.signOut() }
        _state.value = AuthSessionState.SIGNED_OUT
    }

    suspend fun deleteSupabaseSession() = signOut()

    private fun requireCurrentUserId(): String {
        val id = requireNotNull(currentUserId) { "Supabase did not return an authenticated user." }
        _state.value = AuthSessionState.AUTHENTICATED
        return id
    }
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun UserInfo.googleIdentityData() =
    identities?.firstOrNull { it.provider.equals("google", ignoreCase = true) }?.identityData

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

private fun joinNameParts(first: String?, last: String?): String? =
    listOfNotNull(first?.trim()?.takeIf { it.isNotEmpty() }, last?.trim()?.takeIf { it.isNotEmpty() })
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }

enum class AuthSessionState { SIGNED_OUT, AUTHENTICATED }
class PhoneOtpRejectedException(cause: Throwable) : Exception("Phone OTP was rejected", cause)
class PhoneProfileProvisioningException(cause: Throwable) : Exception("Phone was verified but the account profile could not be prepared", cause)
class ReauthenticationIdentityMismatchException : IllegalStateException(
    "The reauthenticated identity does not match the AquaHub account that requested deletion. No account was deleted."
)

internal fun requireSameReauthenticationIdentity(expectedUserId: String, actualUserId: String?) {
    if (expectedUserId.isBlank() || actualUserId.isNullOrBlank() || expectedUserId != actualUserId) {
        throw ReauthenticationIdentityMismatchException()
    }
}

@Serializable
data class SupabaseProfileRecord(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("firebase_uid") val firebaseUid: String? = null,
    val role: String = "CUSTOMER",
    val phone: String = "",
    val email: String? = null,
    @SerialName("phone_verified") val phoneVerified: Boolean = false,
    @SerialName("email_verified") val emailVerified: Boolean = false,
    @SerialName("account_state") val accountState: AccountState = AccountState.VERIFIED_NOT_PROVISIONED,
    @SerialName("preferred_business_id") val preferredBusinessId: String? = null,
    @SerialName("preferred_station_id") val preferredStationId: String? = null
)

data class CurrentUserIdentity(
    val supabaseUserId: String,
    val role: String?,
    val phone: String? = null,
    val email: String? = null,
    val verificationState: VerificationState = VerificationState.UNVERIFIED,
    val accountState: AccountState = AccountState.VERIFIED_NOT_PROVISIONED
)

enum class VerificationState { UNVERIFIED, VERIFIED }
enum class AccountState {
    AUTH_NOT_CREATED,
    AUTH_PENDING_VERIFICATION,
    VERIFIED_NOT_PROVISIONED,
    ACTIVE,
    MIGRATION_REQUIRED,
    MANUAL_REVIEW,
    DISABLED
}
