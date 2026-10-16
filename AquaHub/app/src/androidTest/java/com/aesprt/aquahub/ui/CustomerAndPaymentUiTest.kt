package com.aesprt.aquahub.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryLine
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryRecord
import com.aesprt.aquahub.domain.model.OfflinePosStatus
import com.aesprt.aquahub.feature.customers.list.CustomersContent
import com.aesprt.aquahub.feature.customers.list.CustomersUiState
import com.aesprt.aquahub.feature.orders.walkin.QuickWalkInSaleContent
import com.aesprt.aquahub.feature.orders.walkin.WalkInSaleUiState
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import org.junit.Rule
import org.junit.Test

class CustomerAndPaymentUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addCustomerFabOpensCreateDialog() {
        var state by mutableStateOf(CustomersUiState())
        composeRule.setContent {
            AquaHubTheme {
                CustomersContent(
                    uiState = state,
                    onSearchQueryChange = {},
                    onOpenAddDialog = { state = state.copy(showAddDialog = true) },
                    onCloseAddDialog = { state = state.copy(showAddDialog = false) },
                    onAddCustomer = { _, _, _ -> },
                    onNavigateToCustomerDetail = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Add Customer").performClick()
        composeRule.onNodeWithText("Add New Customer").assertIsDisplayed()
        composeRule.onNodeWithText("Choose delivery location").assertIsDisplayed()
        composeRule.onNodeWithText("Save Customer").assertIsDisplayed()
    }

    @Test
    fun settlementShowsOnlyActivatedMethodsAndNeverCard() {
        composeRule.setContent {
            AquaHubTheme {
                QuickWalkInSaleContent(
                    uiState = WalkInSaleUiState(
                        cashPaymentEnabled = true,
                        gcashPaymentEnabled = true,
                        mayaPaymentEnabled = false,
                        paymentMethod = PaymentMethod.CASH
                    ),
                    onDismiss = {},
                    onSetCustomer = {},
                    onSetContainerSize = {},
                    onIncrementProduct = {},
                    onDecrementProduct = {},
                    onSetEmptyBottlesReturned = {},
                    onSetStationContainersIssued = {},
                    onSetDeliveryMode = {},
                    onSetDeliveryRecipientName = {},
                    onSetDeliveryRecipientPhone = {},
                    onSetDeliveryAddress = {},
                    onSetPaymentMethod = {},
                    onSetChargeToAccount = {},
                    onSubmitSale = {}
                )
            }
        }

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Payment Method"))
        composeRule.onNodeWithText("Payment Method").assertIsDisplayed()
        composeRule.onNodeWithText("Cash").assertIsDisplayed()
        composeRule.onNodeWithText("GCash").assertIsDisplayed()
        composeRule.onAllNodesWithText("Card").assertCountEquals(0)
        composeRule.onAllNodesWithText("Maya").assertCountEquals(0)
    }

    @Test
    fun savedOfflineCashReceiptShowsImmutableDetailsAndRequiresOwnerReview() {
        composeRule.setContent {
            AquaHubTheme {
                QuickWalkInSaleContent(
                    uiState = WalkInSaleUiState(
                        isOnline = false,
                        offlinePosStatus = OfflinePosStatus(
                            pendingCashSalesCentavos = 2_468L,
                            pendingOperationCount = 1,
                        ),
                        showOfflineRecoveryRecords = true,
                        offlineRecoveryRecords = listOf(
                            OfflinePosRecoveryRecord(
                                operationId = "offline-operation-immutable-1",
                                transactionAtMs = 1_800_000_000_000L,
                                totalCentavos = 2_468L,
                                cashReceivedCentavos = 5_000L,
                                changeCentavos = 2_532L,
                                state = "RECONCILIATION_REQUIRED",
                                lastErrorCode = "CATALOG_SNAPSHOT_CONFLICT",
                                lastError = "This locally recorded cash sale needs owner review. Its original sale details are preserved.",
                                lines = listOf(
                                    OfflinePosRecoveryLine(
                                        productName = "Purified Refill",
                                        sizeLabel = "5 gal",
                                        quantity = 2,
                                        unitPriceCentavos = 1_234L,
                                        lineTotalCentavos = 2_468L,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    onDismiss = {},
                    onSetCustomer = {},
                    onSetContainerSize = {},
                    onIncrementProduct = {},
                    onDecrementProduct = {},
                    onSetEmptyBottlesReturned = {},
                    onSetStationContainersIssued = {},
                    onSetDeliveryMode = {},
                    onSetDeliveryRecipientName = {},
                    onSetDeliveryRecipientPhone = {},
                    onSetDeliveryAddress = {},
                    onSetPaymentMethod = {},
                    onSetChargeToAccount = {},
                    onSubmitSale = {},
                )
            }
        }

        composeRule.onNodeWithText("Saved offline cash sales").assertIsDisplayed()
        composeRule.onNodeWithText("Needs reconciliation").assertIsDisplayed()
        composeRule.onNodeWithText("Receipt ID offline-operation-immutable-1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Purified Refill", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("This receipt and its original prices are preserved.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reconnect to retry verification").assertDoesNotExist()
    }
}
