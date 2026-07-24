package dev.arsignallocator.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetizationRulesTest {
    @Test
    fun `authoritative pending query revokes cached Pro`() {
        assertEquals(
            EntitlementDecision(EntitlementState.PENDING, cachePro = false),
            reconcileSuccessfulPurchases(
                cachedPro = true,
                purchases = listOf(StorePurchaseState.PENDING),
                authoritative = true,
            ),
        )
    }

    @Test
    fun `pending purchase update preserves cached Pro`() {
        assertEquals(
            EntitlementDecision(EntitlementState.PRO_CACHED, cachePro = true),
            reconcileSuccessfulPurchases(
                cachedPro = true,
                purchases = listOf(StorePurchaseState.PENDING),
                authoritative = false,
            ),
        )
    }

    @Test
    fun `purchased wins over pending and remains cached`() {
        assertEquals(
            EntitlementDecision(EntitlementState.PRO_CONFIRMED, cachePro = true),
            reconcileSuccessfulPurchases(
                cachedPro = true,
                purchases = listOf(
                    StorePurchaseState.PENDING,
                    StorePurchaseState.PURCHASED,
                ),
                authoritative = false,
            ),
        )
    }

    @Test
    fun `successful empty query revokes cached Pro`() {
        assertEquals(
            EntitlementDecision(EntitlementState.FREE, cachePro = false),
            reconcileSuccessfulPurchases(
                cachedPro = true,
                purchases = emptyList(),
                authoritative = true,
            ),
        )
    }

    @Test
    fun `non-authoritative empty update preserves cached Pro`() {
        assertEquals(
            EntitlementDecision(EntitlementState.PRO_CACHED, cachePro = true),
            reconcileSuccessfulPurchases(
                cachedPro = true,
                purchases = emptyList(),
                authoritative = false,
            ),
        )
    }

    @Test
    fun `invalid authoritative purchase preserves cached Pro without becoming authoritative`() {
        assertEquals(
            PurchaseReconciliation(
                decision = EntitlementDecision(EntitlementState.PRO_CACHED, cachePro = true),
                authoritative = false,
                hasVerificationError = true,
            ),
            reconcileVerifiedPurchases(
                cachedPro = true,
                verifiedPurchases = emptyList(),
                invalidPurchaseCount = 1,
                authoritative = true,
            ),
        )
    }

    @Test
    fun `invalid authoritative purchase leaves uncached user free and non-authoritative`() {
        assertEquals(
            PurchaseReconciliation(
                decision = EntitlementDecision(EntitlementState.FREE, cachePro = false),
                authoritative = false,
                hasVerificationError = true,
            ),
            reconcileVerifiedPurchases(
                cachedPro = false,
                verifiedPurchases = emptyList(),
                invalidPurchaseCount = 1,
                authoritative = true,
            ),
        )
    }

    @Test
    fun `valid purchased entitlement wins over an invalid matching record`() {
        assertEquals(
            PurchaseReconciliation(
                decision = EntitlementDecision(
                    EntitlementState.PRO_CONFIRMED,
                    cachePro = true,
                ),
                authoritative = true,
                hasVerificationError = true,
            ),
            reconcileVerifiedPurchases(
                cachedPro = false,
                verifiedPurchases = listOf(StorePurchaseState.PURCHASED),
                invalidPurchaseCount = 1,
                authoritative = true,
            ),
        )
    }

    @Test
    fun `new queries and purchase updates invalidate older query callbacks`() {
        val generation = CallbackGeneration()
        val firstQuery = generation.next()
        val secondQuery = generation.next()
        var applied = ""

        assertFalse(generation.runIfCurrent(firstQuery) { applied = "first" })
        assertTrue(generation.runIfCurrent(secondQuery) { applied = "second" })
        assertEquals("second", applied)

        val pendingQuery = generation.next()
        generation.advance { applied = "purchase update" }
        assertFalse(generation.runIfCurrent(pendingQuery) { applied = "stale query" })
        assertEquals("purchase update", applied)
    }

    @Test
    fun `catalog callback generation ignores reverse completion without affecting purchases`() {
        val catalog = CallbackGeneration()
        val purchases = CallbackGeneration()
        val oldCatalog = catalog.next()
        val currentPurchase = purchases.next()
        val newCatalog = catalog.next()
        var price: String? = null
        var error: String? = null
        var purchaseApplied = false

        assertTrue(catalog.runIfCurrent(newCatalog) { error = "new failure" })
        assertFalse(catalog.runIfCurrent(oldCatalog) { price = "stale price" })
        assertEquals(null, price)
        assertEquals("new failure", error)

        val nextOldCatalog = catalog.next()
        val nextNewCatalog = catalog.next()
        assertTrue(catalog.runIfCurrent(nextNewCatalog) {
            price = "€4.99"
            error = null
        })
        assertFalse(catalog.runIfCurrent(nextOldCatalog) { error = "stale failure" })
        assertTrue(purchases.runIfCurrent(currentPurchase) { purchaseApplied = true })
        assertEquals("€4.99", price)
        assertEquals(null, error)
        assertTrue(purchaseApplied)
    }

    @Test
    fun `acknowledgement gate deduplicates a job but permits a later refresh`() {
        val attempts = AcknowledgementAttemptGate()

        assertTrue(attempts.start("token"))
        assertFalse(attempts.start("token"))
        attempts.finish("token")
        assertTrue(attempts.start("token"))
    }

    @Test
    fun `acknowledgement responses distinguish completion retry requery and failure`() {
        assertEquals(
            AcknowledgementResponseAction.COMPLETE,
            acknowledgementResponseAction(0),
        )
        listOf(-3, -1, 2, 6, 12).forEach {
            assertEquals(AcknowledgementResponseAction.RETRY, acknowledgementResponseAction(it))
        }
        assertEquals(AcknowledgementResponseAction.REQUERY, acknowledgementResponseAction(8))
        listOf(-2, 1, 3, 4, 5, 7).forEach {
            assertEquals(AcknowledgementResponseAction.FAIL, acknowledgementResponseAction(it))
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (0..4).map(
            ::acknowledgementRetryDelayMillis,
        ))
    }

    @Test
    fun `ads wait for billing and stay off for Pro disabled and normal debug builds`() {
        val eligible = AdsEligibility(
            adsEnabled = true,
            isDebugBuild = false,
            debugTestAdsEnabled = false,
            entitlement = EntitlementState.FREE,
            billingAuthoritative = true,
        )

        assertTrue(canInitializeAds(eligible))
        assertFalse(canInitializeAds(eligible.copy(adsEnabled = false)))
        assertFalse(canInitializeAds(eligible.copy(billingAuthoritative = false)))
        assertFalse(canInitializeAds(eligible.copy(entitlement = EntitlementState.PRO_CACHED)))
        assertFalse(canInitializeAds(eligible.copy(entitlement = EntitlementState.PRO_CONFIRMED)))
        assertFalse(canInitializeAds(eligible.copy(isDebugBuild = true)))
        assertTrue(
            canInitializeAds(
                eligible.copy(isDebugBuild = true, debugTestAdsEnabled = true),
            ),
        )
    }

    @Test
    fun `banner policy matrix requires every release billing consent and SDK gate`() {
        val booleans = listOf(false, true)
        var allowed = 0

        for (adsEnabled in booleans) {
            for (isDebugBuild in booleans) {
                for (debugTestAdsEnabled in booleans) {
                    for (billingAuthoritative in booleans) {
                        for (entitlement in EntitlementState.entries) {
                            for (completeScan in booleans) {
                                for (consentAllowsAds in booleans) {
                                    for (adsSdkReady in booleans) {
                                        val request = AdRequestContext(
                                            eligibility = AdsEligibility(
                                                adsEnabled = adsEnabled,
                                                isDebugBuild = isDebugBuild,
                                                debugTestAdsEnabled = debugTestAdsEnabled,
                                                entitlement = entitlement,
                                                billingAuthoritative = billingAuthoritative,
                                            ),
                                            completeScan = completeScan,
                                            consentAllowsAds = consentAllowsAds,
                                            adsSdkReady = adsSdkReady,
                                        )
                                        val expected = adsEnabled &&
                                            billingAuthoritative &&
                                            (!isDebugBuild || debugTestAdsEnabled) &&
                                            entitlement != EntitlementState.PRO_CACHED &&
                                            entitlement != EntitlementState.PRO_CONFIRMED &&
                                            !completeScan &&
                                            consentAllowsAds &&
                                            adsSdkReady

                                        assertEquals(expected, canRequestBanner(request))
                                        if (expected) allowed += 1
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(6, allowed)
    }

    @Test
    fun `consent state survives errors while ad readiness remains eligibility gated`() {
        val valid = updatedConsentState(
            previous = ConsentState(),
            snapshot = ConsentSnapshot(
                consentAllowsAds = true,
                privacyOptionsRequired = true,
            ),
            adsEligible = false,
            adsSdkReady = false,
        )

        assertTrue(valid.consentAllowsAds)
        assertTrue(valid.privacyOptionsRequired)
        assertFalse(valid.canRequestAds)
        assertEquals(
            valid,
            updatedConsentState(
                previous = valid,
                snapshot = null,
                adsEligible = false,
                adsSdkReady = false,
            ),
        )
        assertTrue(
            updatedConsentState(
                previous = valid,
                snapshot = null,
                adsEligible = true,
                adsSdkReady = true,
            ).canRequestAds,
        )
    }

    @Test
    fun `consent retries are serialized bounded and delayed`() {
        val retries = ConsentRetryGate(maxAttempts = 3)

        assertTrue(retries.begin())
        assertFalse(retries.begin())
        assertEquals(0, retries.finish(success = false))
        assertTrue(retries.begin())
        assertEquals(1, retries.finish(success = false))
        assertTrue(retries.begin())
        assertEquals(null, retries.finish(success = false))
        assertFalse(retries.begin())
        val completed = ConsentRetryGate(maxAttempts = 3)
        assertTrue(completed.begin())
        assertEquals(null, completed.finish(success = true))
        assertFalse(completed.begin())
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 4_000L), (0..3).map(
            ::consentRetryDelayMillis,
        ))
    }
}
