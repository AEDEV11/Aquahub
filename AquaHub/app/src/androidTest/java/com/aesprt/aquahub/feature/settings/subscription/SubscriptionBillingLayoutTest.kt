package com.aesprt.aquahub.feature.settings.subscription

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.domain.model.BillingAvailability
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.ResolvedSubscriptionState
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.model.SubscriptionPlan
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.model.SubscriptionStatus
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubscriptionBillingLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun compactCardKeepsRenewalDateReadableAndChangePlanActionVisible() {
        assertCompactCardLayout(width = 393.dp, fontScale = 1f)
    }

    @Test
    fun narrowCardKeepsRenewalDateReadableWithLargerText() {
        assertCompactCardLayout(width = 320.dp, fontScale = 1.5f)
    }

    private fun assertCompactCardLayout(width: Dp, fontScale: Float) {
        val periodEnd = System.currentTimeMillis() + 14L * 24 * 60 * 60 * 1000
        val expectedDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(periodEnd))
        var changePlanClicked = false
        composeRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, fontScale)) {
                AquaHubTheme {
                    Box(Modifier.width(width).fillMaxHeight()) {
                        SubscriptionBillingContent(
                            uiState = SubscriptionBillingUiState(
                                isLoading = false,
                                billingAvailability = BillingAvailability.AVAILABLE,
                                currentSubscription = Subscription(
                                    stationId = 1L,
                                    planId = 1L,
                                    planCode = SubscriptionPlanCode.STARTER,
                                    status = SubscriptionStatus.ACTIVE,
                                    billingCycle = BillingCycle.MONTHLY,
                                    startedAt = periodEnd - 14L * 24 * 60 * 60 * 1000,
                                    currentPeriodStart = periodEnd - 14L * 24 * 60 * 60 * 1000,
                                    currentPeriodEnd = periodEnd,
                                    provider = "GOOGLE_PLAY"
                                ),
                                currentPlan = SubscriptionPlan(
                                    id = 1L,
                                    code = SubscriptionPlanCode.STARTER,
                                    name = "Starter",
                                    monthlyPrice = 199.0,
                                    annualPrice = 1910.4,
                                    ctaText = "Choose Starter"
                                ),
                                resolvedState = ResolvedSubscriptionState.ACTIVE
                            ),
                            onBackClick = {},
                            onNavigateToPlanComparison = { changePlanClicked = true },
                            onRenew = {},
                            onCancel = {},
                            onResume = {},
                            onClearMessages = {}
                        )
                    }
                }
            }
        }

        val dateBounds = composeRule.onNodeWithText(expectedDate).getUnclippedBoundsInRoot()
        val dateHeight = dateBounds.bottom - dateBounds.top
        assertTrue("Renewal date wrapped into a tall column: $dateHeight", dateHeight <= 64.dp)
        composeRule.onNodeWithText("Change Plan").performScrollTo().assertIsDisplayed().performClick()
        assertTrue("Change Plan action was lost", changePlanClicked)
    }
}
