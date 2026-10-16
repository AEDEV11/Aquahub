package com.aesprt.aquahub.data.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.aesprt.aquahub.data.supabase.subscription.SupabaseSubscriptionDataSource
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import com.aesprt.aquahub.domain.model.BillingAvailability
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.BillingOperationState
import com.aesprt.aquahub.domain.model.GooglePlayBillingProducts
import com.aesprt.aquahub.domain.model.PlayBillingOffer
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.repository.GooglePlayBillingRepository
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Play is deliberately kept behind this boundary. Product prices come
 * from ProductDetails, while plan access comes only from the verified server
 * response and the Supabase -> Room entitlement cache refresh.
 */
class GooglePlayBillingRepositoryImpl(
    context: Context,
    private val subscriptionDataSource: SupabaseSubscriptionDataSource,
    private val subscriptionRepository: SubscriptionRepository
) : GooglePlayBillingRepository {
    private enum class ConnectionState { DISCONNECTED, CONNECTING, READY }
    private enum class VerificationSource { BACKGROUND_RESTORE, USER_RESTORE }

    private sealed interface VerificationResult {
        data object Success : VerificationResult
        data object Pending : VerificationResult
        data class NoPaidAccess(val message: String) : VerificationResult
        data class DifferentOrganization(val message: String) : VerificationResult
        data class Failure(
            val message: String,
            val kind: PlayPurchaseErrorMessages.Kind = PlayPurchaseErrorMessages.Kind.OTHER
        ) : VerificationResult
    }

    private class PendingPurchase(
        val activity: WeakReference<Activity>,
        val planCode: SubscriptionPlanCode,
        val billingCycle: BillingCycle,
        val oldProductId: String?,
        val userId: String,
        @Volatile var organizationId: String? = null,
        @Volatile var oldPurchaseToken: String? = null
    )

    private companion object {
        const val TAG = "AquaHubBilling"
        const val RECONNECT_DELAY_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val productDetailsById = mutableMapOf<String, ProductDetails>()
    private var connectionState = ConnectionState.DISCONNECTED
    private var reconnectScheduled = false
    private var productsLoaded = false
    private var pendingPurchase: PendingPurchase? = null
    private var pendingExplicitRestore = false
    private val checkoutInProgress = AtomicBoolean(false)
    private val checkoutVerificationStarted = AtomicBoolean(false)
    private val checkoutLaunchStarted = AtomicBoolean(false)
    private val restoreInProgress = AtomicBoolean(false)
    private val backgroundRestoreInProgress = AtomicBoolean(false)
    @Volatile private var activeCheckout: PendingPurchase? = null
    private val verificationMutex = Mutex()
    private val verifiedPurchaseTokens = mutableSetOf<String>()
    @Volatile private var lastCheckoutToken: String? = null

    private val _availability = MutableStateFlow(BillingAvailability.CONNECTING)
    override val availability: StateFlow<BillingAvailability> = _availability.asStateFlow()
    private val _offers = MutableStateFlow<List<PlayBillingOffer>>(emptyList())
    override val offers: StateFlow<List<PlayBillingOffer>> = _offers.asStateFlow()
    private val _operation = MutableStateFlow<BillingOperationState>(BillingOperationState.Idle)
    override val operation: StateFlow<BillingOperationState> = _operation.asStateFlow()
    private val _ownedPlayProductId = MutableStateFlow<String?>(null)
    override val ownedPlayProductId: StateFlow<String?> = _ownedPlayProductId.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases.orEmpty().sortedByDescending { it.purchaseTime }
                    .firstNotNullOfOrNull { PlayCheckoutOwnershipPolicy.aquaHubProductId(it.products) }
                    ?.let { _ownedPlayProductId.value = it }
                val checkout = activeCheckout
                if (checkout == null) {
                    processBackgroundPurchases(purchases.orEmpty())
                    return@PurchasesUpdatedListener
                }
                val expectedProduct = GooglePlayBillingProducts.forPlan(checkout.planCode)
                val purchase = purchases.orEmpty()
                    .filter { it.products.singleOrNull() == expectedProduct && it.purchaseToken != checkout.oldPurchaseToken }
                    .maxByOrNull { it.purchaseTime }
                if (purchase == null) {
                    _operation.value = BillingOperationState.Failed("Google Play did not return the selected purchase. Check your Play account and restore your purchase.")
                    finishCheckout()
                } else {
                    processCheckoutPurchase(purchase)
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                logBillingFailure("PurchasesUpdatedListener", result)
                if (checkoutInProgress.get()) {
                    _operation.value = BillingOperationState.Idle
                    finishCheckout()
                }
            }

            else -> {
                logBillingFailure("PurchasesUpdatedListener", result)
                if (checkoutInProgress.get()) {
                    _operation.value = BillingOperationState.Failed(
                        "Google Play could not start the purchase (${responseCodeName(result.responseCode)})."
                    )
                    finishCheckout()
                }
                retryConnectionIfNeeded(result.responseCode)
            }
        }
    }

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    override fun connect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::connect)
            return
        }
        if (billingClient.isReady) {
            connectionState = ConnectionState.READY
            queryProducts()
            restoreAfterConnect()
            return
        }
        if (connectionState == ConnectionState.CONNECTING) return

        connectionState = ConnectionState.CONNECTING
        _availability.value = BillingAvailability.CONNECTING
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connectionState = ConnectionState.READY
                    queryProducts()
                    restoreAfterConnect()
                } else {
                    connectionState = ConnectionState.DISCONNECTED
                    logBillingFailure("onBillingSetupFinished", result)
                    _availability.value = availabilityFor(result.responseCode)
                    if (checkoutInProgress.get() || restoreInProgress.get()) {
                        _operation.value = BillingOperationState.Failed(
                            "Google Play billing is unavailable (${responseCodeName(result.responseCode)})."
                        )
                    }
                    pendingExplicitRestore = false
                    restoreInProgress.set(false)
                    finishCheckout()
                    retryConnectionIfNeeded(result.responseCode)
                }
            }

            override fun onBillingServiceDisconnected() {
                connectionState = ConnectionState.DISCONNECTED
                logBillingFailure(
                    "onBillingServiceDisconnected",
                    BillingResult.newBuilder()
                        .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                        .setDebugMessage("Billing service disconnected without a BillingResult")
                        .build()
                )
                _availability.value = BillingAvailability.BILLING_UNAVAILABLE
                scheduleReconnect()
            }
        })
    }

    private fun queryProducts() {
        if (!billingClient.isReady) {
            connect()
            return
        }
        productsLoaded = false
        val productList = GooglePlayBillingProducts.productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        ) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                logBillingFailure("queryProductDetailsAsync", result)
                _availability.value = availabilityFor(result.responseCode)
                if (pendingPurchase != null) {
                    _operation.value = BillingOperationState.Failed("Google Play could not load plan prices. Please try again.")
                    finishCheckout()
                }
                retryConnectionIfNeeded(result.responseCode)
                return@queryProductDetailsAsync
            }
            productsLoaded = true
            productDetailsById.clear()
            val fetchedProducts = detailsResult.productDetailsList
            fetchedProducts.forEach { productDetails ->
                productDetailsById[productDetails.productId] = productDetails
            }
            val eligibleOffers = fetchedProducts.flatMap(::offersFor)
            _offers.value = eligibleOffers
            val returnedOfferShapes = fetchedProducts.flatMap { details ->
                details.subscriptionOfferDetails.orEmpty().map { offer ->
                    val period = offer.pricingPhases.pricingPhaseList.lastOrNull()?.billingPeriod ?: "unknown-period"
                    "${details.productId}/${offer.basePlanId}/$period"
                }
            }
            val unfetchedProductResults = detailsResult.unfetchedProductList.map {
                "${it.productId}/${it.statusCode}"
            }
            Log.i(
                TAG,
                "Product query completed: fetched=${fetchedProducts.size}, " +
                    "eligibleOffers=${eligibleOffers.size}, returnedOffers=$returnedOfferShapes, " +
                    "unfetchedProducts=$unfetchedProductResults"
            )
            _availability.value = if (_offers.value.isEmpty()) {
                BillingAvailability.NOT_CONFIGURED
            } else {
                BillingAvailability.AVAILABLE
            }
            retryPendingPurchase()
        }
    }

    private fun offersFor(details: ProductDetails): List<PlayBillingOffer> {
        val planCode = GooglePlayBillingProducts.planForProduct(details.productId) ?: return emptyList()
        return details.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
            val phases = offer.pricingPhases.pricingPhaseList
            val recurringPhase = phases.lastOrNull()
            val cycle = GooglePlayBillingProducts.cycleForOffer(
                basePlanId = offer.basePlanId,
                billingPeriod = recurringPhase?.billingPeriod
            ) ?: return@mapNotNull null
            // A Play subscription can contain an introductory/trial phase
            // followed by the recurring price. The plan card must show the
            // recurring amount that will be charged after the trial, not the
            // first phase's temporary price.
            val price = recurringPhase?.formattedPrice ?: return@mapNotNull null
            PlayBillingOffer(
                planCode = planCode,
                billingCycle = cycle,
                productId = details.productId,
                basePlanId = offer.basePlanId,
                formattedPrice = price,
                priceAmountMicros = recurringPhase?.priceAmountMicros,
                currencyCode = recurringPhase?.priceCurrencyCode,
                offerId = offer.offerId,
                introductoryTerms = phases.dropLast(1).takeIf { it.isNotEmpty() }?.let { introPhases ->
                    val intro = introPhases.joinToString("; ") { phase ->
                        "${phase.formattedPrice} for ${billingPhaseDuration(phase.billingPeriod, phase.billingCycleCount)}"
                    }
                    val cycleLabel = if (cycle == BillingCycle.ANNUAL) "year" else "month"
                    "${intro}. Then ${recurringPhase.formattedPrice} per $cycleLabel. Review renewal terms in Google Play checkout."
                },
            )
        }
    }

    private fun billingPhaseDuration(period: String, cycleCount: Int): String {
        val match = Regex("^P(\\d+)(D|W|M|Y)$").matchEntire(period.uppercase())
            ?: return "$period billing period"
        val units = match.groupValues[1].toIntOrNull() ?: return "$period billing period"
        val multiplier = cycleCount.coerceAtLeast(1)
        val count = units * multiplier
        val unit = when (match.groupValues[2]) {
            "D" -> "day"
            "W" -> "week"
            "M" -> "month"
            else -> "year"
        }
        return "$count $unit${if (count == 1) "" else "s"}"
    }

    override fun purchase(
        activity: Activity,
        planCode: SubscriptionPlanCode,
        billingCycle: BillingCycle,
        oldProductId: String?
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { purchase(activity, planCode, billingCycle, oldProductId) }
            return
        }
        val productId = GooglePlayBillingProducts.forPlan(planCode)
        if (productId == null) {
            _operation.value = BillingOperationState.Failed("The Free plan does not require a Play purchase.")
            return
        }
        if (checkoutInProgress.get() || restoreInProgress.get()) return
        val userId = currentAccountUserId()
        if (userId == null) {
            _operation.value = BillingOperationState.Failed(
                "Your AquaHub sign-in is unavailable. Sign in again before starting a Google Play purchase."
            )
            return
        }
        if (!checkoutInProgress.compareAndSet(false, true)) return
        val request = PendingPurchase(WeakReference(activity), planCode, billingCycle, oldProductId, userId)
        activeCheckout = request
        checkoutVerificationStarted.set(false)
        checkoutLaunchStarted.set(false)
        _operation.value = BillingOperationState.Purchasing(planCode, billingCycle)
        attemptPurchase(request)
    }

    private fun finishCheckout() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::finishCheckout)
            return
        }
        activeCheckout = null
        pendingPurchase = null
        checkoutVerificationStarted.set(false)
        checkoutLaunchStarted.set(false)
        checkoutInProgress.set(false)
    }

    private fun attemptPurchase(request: PendingPurchase) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { attemptPurchase(request) }
            return
        }
        if (!billingClient.isReady) {
            pendingPurchase = request
            connect()
            return
        }

        val activity = request.activity.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            finishCheckout()
            _operation.value = BillingOperationState.Failed("The purchase screen is no longer available. Please try again.")
            return
        }

        val productId = GooglePlayBillingProducts.forPlan(request.planCode)
        val details = productId?.let(productDetailsById::get)
        val offer = details?.subscriptionOfferDetails.orEmpty().firstOrNull { candidate ->
            val recurringPhase = candidate.pricingPhases.pricingPhaseList.lastOrNull()
            GooglePlayBillingProducts.cycleForOffer(
                basePlanId = candidate.basePlanId,
                billingPeriod = recurringPhase?.billingPeriod
            ) == request.billingCycle
        }
        if (productId == null || details == null || offer == null) {
            if (!productsLoaded) {
                pendingPurchase = request
                connect()
                return
            }
            finishCheckout()
            _availability.value = BillingAvailability.NOT_CONFIGURED
            _operation.value = BillingOperationState.Failed(
                "This plan is not available for purchase yet. Google Play products are not configured."
            )
            return
        }

        pendingPurchase = null
        if (request.oldProductId == null) {
            // A local Free projection can hide a suspended Play subscription.
            // Check Play ownership before any new checkout, including trial
            // activation and renewal, so we never create a second purchase.
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .includeSuspendedSubscriptions(true)
                    .build()
            ) { queryResult, purchases ->
                if (activeCheckout !== request) return@queryPurchasesAsync
                if (queryResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    logBillingFailure("queryPurchasesAsync(new checkout)", queryResult)
                    _operation.value = BillingOperationState.Failed(
                        "Google Play could not check existing subscriptions. Please try again before starting checkout."
                    )
                    finishCheckout()
                    retryConnectionIfNeeded(queryResult.responseCode)
                    return@queryPurchasesAsync
                }
                updateOwnedPlayProduct(purchases)
                if (purchases.any { PlayCheckoutOwnershipPolicy.hasAquaHubSubscription(it.products) }) {
                    _operation.value = BillingOperationState.Failed(
                        "Google Play already has an AquaHub subscription or pending payment. Check it in the Play Store, then restore your purchase in AquaHub. Do not buy again."
                    )
                    finishCheckout()
                    return@queryPurchasesAsync
                }
                launchPurchaseFlow(request, activity, details, offer, null)
            }
            return
        }

        // Query the current Play purchase immediately before replacement so a
        // stale local token can never be used for an upgrade/downgrade.
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .includeSuspendedSubscriptions(true)
                .build()
        ) { queryResult, purchases ->
            if (activeCheckout !== request) return@queryPurchasesAsync
            if (queryResult.responseCode != BillingClient.BillingResponseCode.OK) {
                logBillingFailure("queryPurchasesAsync(plan change)", queryResult)
                _operation.value = BillingOperationState.Failed(
                    "Google Play could not load the current subscription for this plan change " +
                        "(${responseCodeName(queryResult.responseCode)})."
                )
                finishCheckout()
                retryConnectionIfNeeded(queryResult.responseCode)
                return@queryPurchasesAsync
            }
            updateOwnedPlayProduct(purchases)
            val oldPurchase = purchases.filter {
                it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(request.oldProductId)
            }.maxByOrNull { it.purchaseTime }
            if (oldPurchase == null) {
                _operation.value = BillingOperationState.Failed(
                    "The current Google Play subscription could not be found. Please restore purchases and try again."
                )
                finishCheckout()
                return@queryPurchasesAsync
            }
            launchPurchaseFlow(request, activity, details, offer, oldPurchase)
        }
    }

    private fun retryPendingPurchase() {
        val request = pendingPurchase ?: return
        pendingPurchase = null
        attemptPurchase(request)
    }

    private fun launchPurchaseFlow(
        request: PendingPurchase,
        activity: Activity,
        details: ProductDetails,
        offer: ProductDetails.SubscriptionOfferDetails,
        oldPurchase: Purchase?
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { launchPurchaseFlow(request, activity, details, offer, oldPurchase) }
            return
        }
        if (activeCheckout !== request) return
        if (currentAccountUserId() != request.userId) {
            _operation.value = BillingOperationState.Failed(
                "Your AquaHub sign-in is unavailable. Sign in again before starting a Google Play purchase."
            )
            finishCheckout()
            return
        }
        if (!checkoutLaunchStarted.compareAndSet(false, true)) return
        scope.launch {
            val organizationId = try {
                subscriptionDataSource.checkoutOrganizationId(request.userId)
                    ?.let(PlayCheckoutIdentity::canonicalOrganizationId)
            } catch (error: Exception) {
                Log.e(TAG, "Checkout business lookup failed (${error.javaClass.simpleName})")
                null
            }
            val replacementFailure = if (organizationId != null && oldPurchase != null) {
                try {
                    if (subscriptionDataSource.preflightPlayReplacement(
                            organizationId,
                            oldPurchase.purchaseToken
                        )) null else PlayPurchaseErrorMessages.replacementPreflightFailure()
                } catch (error: Exception) {
                    Log.e(TAG, "Replacement preflight failed (${error.javaClass.simpleName})")
                    PlayPurchaseErrorMessages.replacementPreflightFailure(error)
                }
            } else null
            mainHandler.post {
                if (activeCheckout !== request) return@post
                if (organizationId == null || currentAccountUserId() != request.userId) {
                    _operation.value = BillingOperationState.Failed(
                        "AquaHub could not confirm your sign-in and selected business. Check them, then try again before paying."
                    )
                    finishCheckout()
                    return@post
                }
                if (replacementFailure != null) {
                    _operation.value = BillingOperationState.Failed(replacementFailure)
                    finishCheckout()
                    return@post
                }
                request.organizationId = organizationId
                launchPurchaseFlowForOrganization(request, activity, details, offer, oldPurchase, organizationId)
            }
        }
    }

    private fun launchPurchaseFlowForOrganization(
        request: PendingPurchase,
        activity: Activity,
        details: ProductDetails,
        offer: ProductDetails.SubscriptionOfferDetails,
        oldPurchase: Purchase?,
        organizationId: String
    ) {
        if (activeCheckout !== request) return
        if (activity.isFinishing || activity.isDestroyed) {
            _operation.value = BillingOperationState.Failed("The purchase screen is no longer available. Please try again.")
            finishCheckout()
            return
        }
        request.oldPurchaseToken = oldPurchase?.purchaseToken
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
        val flowBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .setObfuscatedAccountId(PlayCheckoutIdentity.accountId(request.userId))
            .setObfuscatedProfileId(PlayCheckoutIdentity.profileId(request.userId, organizationId))

        if (oldPurchase != null) {
            val replacement = BillingFlowParams.ProductDetailsParams
                .SubscriptionProductReplacementParams.newBuilder()
                .setOldProductId(oldPurchase.products.first())
                .setReplacementMode(
                    BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                        .ReplacementMode.CHARGE_FULL_PRICE
                )
                .build()
            val replacedParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offer.offerToken)
                .setSubscriptionProductReplacementParams(replacement)
                .build()
            flowBuilder
                .setProductDetailsParamsList(listOf(replacedParams))
                .setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(oldPurchase.purchaseToken)
                        .build()
                )
        }

        val result = billingClient.launchBillingFlow(activity, flowBuilder.build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            logBillingFailure("launchBillingFlow", result)
            _operation.value = BillingOperationState.Failed(
                "Google Play could not open checkout (${responseCodeName(result.responseCode)})."
            )
            finishCheckout()
            retryConnectionIfNeeded(result.responseCode)
        }
    }

    override fun openSubscriptionManagement(activity: Activity, productId: String?) {
        val builder = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
            .appendQueryParameter("package", appContext.packageName)
        if (!productId.isNullOrBlank()) builder.appendQueryParameter("sku", productId)
        val intent = Intent(Intent.ACTION_VIEW, builder.build())
        runCatching { activity.startActivity(intent) }
            .onFailure { _operation.value = BillingOperationState.Failed("Google Play subscription management could not be opened.") }
    }

    private fun currentAccountUserId(): String? {
        val auth = SupabaseClientProvider.client.auth
        val uid = auth.currentUserOrNull()?.id?.takeIf(String::isNotBlank) ?: return null
        if (auth.currentAccessTokenOrNull().isNullOrBlank()) return null
        return uid
    }

    override fun restorePurchases() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::restorePurchases)
            return
        }
        if (checkoutInProgress.get()) return
        if (!restoreInProgress.compareAndSet(false, true)) return
        _operation.value = BillingOperationState.Verifying
        pendingExplicitRestore = true
        if (!billingClient.isReady) {
            connect()
            return
        }
        restoreAfterConnect()
    }

    private fun restoreAfterConnect() {
        val source = if (pendingExplicitRestore) VerificationSource.USER_RESTORE
            else VerificationSource.BACKGROUND_RESTORE
        pendingExplicitRestore = false
        if (source == VerificationSource.BACKGROUND_RESTORE && checkoutInProgress.get()) return
        if (source == VerificationSource.BACKGROUND_RESTORE && restoreInProgress.get()) return
        if (source == VerificationSource.BACKGROUND_RESTORE && !backgroundRestoreInProgress.compareAndSet(false, true)) return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .includeSuspendedSubscriptions(true)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                updateOwnedPlayProduct(purchases)
                scope.launch {
                    try {
                        processRestoredPurchases(purchases, source)
                    } finally {
                        if (source == VerificationSource.USER_RESTORE) restoreInProgress.set(false)
                        else backgroundRestoreInProgress.set(false)
                    }
                }
            } else {
                logBillingFailure("queryPurchasesAsync(restore)", result)
                _availability.value = availabilityFor(result.responseCode)
                if (source == VerificationSource.USER_RESTORE) {
                    _operation.value = BillingOperationState.Failed("Google Play could not check your existing purchases. Please try again.")
                    restoreInProgress.set(false)
                } else {
                    backgroundRestoreInProgress.set(false)
                }
                retryConnectionIfNeeded(result.responseCode)
            }
        }
    }

    private fun updateOwnedPlayProduct(purchases: List<Purchase>) {
        _ownedPlayProductId.value = purchases.sortedByDescending { it.purchaseTime }
            .firstNotNullOfOrNull { PlayCheckoutOwnershipPolicy.aquaHubProductId(it.products) }
    }

    private fun processCheckoutPurchase(purchase: Purchase) {
        if (!checkoutVerificationStarted.compareAndSet(false, true)) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                _operation.value = BillingOperationState.Pending()
            } else {
                _operation.value = BillingOperationState.Failed("Google Play has not completed this purchase. Check your Play account before trying again.")
            }
            finishCheckout()
            return
        }
        lastCheckoutToken = purchase.purchaseToken
        _operation.value = BillingOperationState.Verifying
        val checkout = activeCheckout
        scope.launch {
            val result = verifyPurchase(purchase, checkout)
            _operation.value = when (result) {
                VerificationResult.Success -> BillingOperationState.Completed
                VerificationResult.Pending -> BillingOperationState.Pending()
                is VerificationResult.NoPaidAccess -> BillingOperationState.Failed(result.message)
                is VerificationResult.DifferentOrganization -> BillingOperationState.Failed(result.message)
                is VerificationResult.Failure -> BillingOperationState.Failed(result.message)
            }
            finishCheckout()
        }
    }

    private fun processBackgroundPurchases(purchases: List<Purchase>) {
        if (checkoutInProgress.get() || restoreInProgress.get()) return
        if (!backgroundRestoreInProgress.compareAndSet(false, true)) return
        scope.launch {
            try {
                processRestoredPurchases(purchases, VerificationSource.BACKGROUND_RESTORE)
            } finally {
                backgroundRestoreInProgress.set(false)
            }
        }
    }

    private suspend fun processRestoredPurchases(purchases: List<Purchase>, source: VerificationSource) {
        if (source == VerificationSource.BACKGROUND_RESTORE && (checkoutInProgress.get() || restoreInProgress.get())) return
        val selectedOrganizationId = if (source == VerificationSource.USER_RESTORE) {
            currentAccountUserId()?.let { userId ->
                try {
                    subscriptionDataSource.checkoutOrganizationId(userId)
                        ?.let(PlayCheckoutIdentity::canonicalOrganizationId)
                } catch (error: Exception) {
                    Log.e(TAG, "Restore business lookup failed (${error.javaClass.simpleName})")
                    null
                }
            }
        } else null
        var verifiedCurrent = false
        var verifiedOlder = false
        var pendingCurrent = false
        var pendingOlder = false
        var failure: String? = null
        var currentFailure: String? = null
        val latestPurchase = purchases.maxByOrNull { it.purchaseTime }
        val checkedOutPurchase = purchases.firstOrNull { it.purchaseToken == lastCheckoutToken }
        val currentToken = checkedOutPurchase
            ?.takeIf { it.purchaseTime >= (latestPurchase?.purchaseTime ?: Long.MIN_VALUE) }
            ?.purchaseToken ?: latestPurchase?.purchaseToken
        val orderedPurchases = purchases.sortedWith(
            compareByDescending<Purchase> { it.purchaseToken == currentToken }
                .thenByDescending { it.purchaseTime }
        )
        for (purchase in orderedPurchases) {
            if (source == VerificationSource.BACKGROUND_RESTORE && (checkoutInProgress.get() || restoreInProgress.get())) return
            when (val result = verifyPurchase(
                purchase = purchase,
                expectedCheckout = null,
                forceVerification = source == VerificationSource.USER_RESTORE,
                selectedOrganizationId = selectedOrganizationId
            )) {
                VerificationResult.Success -> {
                    if (purchase.purchaseToken == currentToken) verifiedCurrent = true
                    else verifiedOlder = true
                    break
                }
                VerificationResult.Pending -> {
                    if (purchase.purchaseToken == currentToken) pendingCurrent = true
                    else pendingOlder = true
                }
                is VerificationResult.NoPaidAccess -> {
                    if (purchase.purchaseToken == currentToken) currentFailure = result.message
                    failure = result.message
                }
                is VerificationResult.DifferentOrganization -> {
                    if (failure == null || purchase.purchaseToken == currentToken) failure = result.message
                }
                is VerificationResult.Failure -> {
                    if (purchase.purchaseToken == currentToken) currentFailure = result.message
                    if (result.kind != PlayPurchaseErrorMessages.Kind.STALE_TOKEN || purchase.purchaseToken == currentToken) {
                        if (failure == null || purchase.purchaseToken == currentToken) failure = result.message
                    }
                }
            }
        }
        if (source == VerificationSource.USER_RESTORE && !checkoutInProgress.get()) {
            _operation.value = PlayRestoreOperationDecision.choose(
                verifiedCurrent = verifiedCurrent,
                verifiedOlder = verifiedOlder,
                currentFailure = currentFailure,
                pendingCurrent = pendingCurrent,
                pendingOlder = pendingOlder,
                failure = failure
            )
        }
    }

    private suspend fun verifyPurchase(
        purchase: Purchase,
        expectedCheckout: PendingPurchase?,
        forceVerification: Boolean = false,
        selectedOrganizationId: String? = null
    ): VerificationResult = verificationMutex.withLock {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) return@withLock VerificationResult.Pending
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return@withLock VerificationResult.Failure("Google Play has not completed this purchase yet.")
        }
        val productId = purchase.products.singleOrNull()
        if (productId == null || productId !in GooglePlayBillingProducts.productIds) {
            return@withLock VerificationResult.Failure("Google Play returned an unknown subscription product.")
        }
        if (expectedCheckout != null && currentAccountUserId() != expectedCheckout.userId) {
            return@withLock VerificationResult.Failure(
                "Your AquaHub account changed after checkout. Sign in with the purchasing account and restore this purchase. Do not buy again."
            )
        }
        if (expectedCheckout != null && expectedCheckout.organizationId == null) {
            return@withLock VerificationResult.Failure(
                "AquaHub could not confirm which business this purchase belongs to. Restore the purchase after signing in. Do not buy again."
            )
        }
        if (!forceVerification && expectedCheckout == null && purchase.purchaseToken in verifiedPurchaseTokens) {
            return@withLock try {
                check(subscriptionRepository.refreshFromSupabase())
                VerificationResult.Success
            } catch (error: Exception) {
                Log.e(TAG, "Entitlement synchronization failed (${error.javaClass.simpleName})")
                VerificationResult.Failure(PlayPurchaseErrorMessages.ENTITLEMENT_REFRESH_FAILURE)
            }
        }
        var purchaseVerified = false
        try {
            // PURCHASED alone is not entitlement. The server verifies the
            // token, acknowledges it, and writes the trusted subscription.
            val verified = subscriptionDataSource.verifyPlaySubscription(
                productId,
                purchase.purchaseToken,
                expectedCheckout?.organizationId
            )
            purchaseVerified = true
            check(subscriptionRepository.refreshFromSupabase())
            if (expectedCheckout != null &&
                (verified.planCode != expectedCheckout.planCode.name ||
                    verified.billingCycle != expectedCheckout.billingCycle.name)
            ) {
                return@withLock VerificationResult.Failure("Google Play verified a different plan than the one selected. Check your active plan before trying again.")
            }
            if (expectedCheckout != null && !PlayVerifiedEntitlementPolicy.matchesSelectedOrganization(
                    verified.organizationId, expectedCheckout.organizationId
                )
            ) {
                return@withLock VerificationResult.DifferentOrganization(
                    "Google Play verified this purchase for another AquaHub business. Switch to that business and check your plan. Do not buy again."
                )
            }
            if (forceVerification && expectedCheckout == null &&
                !PlayVerifiedEntitlementPolicy.matchesSelectedOrganization(
                    verified.organizationId, selectedOrganizationId
                )
            ) {
                return@withLock VerificationResult.DifferentOrganization(
                    if (selectedOrganizationId == null)
                        "AquaHub could not confirm the selected business. Check your sign-in and business, then restore again. Do not buy again."
                    else
                        "This Google Play purchase belongs to another AquaHub business. Switch to that business and restore it. Do not buy again."
                )
            }
            if (!PlayVerifiedEntitlementPolicy.grantsPaidAccess(verified.status)) {
                return@withLock VerificationResult.NoPaidAccess(
                    "Google Play verified this subscription, but its billing status does not grant paid access. Check or renew it in Google Play, then restore again."
                )
            }
            verifiedPurchaseTokens += purchase.purchaseToken
            VerificationResult.Success
        } catch (error: Exception) {
            Log.e(TAG, "Purchase ${if (purchaseVerified) "entitlement synchronization" else "verification"} failed (${error.javaClass.simpleName})")
            VerificationResult.Failure(
                if (purchaseVerified) PlayPurchaseErrorMessages.ENTITLEMENT_REFRESH_FAILURE
                else PlayPurchaseErrorMessages.verificationFailure(error),
                if (purchaseVerified) PlayPurchaseErrorMessages.Kind.OTHER else PlayPurchaseErrorMessages.kind(error)
            )
        }
    }

    private fun availabilityFor(responseCode: Int): BillingAvailability = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> BillingAvailability.BILLING_UNAVAILABLE
        BillingClient.BillingResponseCode.NETWORK_ERROR -> BillingAvailability.NETWORK_ERROR
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingAvailability.NOT_CONFIGURED
        else -> BillingAvailability.BILLING_UNAVAILABLE
    }

    private fun retryConnectionIfNeeded(responseCode: Int) {
        if (responseCode in setOf(
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.NETWORK_ERROR
            )
        ) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        mainHandler.postDelayed({
            reconnectScheduled = false
            connect()
        }, RECONNECT_DELAY_MS)
    }

    private fun logBillingFailure(operation: String, result: BillingResult) {
        Log.e(
            TAG,
            "$operation failed: responseCode=${result.responseCode} (${responseCodeName(result.responseCode)})"
        )
    }

    private fun responseCodeName(responseCode: Int): String = when (responseCode) {
        BillingClient.BillingResponseCode.OK -> "OK"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        BillingClient.BillingResponseCode.ERROR -> "ERROR"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> "SERVICE_TIMEOUT"
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
        else -> "UNKNOWN"
    }
}
