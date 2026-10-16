package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.repository.AuthRepository
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import com.aesprt.aquahub.domain.usecase.subscription.RefreshSubscriptionStateUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import com.aesprt.aquahub.ui.theme.AquaHubTheme

private const val ACCESS_RECHECK_INTERVAL_MS = 1_000L
private const val DENIAL_CONFIRMATION_CHECKS = 2

/** Rechecks an open destination on station changes and as its subscription expires. */
@Composable
fun SubscriptionGate(
    feature: SubscriptionFeature,
    onSubscription: () -> Unit,
    content: @Composable () -> Unit
) {
    if (LocalInspectionMode.current) {
        content()
        return
    }

    val stationContext: ActiveStationContext = koinInject()
    val auth: AuthRepository = koinInject()
    val checkAccess: CheckSubscriptionAccessUseCase = koinInject()
    val refreshSubscription: RefreshSubscriptionStateUseCase = koinInject()
    val access by produceState<SubscriptionAccessResult?>(null, feature) {
        combine(stationContext.observeActiveStationId(), auth.observeCurrentOwner()) { active, owner ->
            if (owner == null) null else active ?: owner.stationId
        }.distinctUntilChanged().collectLatest { stationId ->
            if (stationId == null) {
                value = null
            } else {
                value = null
                // The gate may prevent the destination ViewModel from being
                // composed. Refresh the authoritative Supabase subscription
                // once before evaluating access, while retaining Room state
                // when the device is offline.
                runCatching { refreshSubscription(stationId) }
                var consecutiveDeniedChecks = 0
                while (true) {
                    val checkedAccess = try {
                        checkAccess(stationId, feature)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A sync can briefly contend with Room or refresh a
                        // dependent row. Do not turn that transient failure
                        // into a false subscription expiration.
                        null
                    }
                    when {
                        checkedAccess == null -> {
                            // Keep the last known state while dependent data is refreshing.
                            consecutiveDeniedChecks = 0
                        }
                        checkedAccess == SubscriptionAccessResult.Allowed -> {
                            consecutiveDeniedChecks = 0
                            value = checkedAccess
                        }
                        else -> {
                            // A missing subscription/plan row during sync can look like a
                            // real denial. Require two consecutive checks before blocking.
                            consecutiveDeniedChecks++
                            if (consecutiveDeniedChecks >= DENIAL_CONFIRMATION_CHECKS) {
                                value = checkedAccess
                            }
                        }
                    }
                    delay(ACCESS_RECHECK_INTERVAL_MS)
                }
            }
        }
    }
    when (access) {
        SubscriptionAccessResult.Allowed -> content()
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("${feature.displayName} is unavailable", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("Your current subscription does not provide access. Review your plan or renew to continue.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSubscription) { Text("View subscription") }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun SubscriptionGatePreview() {
    AquaHubTheme {
        SubscriptionGate(
            feature = SubscriptionFeature.RIDER_MANAGEMENT,
            onSubscription = {}
        ) {
            LockedFeatureCard(
                title = "Rider management",
                description = "Assign delivery work to riders and track active orders.",
                buttonText = "View plans",
                icon = androidx.compose.material.icons.Icons.Rounded.LocalShipping,
                onButtonClick = {}
            )
        }
    }
}
