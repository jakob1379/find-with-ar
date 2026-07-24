package dev.arsignallocator.ui

import dev.arsignallocator.BuildConfig
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.allowsCompleteScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantMonetizationUiTest {
    @Test
    fun `debug entitlement can be forced free Pro or returned to Play`() {
        val play = BillingState(
            entitlement = EntitlementState.PENDING,
            localizedPrice = "DKK 39.00",
            retryableError = "store error",
        )

        assertEquals(
            EntitlementState.FREE,
            overrideBillingState(play, EntitlementState.FREE).entitlement,
        )
        val pro = overrideBillingState(play, EntitlementState.PRO_CONFIRMED)
        assertEquals(EntitlementState.PRO_CONFIRMED, pro.entitlement)
        assertTrue(pro.isAuthoritative)
        assertFalse(pro.purchaseInProgress)
        assertNull(pro.retryableError)
        assertEquals(play, overrideBillingState(play, null))
    }

    @Test
    fun `debug fake Pro passes the production Complete Scan hard gate end to end`() {
        val play = BillingState(
            entitlement = EntitlementState.FREE,
            isAuthoritative = true,
        )

        assertFalse(play.entitlement.allowsCompleteScan())
        assertTrue(
            overrideBillingState(play, EntitlementState.PRO_CONFIRMED)
                .entitlement
                .allowsCompleteScan(),
        )
        assertFalse(
            overrideBillingState(play, EntitlementState.FREE)
                .entitlement
                .allowsCompleteScan(),
        )
    }

    @Test
    fun `debug artifact is pinned to Google's official test identifiers`() {
        assertEquals("ca-app-pub-3940256099942544~3347511713", BuildConfig.ADMOB_APP_ID)
        assertEquals("ca-app-pub-3940256099942544/9214589741", BuildConfig.ADMOB_BANNER_ID)
    }
}
