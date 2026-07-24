package dev.arsignallocator.monetization

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dev.arsignallocator.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class BillingState(
    val entitlement: EntitlementState,
    val isAuthoritative: Boolean = false,
    val localizedPrice: String? = null,
    val purchaseInProgress: Boolean = false,
    val retryableError: String? = null,
)

internal data class StoreBillingResult(val responseCode: Int)

internal data class StorePurchase(
    val products: List<String>,
    val purchaseToken: String,
    val purchaseState: Int,
    val isAcknowledged: Boolean,
    val originalJson: String,
    val signature: String,
)

internal data class StoreProduct(
    val formattedPrice: String,
    val platformDetails: Any,
)

internal interface BillingSdk {
    val isReady: Boolean

    fun setPurchaseListener(listener: (StoreBillingResult, List<StorePurchase>?) -> Unit)

    fun startConnection(
        onSetup: (StoreBillingResult) -> Unit,
        onDisconnected: () -> Unit,
    )

    fun queryPurchases(callback: (StoreBillingResult, List<StorePurchase>) -> Unit)

    fun queryProduct(callback: (StoreBillingResult, StoreProduct?) -> Unit)

    fun launchPurchase(activity: Activity, product: StoreProduct): StoreBillingResult

    fun acknowledgePurchase(token: String, callback: (StoreBillingResult) -> Unit)

    fun close()
}

private data class PlayProduct(
    val details: ProductDetails,
    val offerToken: String?,
)

private class PlayBillingSdk(context: Context) : BillingSdk {
    private var purchaseListener: (StoreBillingResult, List<StorePurchase>?) -> Unit = { _, _ -> }
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            purchaseListener(
                result.toStoreResult(),
                purchases?.map(Purchase::toStorePurchase),
            )
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    override val isReady: Boolean
        get() = client.isReady

    override fun setPurchaseListener(
        listener: (StoreBillingResult, List<StorePurchase>?) -> Unit,
    ) {
        purchaseListener = listener
    }

    override fun startConnection(
        onSetup: (StoreBillingResult) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    onSetup(result.toStoreResult())
                }

                override fun onBillingServiceDisconnected() {
                    onDisconnected()
                }
            },
        )
    }

    override fun queryPurchases(callback: (StoreBillingResult, List<StorePurchase>) -> Unit) {
        client.queryPurchasesAsync(queryPurchasesParams()) { result, purchases ->
            callback(result.toStoreResult(), purchases.map(Purchase::toStorePurchase))
        }
    }

    override fun queryProduct(callback: (StoreBillingResult, StoreProduct?) -> Unit) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BillingEntitlementController.PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, detailsResult ->
            val details = detailsResult.productDetailsList.singleOrNull {
                it.productId == BillingEntitlementController.PRODUCT_ID
            }
            val offer = details?.let(::eligibleOffer)
            callback(
                result.toStoreResult(),
                if (details == null || offer == null) {
                    null
                } else {
                    StoreProduct(
                        formattedPrice = offer.formattedPrice,
                        platformDetails = PlayProduct(details, offer.offerToken),
                    )
                },
            )
        }
    }

    override fun launchPurchase(
        activity: Activity,
        product: StoreProduct,
    ): StoreBillingResult {
        val playProduct = product.platformDetails as PlayProduct
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(playProduct.details)
            .apply {
                playProduct.offerToken?.takeIf(String::isNotBlank)?.let(::setOfferToken)
            }
            .build()
        return client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build(),
        ).toStoreResult()
    }

    override fun acknowledgePurchase(
        token: String,
        callback: (StoreBillingResult) -> Unit,
    ) {
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build(),
        ) { callback(it.toStoreResult()) }
    }

    override fun close() {
        client.endConnection()
    }
}

private fun BillingResult.toStoreResult() = StoreBillingResult(responseCode)

private fun Purchase.toStorePurchase() = StorePurchase(
    products = products,
    purchaseToken = purchaseToken,
    purchaseState = purchaseState,
    isAcknowledged = isAcknowledged,
    originalJson = originalJson,
    signature = signature,
)

