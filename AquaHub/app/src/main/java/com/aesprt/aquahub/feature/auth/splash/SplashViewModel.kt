package com.aesprt.aquahub.feature.auth.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.usecase.auth.GetCurrentSessionUseCase
import com.aesprt.aquahub.domain.usecase.auth.HasCompletedOnboardingUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

sealed interface SplashNavigationTarget {
    data object Loading : SplashNavigationTarget
    data object ToOnboarding : SplashNavigationTarget
    data object ToLogin : SplashNavigationTarget
    data object ToDashboard : SplashNavigationTarget
}

class SplashViewModel(
    private val getCurrentSessionUseCase: GetCurrentSessionUseCase,
    private val hasCompletedOnboardingUseCase: HasCompletedOnboardingUseCase
) : ViewModel() {

    private val _target = MutableStateFlow<SplashNavigationTarget>(SplashNavigationTarget.Loading)
    val target = _target.asStateFlow()

    init {
        checkSession()
    }

    private fun checkSession() {
        viewModelScope.launch {
            delay(1000.milliseconds) // Brief smooth splash branding display
            val isLoggedIn = getCurrentSessionUseCase()
            if (isLoggedIn) {
                _target.value = SplashNavigationTarget.ToDashboard
            } else {
                val hasCompletedOnboarding = hasCompletedOnboardingUseCase()
                if (!hasCompletedOnboarding) {
                    _target.value = SplashNavigationTarget.ToOnboarding
                } else {
                    _target.value = SplashNavigationTarget.ToLogin
                }
            }
        }
    }
}
