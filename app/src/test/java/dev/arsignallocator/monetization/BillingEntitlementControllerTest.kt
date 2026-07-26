package dev.arsignallocator.monetization

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingEntitlementControllerTest {
    @Test
    fun `purchase success pending and cancel follow Play callbacks`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)
        controller.refresh()
        sdk.replyPurchases()
        sdk.replyProduct(product())

        assertTrue(controller.launchPurchase(testActivity()))
        assertTrue(controller.state.value.purchaseInProgress)
        sdk.emitPurchaseUpdate(purchase(state = Purchase.PurchaseState.PENDING))
        assertEquals(EntitlementState.PENDING, controller.state.value.entitlement)
        assertFalse(controller.state.value.purchaseInProgress)

        controller.refresh()
        sdk.replyPurchases()
        assertEquals(EntitlementState.FREE, controller.state.value.entitlement)
        assertTrue(controller.launchPurchase(testActivity()))
        sdk.emitPurchaseUpdate(
            resultCode = BillingClient.BillingResponseCode.USER_CANCELED,
            purchases = null,
        )
        assertFalse(controller.state.value.purchaseInProgress)
        assertNull(controller.state.value.retryableError)

        assertTrue(controller.launchPurchase(testActivity()))
        sdk.emitPurchaseUpdate(purchase())
        runCurrent()
        assertEquals(EntitlementState.PRO_CONFIRMED, controller.state.value.entitlement)
        assertTrue(sdk.acknowledgements.isNotEmpty())
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.OK)
        advanceUntilIdle()
        assertNull(controller.state.value.retryableError)
    }

    @Test
    fun `purchase launch error is retryable and a retry returns to busy`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)
        controller.refresh()
        sdk.replyPurchases()
        sdk.replyProduct(product())
        sdk.launchResult = StoreBillingResult(BillingClient.BillingResponseCode.ERROR)

        assertFalse(controller.launchPurchase(testActivity()))
        assertFalse(controller.state.value.purchaseInProgress)
        assertEquals(
            "Google Play Billing is unavailable (6). Try again.",
            controller.state.value.retryableError,
        )

        sdk.launchResult = StoreBillingResult(BillingClient.BillingResponseCode.OK)
        assertTrue(controller.launchPurchase(testActivity()))
        assertTrue(controller.state.value.purchaseInProgress)
        assertNull(controller.state.value.retryableError)
    }

    @Test
    fun `localized price cannot launch purchase before authoritative ownership wins the race`() =
        runTest {
            val sdk = FakeBillingSdk()
            val controller = controller(sdk)
            controller.refresh()
            sdk.replyProduct(product())

            assertEquals("€4.99", controller.state.value.localizedPrice)
            assertFalse(controller.state.value.isAuthoritative)
            assertFalse(controller.launchPurchase(testActivity()))
            assertEquals(2, sdk.purchaseQueries.size)

            sdk.replyPurchasesAt(1)

            assertTrue(controller.state.value.isAuthoritative)
            assertTrue(controller.launchPurchase(testActivity()))
        }

    @Test
    fun `cached offline restore absent and refund states reconcile authoritatively`() = runTest {
        val offlineCache = FakeEntitlementCache(true)
        val offlineSdk = FakeBillingSdk()
        val offlineController = controller(offlineSdk, offlineCache)
        assertEquals(EntitlementState.PRO_CACHED, offlineController.state.value.entitlement)

        offlineController.refresh()
        offlineSdk.replyPurchases(
            resultCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        )
        assertEquals(EntitlementState.PRO_CACHED, offlineController.state.value.entitlement)
        assertFalse(offlineController.state.value.isAuthoritative)

        val restoredCache = FakeEntitlementCache(false)
        val restoredSdk = FakeBillingSdk()
        val restoredController = controller(restoredSdk, restoredCache)
        restoredController.refresh()
        restoredSdk.replyPurchases(purchase(acknowledged = true))
        assertEquals(EntitlementState.PRO_CONFIRMED, restoredController.state.value.entitlement)
        assertTrue(restoredController.state.value.isAuthoritative)
        assertTrue(restoredCache.pro)

        restoredController.refresh()
        restoredSdk.replyPurchases()
        assertEquals(EntitlementState.FREE, restoredController.state.value.entitlement)
        assertTrue(restoredController.state.value.isAuthoritative)
        assertFalse(restoredCache.pro)
    }

    @Test
    fun `acknowledgement and requery callback timeouts are bounded and release the token gate`() =
        runTest {
            val sdk = FakeBillingSdk()
            val controller = controller(sdk, callbackTimeoutMillis = 100)

            sdk.emitPurchaseUpdate(purchase())
            runCurrent()
            assertEquals(1, sdk.acknowledgements.size)
            advanceTimeBy(1_000)
            advanceUntilIdle()
            assertEquals(4, sdk.acknowledgements.size)
            val stateAfterAcknowledgementTimeout = controller.state.value
            repeat(4) {
                sdk.replyAcknowledgement(BillingClient.BillingResponseCode.OK)
            }
            assertEquals(stateAfterAcknowledgementTimeout, controller.state.value)

            sdk.emitPurchaseUpdate(purchase(token = "requery"))
            runCurrent()
            sdk.replyAcknowledgement(
                BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
                token = "requery",
            )
            advanceTimeBy(1_000)
            advanceUntilIdle()
            assertEquals(3, sdk.purchaseQueries.count { !it.completed })
            val stateAfterRequeryTimeout = controller.state.value
            repeat(3) {
                sdk.replyPurchases(purchase(token = "requery", acknowledged = true))
            }
            assertEquals(stateAfterRequeryTimeout, controller.state.value)

            val acknowledgementsBeforeRetry = sdk.acknowledgements.size
            sdk.emitPurchaseUpdate(purchase(token = "requery"))
            runCurrent()
            assertEquals(acknowledgementsBeforeRetry + 1, sdk.acknowledgements.size)
        }

    @Test
    fun `authoritative acknowledged purchase clears stale acknowledgement error`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)
        sdk.emitPurchaseUpdate(purchase())
        runCurrent()
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)
        advanceUntilIdle()
        assertEquals(
            "Google Play Billing is unavailable (-2). Try again.",
            controller.state.value.retryableError,
        )

        controller.refresh()
        sdk.replyPurchases(purchase(acknowledged = true))
        assertNull(controller.state.value.retryableError)
    }

    @Test
    fun `successful acknowledgement retry clears its transient error`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)

        sdk.emitPurchaseUpdate(purchase())
        runCurrent()
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
        runCurrent()

        assertEquals(2, sdk.acknowledgements.size)
        assertEquals(
            "Google Play Billing is unavailable (-1). Try again.",
            controller.state.value.retryableError,
        )
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.OK)
        advanceUntilIdle()
        assertNull(controller.state.value.retryableError)
    }

    @Test
    fun `item not owned requery recovers and acknowledges the current purchase`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)

        sdk.emitPurchaseUpdate(purchase())
        runCurrent()
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        runCurrent()
        sdk.replyPurchases(purchase())
        runCurrent()

        assertEquals(2, sdk.acknowledgements.size)
        assertTrue(controller.state.value.isAuthoritative)
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.OK)
        advanceUntilIdle()
        assertNull(controller.state.value.retryableError)
    }

    @Test
    fun `item not owned requery exhausts retries and releases the token gate`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)

        sdk.emitPurchaseUpdate(purchase())
        runCurrent()
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        repeat(3) {
            runCurrent()
            sdk.replyPurchases(resultCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        }
        advanceUntilIdle()

        assertEquals(3, sdk.purchaseQueries.size)
        assertEquals(
            "Google Play Billing is unavailable (2). Try again.",
            controller.state.value.retryableError,
        )
        sdk.emitPurchaseUpdate(purchase())
        runCurrent()
        assertEquals(2, sdk.acknowledgements.size)
    }

    @Test
    fun `close cancels attempts and ignores late purchase catalog and acknowledgement callbacks`() =
        runTest {
            val sdk = FakeBillingSdk()
            var mainThreadChecks = 0
            val controller = controller(
                sdk = sdk,
                mainThreadCheck = { mainThreadChecks += 1 },
                callbackTimeoutMillis = 100,
            )
            controller.refresh()
            sdk.emitPurchaseUpdate(purchase())
            runCurrent()
            val stateAtClose = controller.state.value

            controller.close()
            sdk.replyPurchases(purchase(acknowledged = true))
            sdk.replyProduct(product())
            sdk.replyAcknowledgement(BillingClient.BillingResponseCode.OK)
            sdk.emitPurchaseUpdate(purchase(state = Purchase.PurchaseState.PENDING))
            advanceUntilIdle()

            assertTrue(sdk.closed)
            assertEquals(stateAtClose, controller.state.value)
            assertEquals(2, mainThreadChecks)
        }

    @Test
    fun `refresh launch and close enforce their main thread entry contract`() = runTest {
        val sdk = FakeBillingSdk()
        var checks = 0
        val controller = controller(sdk, mainThreadCheck = { checks += 1 })

        controller.refresh()
        sdk.replyPurchases()
        sdk.replyProduct(product())
        controller.launchPurchase(testActivity())
        controller.close()

        assertEquals(3, checks)
    }

    @Test
    fun `newer authoritative query wins when Play callbacks complete in reverse`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)

        controller.refresh()
        controller.refresh()
        sdk.replyPurchasesAt(1, purchase(acknowledged = true))
        sdk.replyPurchasesAt(0)

        assertEquals(EntitlementState.PRO_CONFIRMED, controller.state.value.entitlement)
        assertTrue(controller.state.value.isAuthoritative)
    }

    @Test
    fun `newer failed query does not suppress an earlier successful query`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)

        controller.refresh()
        controller.refresh()
        sdk.replyPurchasesAt(
            1,
            resultCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        )
        sdk.replyPurchasesAt(0, purchase(acknowledged = true))

        assertEquals(EntitlementState.PRO_CONFIRMED, controller.state.value.entitlement)
        assertTrue(controller.state.value.isAuthoritative)
        assertNull(controller.state.value.retryableError)
    }

    @Test
    fun `later failed requery preserves an authoritative successful entitlement`() = runTest {
        val sdk = FakeBillingSdk()
        val controller = controller(sdk)

        controller.refresh()
        sdk.replyPurchases(purchase())
        runCurrent()
        sdk.replyAcknowledgement(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        runCurrent()
        sdk.replyPurchases(resultCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)

        assertEquals(EntitlementState.PRO_CONFIRMED, controller.state.value.entitlement)
        assertTrue(controller.state.value.isAuthoritative)
        assertTrue(controller.state.value.retryableError != null)
    }

    @Test
    fun `stale absence cannot revoke a later purchase update`() = runTest {
        val sdk = FakeBillingSdk()
        val cache = FakeEntitlementCache(false)
        val controller = controller(sdk, cache)

        controller.refresh()
        sdk.emitPurchaseUpdate(purchase(acknowledged = true))
        sdk.replyPurchasesAt(0)

        assertEquals(EntitlementState.PRO_CONFIRMED, controller.state.value.entitlement)
        assertTrue(cache.pro)
    }

    @Test
    fun `listener updates preserve unrelated acknowledgement errors until authoritative query`() =
        runTest {
            val sdk = FakeBillingSdk()
            val controller = controller(sdk)

            sdk.emitPurchaseUpdate(purchase(token = "old"))
            runCurrent()
            sdk.replyAcknowledgement(
                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                token = "old",
            )
            advanceUntilIdle()
            val oldError = "Google Play Billing is unavailable (-2). Try again."
            assertEquals(oldError, controller.state.value.retryableError)

            sdk.emitPurchaseUpdate(purchase(token = "current", acknowledged = true))
            assertEquals(oldError, controller.state.value.retryableError)

            sdk.emitPurchaseUpdate(purchase(token = "old", acknowledged = true))
            assertNull(controller.state.value.retryableError)

            sdk.emitPurchaseUpdate(purchase(token = "old"))
            runCurrent()
            sdk.replyAcknowledgement(
                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                token = "old",
            )
            advanceUntilIdle()
            controller.refresh()
            sdk.replyPurchases(purchase(token = "current", acknowledged = true))
            assertNull(controller.state.value.retryableError)
        }

    @Test
    fun `connection failure disconnect and connecting reentrancy publish exact errors`() = runTest {
        val sdk = FakeBillingSdk(isReady = false)
        val controller = controller(sdk)

        controller.refresh()
        controller.refresh()
        assertEquals(1, sdk.connections.size)

        sdk.replySetup(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        assertEquals(
            "Google Play Billing is unavailable (2). Try again.",
            controller.state.value.retryableError,
        )

        controller.refresh()
        assertEquals(2, sdk.connections.size)
        sdk.replySetup(BillingClient.BillingResponseCode.OK)
        sdk.disconnect()
        assertEquals(
            "Google Play Billing is unavailable. Try again.",
            controller.state.value.retryableError,
        )
    }

    private fun TestScope.controller(
        sdk: FakeBillingSdk,
        cache: FakeEntitlementCache = FakeEntitlementCache(false),
        mainThreadCheck: () -> Unit = {},
        callbackTimeoutMillis: Long = 1_000,
    ) = BillingEntitlementController(
        cache = cache,
        purchaseVerifier = PurchaseVerifier { json, signature ->
            json == "valid" && signature == "valid"
        },
        billingSdk = sdk,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        mainThreadCheck = mainThreadCheck,
        retryDelay = {},
        callbackTimeoutMillis = callbackTimeoutMillis,
    )

    private class FakeEntitlementCache(override var pro: Boolean) : EntitlementCache

    private class FakeBillingSdk(override var isReady: Boolean = true) : BillingSdk {
        var closed = false
        var launchResult = StoreBillingResult(BillingClient.BillingResponseCode.OK)
        val connections = mutableListOf<PendingConnection>()
        val purchaseQueries = mutableListOf<PendingPurchaseQuery>()
        val productQueries = ArrayDeque<(StoreBillingResult, StoreProduct?) -> Unit>()
        val acknowledgements = mutableListOf<PendingAcknowledgement>()
        private var purchaseListener:
            (StoreBillingResult, List<StorePurchase>?) -> Unit = { _, _ -> }

        override fun setPurchaseListener(
            listener: (StoreBillingResult, List<StorePurchase>?) -> Unit,
        ) {
            purchaseListener = listener
        }

        override fun startConnection(
            onSetup: (StoreBillingResult) -> Unit,
            onDisconnected: () -> Unit,
        ) {
            connections += PendingConnection(onSetup, onDisconnected)
        }

        override fun queryPurchases(
            callback: (StoreBillingResult, List<StorePurchase>) -> Unit,
        ) {
            purchaseQueries += PendingPurchaseQuery(callback)
        }

        override fun queryProduct(callback: (StoreBillingResult, StoreProduct?) -> Unit) {
            productQueries += callback
        }

        override fun launchPurchase(
            activity: Activity,
            product: StoreProduct,
        ): StoreBillingResult = launchResult

        override fun acknowledgePurchase(
            token: String,
            callback: (StoreBillingResult) -> Unit,
        ) {
            acknowledgements += PendingAcknowledgement(token, callback)
        }

        override fun close() {
            closed = true
        }

        fun emitPurchaseUpdate(
            vararg purchases: StorePurchase,
            resultCode: Int = BillingClient.BillingResponseCode.OK,
        ) {
            emitPurchaseUpdate(resultCode, purchases.toList())
        }

        fun emitPurchaseUpdate(resultCode: Int, purchases: List<StorePurchase>?) {
            purchaseListener(StoreBillingResult(resultCode), purchases)
        }

        fun replySetup(resultCode: Int) {
            connections.first { !it.setupCompleted }.run {
                setupCompleted = true
                isReady = resultCode == BillingClient.BillingResponseCode.OK
                onSetup(StoreBillingResult(resultCode))
            }
        }

        fun disconnect() {
            isReady = false
            connections.last { it.setupCompleted }.onDisconnected()
        }

        fun replyPurchases(
            vararg purchases: StorePurchase,
            resultCode: Int = BillingClient.BillingResponseCode.OK,
        ) {
            purchaseQueries.first { !it.completed }.run {
                completed = true
                callback(StoreBillingResult(resultCode), purchases.toList())
            }
        }

        fun replyPurchasesAt(
            index: Int,
            vararg purchases: StorePurchase,
            resultCode: Int = BillingClient.BillingResponseCode.OK,
        ) {
            purchaseQueries[index].run {
                completed = true
                callback(StoreBillingResult(resultCode), purchases.toList())
            }
        }

        fun replyProduct(
            product: StoreProduct?,
            resultCode: Int = BillingClient.BillingResponseCode.OK,
        ) {
            productQueries.removeFirst()(StoreBillingResult(resultCode), product)
        }

        fun replyAcknowledgement(resultCode: Int, token: String = "token") {
            acknowledgements.first { !it.completed && it.token == token }.run {
                completed = true
                callback(StoreBillingResult(resultCode))
            }
        }
    }

    private data class PendingPurchaseQuery(
        val callback: (StoreBillingResult, List<StorePurchase>) -> Unit,
        var completed: Boolean = false,
    )

    private data class PendingConnection(
        val onSetup: (StoreBillingResult) -> Unit,
        val onDisconnected: () -> Unit,
        var setupCompleted: Boolean = false,
    )

    private data class PendingAcknowledgement(
        val token: String,
        val callback: (StoreBillingResult) -> Unit,
        var completed: Boolean = false,
    )

    companion object {
        private fun product() = StoreProduct("€4.99")

        private fun purchase(
            state: Int = Purchase.PurchaseState.PURCHASED,
            acknowledged: Boolean = false,
            token: String = "token",
        ) = StorePurchase(
            products = listOf(BillingEntitlementController.PRODUCT_ID),
            purchaseToken = token,
            purchaseState = state,
            isAcknowledged = acknowledged,
            originalJson = "valid",
            signature = "valid",
        )

    }
}
