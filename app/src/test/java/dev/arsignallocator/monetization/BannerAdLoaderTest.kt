package dev.arsignallocator.monetization

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerAdLoaderTest {
    @Test
    fun `eligibility changes and owner destruction remove and destroy the current banner`() {
        val owner = TestLifecycleOwner()
        val factory = FakeBannerFactory()
        val loader = BannerAdLoader(owner, factory)
        val allowed = allowedRequest()

        listOf(
            allowed.copy(consentAllowsAds = false),
            allowed.copy(
                eligibility = allowed.eligibility.copy(
                    entitlement = EntitlementState.PRO_CACHED,
                ),
            ),
            allowed.copy(
                eligibility = allowed.eligibility.copy(
                    entitlement = EntitlementState.PRO_CONFIRMED,
                ),
            ),
            allowed.copy(screenAllowsAds = false),
        ).forEach { disallowed ->
            loader.update(testActivity(), 360, BannerFormat.ANCHORED_ADAPTIVE, allowed, {}, {})
            val banner = factory.created.last()

            loader.update(
                testActivity(),
                360,
                BannerFormat.ANCHORED_ADAPTIVE,
                disallowed,
                {},
                {},
            )

            assertEquals(1, banner.destroyCalls)
            assertTrue(banner.removed)
        }

        loader.update(testActivity(), 360, BannerFormat.ANCHORED_ADAPTIVE, allowed, {}, {})
        val banner = factory.created.last()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertEquals(1, banner.destroyCalls)
        assertTrue(banner.removed)
        loader.update(testActivity(), 360, BannerFormat.ANCHORED_ADAPTIVE, allowed, {}, {})
        assertEquals(5, factory.created.size)
    }

    @Test
    fun `failed load tears down its banner and allows a later retry`() {
        val factory = FakeBannerFactory()
        val loader = BannerAdLoader(TestLifecycleOwner(), factory)
        var failures = 0

        loader.update(
            testActivity(),
            320,
            BannerFormat.FIXED_320_BY_50,
            allowedRequest(),
            {},
            { failures += 1 },
        )
        val failed = factory.created.single()
        failed.fail()
        loader.update(
            testActivity(),
            320,
            BannerFormat.FIXED_320_BY_50,
            allowedRequest(),
            {},
            {},
        )

        assertEquals(1, failed.destroyCalls)
        assertTrue(failed.removed)
        assertEquals(1, failures)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `clear handles navigation and dispose permanently closes the loader`() {
        val factory = FakeBannerFactory()
        val loader = BannerAdLoader(TestLifecycleOwner(), factory)

        loader.update(
            testActivity(),
            360,
            BannerFormat.ANCHORED_ADAPTIVE,
            allowedRequest(),
            {},
            {},
        )
        val navigatingAway = factory.created.single()
        loader.clear()

        assertEquals(1, navigatingAway.destroyCalls)
        assertTrue(navigatingAway.removed)

        loader.update(
            testActivity(),
            360,
            BannerFormat.ANCHORED_ADAPTIVE,
            allowedRequest(),
            {},
            {},
        )
        val disposing = factory.created.last()
        loader.dispose()
        loader.update(
            testActivity(),
            320,
            BannerFormat.FIXED_320_BY_50,
            allowedRequest(),
            {},
            {},
        )

        assertEquals(1, disposing.destroyCalls)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `Complete Scan never reaches the banner factory across every runtime state`() {
        val factory = FakeBannerFactory()
        val loader = BannerAdLoader(TestLifecycleOwner(), factory)

        for (isDebug in listOf(false, true)) {
            for (testAds in listOf(false, true)) {
                for (consent in listOf(false, true)) {
                    for (ready in listOf(false, true)) {
                        for (entitlement in EntitlementState.entries) {
                            loader.update(
                                testActivity(),
                                360,
                                BannerFormat.ANCHORED_ADAPTIVE,
                                AdRequestContext(
                                    eligibility = AdsEligibility(
                                        adsEnabled = true,
                                        isDebugBuild = isDebug,
                                        debugTestAdsEnabled = testAds,
                                        entitlement = entitlement,
                                        billingAuthoritative = true,
                                    ),
                                    screenAllowsAds = false,
                                    consentAllowsAds = consent,
                                    adsSdkReady = ready,
                                ),
                                {},
                                {},
                            )
                        }
                    }
                }
            }
        }

        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun `loaded banner delivers its non-null view`() {
        val factory = FakeBannerFactory()
        val loader = BannerAdLoader(TestLifecycleOwner(), factory)
        var loaded: AdView? = null

        loader.update(
            testActivity(),
            320,
            BannerFormat.FIXED_320_BY_50,
            allowedRequest(),
            { loaded = it },
            {},
        )
        val banner = factory.created.single()
        banner.succeed()

        assertSame(banner.view, loaded)
    }

    @Test
    fun `stale loads after replacement or ineligibility are destroyed and never delivered`() {
        val factory = FakeBannerFactory()
        val loader = BannerAdLoader(TestLifecycleOwner(), factory)
        var loaded: AdView? = null

        loader.update(
            testActivity(),
            320,
            BannerFormat.FIXED_320_BY_50,
            allowedRequest(),
            { loaded = it },
            {},
        )
        val replaced = factory.created.single()
        loader.update(
            testActivity(),
            360,
            BannerFormat.ANCHORED_ADAPTIVE,
            allowedRequest(),
            { loaded = it },
            {},
        )
        replaced.succeed()

        val ineligible = factory.created.last()
        loader.update(
            testActivity(),
            360,
            BannerFormat.ANCHORED_ADAPTIVE,
            allowedRequest().copy(consentAllowsAds = false),
            { loaded = it },
            {},
        )
        ineligible.succeed()

        assertNull(loaded)
        assertEquals(1, replaced.destroyCalls)
        assertEquals(1, ineligible.destroyCalls)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.CREATED
        }
        override val lifecycle: Lifecycle = registry
    }

    private class FakeBannerFactory : ManagedBannerAdFactory {
        val created = mutableListOf<FakeBanner>()

        override fun create(
            activity: Activity,
            widthDp: Int,
            format: BannerFormat,
        ) = FakeBanner().also(created::add)
    }

    private class FakeBanner : ManagedBannerAd {
        override val view = AdView(testActivity())
        var destroyCalls = 0
        var removed = false
        private var onLoaded: () -> Unit = {}
        private var onFailed: () -> Unit = {}

        override fun load(onLoaded: () -> Unit, onFailed: () -> Unit) {
            this.onLoaded = onLoaded
            this.onFailed = onFailed
        }

        override fun destroyAndRemove() {
            if (removed) return
            destroyCalls += 1
            removed = true
        }

        fun succeed() = onLoaded()

        fun fail() = onFailed()
    }

    companion object {
        private fun allowedRequest() = AdRequestContext(
            eligibility = AdsEligibility(
                adsEnabled = true,
                isDebugBuild = false,
                debugTestAdsEnabled = false,
                entitlement = EntitlementState.FREE,
                billingAuthoritative = true,
            ),
            screenAllowsAds = true,
            consentAllowsAds = true,
            adsSdkReady = true,
        )

    }
}
