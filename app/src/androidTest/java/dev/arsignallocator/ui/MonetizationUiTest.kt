package dev.arsignallocator.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import dev.arsignallocator.monetization.AdRequestContext
import dev.arsignallocator.monetization.AdsEligibility
import dev.arsignallocator.monetization.BannerFormat
import dev.arsignallocator.monetization.BannerSurface
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.ConsentState
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.ManagedBannerLoader
import dev.arsignallocator.monetization.PrivacyOptionsRequirement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MonetizationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun purchaseSheetShowsOnlyThePlayLocalizedPriceAndRoutesPurchaseAndRestore() {
        var purchases = 0
        var restores = 0
        compose.setContent {
            MaterialTheme {
                ProPurchaseSheet(
                    billingState = BillingState(
                        entitlement = EntitlementState.FREE,
                        isAuthoritative = true,
                        localizedPrice = "DKK 37,00",
                    ),
                    onPurchase = { purchases++ },
                    onRestore = { restores++ },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("One-time purchase", substring = true)
            .assertTextContains("DKK 37,00", substring = true)
        compose.onAllNodesWithText("€4.99", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Buy Pro for DKK 37,00").performClick()
        compose.onNodeWithText("Restore purchases").performClick()

        compose.runOnIdle {
            assertEquals(1, purchases)
            assertEquals(1, restores)
        }
    }

    @Test
    fun purchaseSheetExposesPendingBusyErrorAndRetryStates() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                ProPurchaseSheet(
                    billingState = BillingState(
                        entitlement = EntitlementState.PENDING,
                        retryableError = "Google Play is unavailable.",
                    ),
                    onPurchase = {},
                    onRestore = { retries++ },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Purchase pending", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Google Play is unavailable.").assertIsDisplayed()
        compose.onNodeWithText("Check purchase status").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun purchaseSheetLabelsAPlayLaunchFailureAsRetryable() {
        compose.setContent {
            MaterialTheme {
                ProPurchaseSheet(
                    billingState = BillingState(
                        entitlement = EntitlementState.FREE,
                        isAuthoritative = true,
                        localizedPrice = "USD 5.49",
                        retryableError = "Google Play Billing is unavailable. Try again.",
                    ),
                    onPurchase = {},
                    onRestore = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Retry purchase for USD 5.49").assertIsDisplayed()
    }

    @Test
    fun localizedPriceWaitsForAuthoritativeOwnershipBeforeBuyAppears() {
        var purchases = 0
        var restores = 0
        val billingState = mutableStateOf(
            BillingState(
                entitlement = EntitlementState.FREE,
                localizedPrice = "USD 5.49",
            ),
        )
        compose.setContent {
            MaterialTheme {
                ProPurchaseSheet(
                    billingState = billingState.value,
                    onPurchase = { purchases++ },
                    onRestore = { restores++ },
                    onDismiss = {},
                )
            }
        }

        compose.onAllNodesWithText("Buy Pro", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Retry Google Play").performClick()
        compose.runOnIdle {
            assertEquals(0, purchases)
            assertEquals(1, restores)
            billingState.value = billingState.value.copy(isAuthoritative = true)
        }
        compose.onNodeWithText("Buy Pro for USD 5.49").performClick()
        compose.runOnIdle { assertEquals(1, purchases) }
    }

    @Test
    fun GooglePrivacyChoicesStayHiddenWhenUmpDoesNotRequireThem() {
        compose.setContent {
            MaterialTheme {
                PrivacyAndAdsCard(
                    consentState = ConsentState(
                        privacyOptionsRequirement = PrivacyOptionsRequirement.NOT_REQUIRED,
                    ),
                    privacyPolicyAvailable = false,
                    onPrivacyPolicy = {},
                    onPrivacyChoices = {},
                )
            }
        }

        compose.onAllNodesWithText("Google privacy choices").assertCountEquals(0)
        compose.onNodeWithText("Google privacy choices are not required.").assertIsDisplayed()
        compose.onNodeWithText("Privacy policy unavailable in this build.").assertIsDisplayed()
    }

    @Test
    fun GooglePrivacyChoicesAppearWhenUmpRequiresThem() {
        compose.setContent {
            MaterialTheme {
                PrivacyAndAdsCard(
                    consentState = ConsentState(
                        privacyOptionsRequirement = PrivacyOptionsRequirement.REQUIRED,
                    ),
                    privacyPolicyAvailable = true,
                    onPrivacyPolicy = {},
                    onPrivacyChoices = {},
                )
            }
        }
        compose.onNodeWithText("Privacy policy").assertIsDisplayed()
        compose.onNodeWithText("Google privacy choices").assertIsDisplayed()
    }

    @Test
    fun unknownGooglePrivacyRequirementNeverClaimsChoicesAreNotRequired() {
        val consentState = mutableStateOf(
            ConsentState(
                privacyOptionsRequirement = PrivacyOptionsRequirement.UNKNOWN,
                isRefreshing = true,
            ),
        )
        compose.setContent {
            MaterialTheme {
                PrivacyAndAdsCard(
                    consentState = consentState.value,
                    privacyPolicyAvailable = true,
                    onPrivacyPolicy = {},
                    onPrivacyChoices = {},
                )
            }
        }

        compose.onNodeWithText("Checking whether Google privacy choices are required…")
            .assertIsDisplayed()
        compose.onAllNodesWithText("not required", substring = true).assertCountEquals(0)
        compose.runOnIdle {
            consentState.value = consentState.value.copy(
                isRefreshing = false,
                privacyError = "Google privacy status is unavailable.",
            )
        }
        compose.onNodeWithText("Whether Google privacy choices are required is unavailable.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("not required", substring = true).assertCountEquals(0)
    }

    @Test
    fun managedBannerSlotAttachesCollapsesAndDisposesThroughProductionUi() {
        val loader = FakeManagedBannerLoader()
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    ManagedBannerSlot(
                        surface = BannerSurface.PICKER,
                        widthDp = 360,
                        requestContext = allowedBannerRequest(),
                        loaderFactory = { loader },
                        content = { Box(Modifier.testTag("managed-banner")) },
                    )
                }
            }
        }

        compose.onAllNodesWithTag("managed-banner").assertCountEquals(0)
        compose.runOnIdle { loader.succeed() }
        compose.onAllNodesWithTag("managed-banner").assertCountEquals(1)
        compose.runOnIdle { loader.fail() }
        compose.onAllNodesWithTag("managed-banner").assertCountEquals(0)
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(1, loader.disposeCalls) }
    }

    @Test
    fun debugControlsRouteFakeEntitlementAndTestAdToggle() {
        var override: EntitlementState? = null
        var ads = false
        compose.setContent {
            MaterialTheme {
                VariantMonetizationSettings(
                    VariantMonetizationUi(
                        effectiveBillingState = BillingState(EntitlementState.FREE),
                        allowAds = false,
                        overrideState = null,
                        onOverrideChange = { override = it },
                        onAllowAdsChange = { ads = it },
                    ),
                )
            }
        }

        compose.onNodeWithText("Fake Pro").performClick()
        compose.onNodeWithText("Show test ads").performClick()
        compose.runOnIdle {
            assertEquals(EntitlementState.PRO_CONFIRMED, override)
            assertEquals(true, ads)
        }
    }

    private class FakeManagedBannerLoader : ManagedBannerLoader {
        var disposeCalls = 0
        private lateinit var view: AdView
        private var onLoaded: (AdView) -> Unit = {}
        private var onFailed: () -> Unit = {}

        override fun update(
            activity: Activity,
            widthDp: Int,
            format: BannerFormat,
            requestContext: AdRequestContext,
            onLoaded: (AdView) -> Unit,
            onFailed: () -> Unit,
        ) {
            view = AdView(activity)
            this.onLoaded = onLoaded
            this.onFailed = onFailed
        }

        override fun clear() = Unit

        override fun dispose() {
            disposeCalls += 1
        }

        fun succeed() = onLoaded(view)

        fun fail() = onFailed()
    }

    companion object {
        private fun allowedBannerRequest() = AdRequestContext(
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