private fun eligibleOffer(details: ProductDetails) =
    (
        details.oneTimePurchaseOfferDetailsList?.firstOrNull {
            it.formattedPrice.isNotBlank()
        } ?: details.oneTimePurchaseOfferDetails?.takeIf {
            it.formattedPrice.isNotBlank()
        }
    )

private fun queryPurchasesParams(): QueryPurchasesParams =
    QueryPurchasesParams.newBuilder()
        .setProductType(BillingClient.ProductType.INAPP)
        .build()

internal interface EntitlementCache {
    var pro: Boolean
}

private class SharedPreferencesEntitlementCache(context: Context) : EntitlementCache {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var pro: Boolean
        get() = preferences.getBoolean(CACHED_PRO_KEY, false)
        set(value) {
            preferences.edit().putBoolean(CACHED_PRO_KEY, value).apply()
        }

    companion object {
        private const val PREFERENCES_NAME = "billing_entitlement"
        private const val CACHED_PRO_KEY = "pro_confirmed"
    }
}

class BillingEntitlementController internal constructor(
    private val cache: EntitlementCache,
    private val purchaseVerifier: PurchaseVerifier,
    private val billingSdk: BillingSdk,
    private val scope: CoroutineScope,
    private val mainThreadCheck: () -> Unit,
    private val retryDelay: suspend (Int) -> Unit = {
        delay(acknowledgementRetryDelayMillis(it))
    },
    private val callbackTimeoutMillis: Long = ACKNOWLEDGEMENT_CALLBACK_TIMEOUT_MILLIS,
) {
    private val _state = MutableStateFlow(
        BillingState(
            entitlement = if (cache.pro) {
                EntitlementState.PRO_CACHED
            } else {
                EntitlementState.FREE
            },
        ),
    )
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val purchaseQueries = SuccessfulQueryGeneration()
    private val catalogCallbacks = CallbackGeneration()
    private val acknowledgementAttempts = AcknowledgementAttemptGate()
    private val acknowledgementErrors = mutableMapOf<String, String>()
    private var connecting = false
    @Volatile private var closed = false
    private var product: StoreProduct? = null
    private var catalogError: String? = null
    private var entitlementError: String? = null

    constructor(
        context: Context,
        purchaseVerifier: PurchaseVerifier =
            playPurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY),
    ) : this(
        cache = SharedPreferencesEntitlementCache(context),
        purchaseVerifier = purchaseVerifier,
        billingSdk = PlayBillingSdk(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        mainThreadCheck = {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "BillingEntitlementController must be called on the main thread"
            }
        },
    )

    init {
        require(callbackTimeoutMillis > 0)
        billingSdk.setPurchaseListener(::onPurchasesUpdated)
    }

    @MainThread
    fun refresh() {
        mainThreadCheck()
        if (closed) return
        _state.update { it.copy(purchaseInProgress = false) }
        if (billingSdk.isReady) {
            queryPurchases()
            queryProduct()
            return
        }
        if (connecting) return
        connecting = true
        billingSdk.startConnection(
            onSetup = { result ->
                if (closed) return@startConnection
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                    queryProduct()
                } else {
                    reportUnavailable(result)
                }
            },
            onDisconnected = {
                if (closed) return@startConnection
                connecting = false
                entitlementError = BILLING_UNAVAILABLE
                catalogError = BILLING_UNAVAILABLE
                publishErrors()
            },
        )
    }

    @MainThread
    fun launchPurchase(activity: Activity): Boolean {
        mainThreadCheck()
        if (closed) return false
        val currentProduct = product
        if (
            !_state.value.isAuthoritative ||
            _state.value.entitlement != EntitlementState.FREE
        ) {
            refresh()
            return false
        }
        if (!billingSdk.isReady || currentProduct == null) {
            clearCatalog(PRO_PRICE_UNAVAILABLE)
            refresh()
            return false
        }
        entitlementError = null
        val result = billingSdk.launchPurchase(activity, currentProduct)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            entitlementError = billingError(result)
        }
        _state.update {
            it.copy(
                purchaseInProgress =
                    result.responseCode == BillingClient.BillingResponseCode.OK,
                retryableError = visibleError(),
            )
        }
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    @MainThread
    fun close() {
        mainThreadCheck()
        if (closed) return
        closed = true
        catalogCallbacks.next()
        scope.cancel()
        billingSdk.close()
    }

    private fun queryPurchases() {
        val generation = purchaseQueries.next()
        billingSdk.queryPurchases { result, purchases ->
            if (closed) return@queryPurchases
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchaseQueries.runIfNewestSuccess(generation) {
                    applyPurchases(purchases.filter(::isProPurchase), authoritative = true)
                }
            } else if (purchaseQueries.isNewerThanLatestSuccess(generation)) {
                entitlementError = billingError(result)
                publishErrors()
            }
        }
    }

    private fun queryProduct() {
        val generation = catalogCallbacks.next()
        billingSdk.queryProduct { result, queriedProduct ->
            if (closed) return@queryProduct
            catalogCallbacks.runIfCurrent(generation) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK ||
                    queriedProduct == null
                ) {
                    clearCatalog(
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            PRO_PRICE_UNAVAILABLE
                        } else {
                            billingError(result)
                        },
                    )
                    return@runIfCurrent
                }
                product = queriedProduct
                catalogError = null
                _state.update {
                    it.copy(
                        localizedPrice = queriedProduct.formattedPrice,
                        retryableError = visibleError(),
                    )
                }
            }
        }
    }

    private fun onPurchasesUpdated(
        result: StoreBillingResult,
        purchases: List<StorePurchase>?,
    ) {
        if (closed) return
        _state.update { it.copy(purchaseInProgress = false) }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                entitlementError = billingError(result)
                publishErrors()
            }
            return
        }
        val matching = purchases.orEmpty().filter(::isProPurchase)
        if (matching.isEmpty()) return
        purchaseQueries.advance {
            applyPurchases(matching, authoritative = false)
        }
    }

    private fun applyPurchases(
        matching: List<StorePurchase>,
        authoritative: Boolean,
    ) {
        val valid = matching.filter(::hasValidSignature)
        val reconciliation = reconcileVerifiedPurchases(
            cachedPro = cache.pro,
            verifiedPurchases = valid.mapNotNull {
                when (it.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> StorePurchaseState.PURCHASED
                    Purchase.PurchaseState.PENDING -> StorePurchaseState.PENDING
                    else -> null
                }
            },
            invalidPurchaseCount = matching.size - valid.size,
            authoritative = authoritative,
        )
        entitlementError = if (reconciliation.hasVerificationError) PURCHASE_INVALID else null
        val decision = reconciliation.decision
        cache.pro = decision.cachePro

        val currentTokens = valid.mapTo(mutableSetOf(), StorePurchase::purchaseToken)
        if (authoritative) {
            acknowledgementErrors.keys.retainAll(currentTokens)
        }
        valid.filter(StorePurchase::isAcknowledged)
            .forEach { acknowledgementErrors.remove(it.purchaseToken) }
        _state.update {
            it.copy(
                entitlement = decision.state,
                isAuthoritative = when {
                    authoritative -> reconciliation.authoritative
                    reconciliation.hasVerificationError &&
                        decision.state != EntitlementState.PRO_CONFIRMED -> false
                    else -> it.isAuthoritative
                },
                retryableError = visibleError(),
            )
        }
        valid.filter {
            it.purchaseState == Purchase.PurchaseState.PURCHASED
        }.forEach(::acknowledge)
    }

    private fun acknowledge(purchase: StorePurchase) {
        val token = purchase.purchaseToken
        if (purchase.isAcknowledged || !acknowledgementAttempts.start(token)) return
        scope.launch {
            try {
                acknowledgeWithRetry(purchase)
            } finally {
                acknowledgementAttempts.finish(token)
            }
        }
    }

    private suspend fun acknowledgeWithRetry(initialPurchase: StorePurchase) {
        var purchase = initialPurchase
        var retries = 0
        while (currentCoroutineContext().isActive) {
            val result = acknowledgeOnce(purchase.purchaseToken)
            when (acknowledgementResponseAction(result.responseCode)) {
                AcknowledgementResponseAction.COMPLETE -> {
                    acknowledgementErrors.remove(purchase.purchaseToken)
                    publishErrors()
                    return
                }
                AcknowledgementResponseAction.FAIL -> {
                    acknowledgementErrors[purchase.purchaseToken] = billingError(result)
                    publishErrors()
                    return
                }
                AcknowledgementResponseAction.RETRY -> {
                    acknowledgementErrors[purchase.purchaseToken] = billingError(result)
                    publishErrors()
                    if (retries == MAX_ACKNOWLEDGEMENT_RETRIES) return
                    retryDelay(retries++)
                }
                AcknowledgementResponseAction.REQUERY -> {
                    acknowledgementErrors[purchase.purchaseToken] = billingError(result)
                    publishErrors()
                    var query: RequeryResult
                    do {
                        if (retries == MAX_ACKNOWLEDGEMENT_RETRIES) return
                        retryDelay(retries++)
                        query = requeryPurchase(purchase.purchaseToken)
                    } while (query == RequeryResult.Retry)

                    val current = (query as RequeryResult.Complete).purchase
                    if (current == null || current.isAcknowledged) {
                        acknowledgementErrors.remove(purchase.purchaseToken)
                        publishErrors()
                        return
                    }
                    purchase = current
                }
            }
        }
    }

    private suspend fun acknowledgeOnce(token: String): StoreBillingResult =
        withTimeoutOrNull(callbackTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                billingSdk.acknowledgePurchase(token) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } ?: StoreBillingResult(ACKNOWLEDGEMENT_TIMEOUT_RESPONSE_CODE)

    private suspend fun requeryPurchase(token: String): RequeryResult =
        withTimeoutOrNull(callbackTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val generation = purchaseQueries.next()
                billingSdk.queryPurchases { result, purchases ->
                    if (!continuation.isActive) return@queryPurchases
                    if (closed) {
                        continuation.resume(RequeryResult.Retry)
                        return@queryPurchases
                    }
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        if (purchaseQueries.isNewerThanLatestSuccess(generation)) {
                            entitlementError = billingError(result)
                            publishErrors()
                        }
                        continuation.resume(RequeryResult.Retry)
                        return@queryPurchases
                    }
                    val accepted = purchaseQueries.runIfNewestSuccess(generation) {
                        val matching = purchases.filter(::isProPurchase)
                        applyPurchases(matching, authoritative = true)
                        continuation.resume(
                            RequeryResult.Complete(
                                matching.firstOrNull {
                                    it.purchaseToken == token && hasValidSignature(it)
                                },
                            ),
                        )
                    }
                    if (!accepted) continuation.resume(RequeryResult.Retry)
                }
            }
        } ?: RequeryResult.Retry

    private fun hasValidSignature(purchase: StorePurchase): Boolean =
        purchaseVerifier.verify(purchase.originalJson, purchase.signature)

    private fun isProPurchase(purchase: StorePurchase): Boolean =
        PRODUCT_ID in purchase.products

    private fun clearCatalog(error: String) {
        product = null
        catalogError = error
        _state.update { it.copy(localizedPrice = null, retryableError = visibleError()) }
    }

    private fun reportUnavailable(result: StoreBillingResult) {
        entitlementError = billingError(result)
        catalogError = billingError(result)
        publishErrors()
    }

    private fun billingError(result: StoreBillingResult): String =
        "Google Play Billing is unavailable (${result.responseCode}). Try again."

    private fun visibleError(): String? =
        entitlementError ?: acknowledgementErrors.values.firstOrNull() ?: catalogError

    private fun publishErrors() {
        _state.update { it.copy(retryableError = visibleError()) }
    }

    private sealed interface RequeryResult {
        data object Retry : RequeryResult

        data class Complete(val purchase: StorePurchase?) : RequeryResult
    }

    companion object {
        const val PRODUCT_ID = "pro_lifetime"
        private const val MAX_ACKNOWLEDGEMENT_RETRIES = 3
        private const val ACKNOWLEDGEMENT_CALLBACK_TIMEOUT_MILLIS = 10_000L
        private const val ACKNOWLEDGEMENT_TIMEOUT_RESPONSE_CODE = -3
        private const val BILLING_UNAVAILABLE = "Google Play Billing is unavailable. Try again."
        private const val PRO_PRICE_UNAVAILABLE = "The Pro catalog is unavailable. Try again."
        private const val PURCHASE_INVALID = "The Pro purchase could not be verified. Try again."
    }
}
