package dev.arsignallocator.monetization

import android.app.Activity
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdsConsentControllerTest {
    @Test
    fun `persisted consent is published immediately and initializes ads after update failure`() =
        runTest {
            val sdk = FakeConsentSdk(
                ConsentSnapshot(
                    consentAllowsAds = true,
                    privacyOptionsRequired = true,
                ),
            )
            var initializations = 0
            val controller = controller(
                sdk,
                initializer = AdsSdkInitializer { onComplete ->
                    initializations += 1
                    onComplete()
                },
            )

            assertTrue(controller.state.value.consentAllowsAds)
            assertTrue(controller.state.value.privacyOptionsRequired)
            controller.setEligible(true)
            controller.gatherConsent(testActivity())
            sdk.failUpdate()
            advanceUntilIdle()

            assertEquals(1, initializations)
            assertTrue(controller.state.value.adsSdkReady)
            assertTrue(controller.state.value.canRequestAds)
            assertEquals(2, sdk.updateRequests)
        }

    @Test
    fun `previous session consent is sampled after request without waiting for a callback`() =
        runTest {
            val sdk = FakeConsentSdk(
                currentSnapshot = ConsentSnapshot(false, false),
                snapshotAfterRequest = ConsentSnapshot(true, true),
            )
            var initializations = 0
            val controller = controller(
                sdk,
                initializer = AdsSdkInitializer { onComplete ->
                    initializations += 1
                    onComplete()
                },
            )
            controller.setEligible(true)

            controller.gatherConsent(testActivity())
            advanceUntilIdle()

            assertEquals(1, sdk.updateRequests)
            assertEquals(0, sdk.formRequests)
            assertTrue(controller.state.value.consentAllowsAds)
            assertTrue(controller.state.value.privacyOptionsRequired)
            assertTrue(controller.state.value.canRequestAds)
            assertEquals(1, initializations)
        }

    @Test
    fun `slow update callback replaces the sampled previous session state`() = runTest {
        val sdk = FakeConsentSdk(
            currentSnapshot = ConsentSnapshot(false, false),
            snapshotAfterRequest = ConsentSnapshot(true, true),
        )
        val controller = controller(
            sdk,
            initializer = AdsSdkInitializer { it() },
        )
        controller.setEligible(true)
        controller.gatherConsent(testActivity())
        advanceUntilIdle()
        assertTrue(controller.state.value.consentAllowsAds)

        sdk.currentSnapshot = ConsentSnapshot(false, false)
        sdk.succeedUpdate()

        assertFalse(controller.state.value.consentAllowsAds)
        assertFalse(controller.state.value.canRequestAds)
        assertEquals(1, sdk.formRequests)
    }

    @Test
    fun `successful update waits for form and recreation republishes persisted state`() = runTest {
        val sdk = FakeConsentSdk(ConsentSnapshot(false, false))
        var initializations = 0
        val controller = controller(
            sdk,
            initializer = AdsSdkInitializer { onComplete ->
                initializations += 1
                onComplete()
            },
        )
        controller.setEligible(true)
        controller.gatherConsent(testActivity())
        sdk.currentSnapshot = ConsentSnapshot(true, true)
        sdk.succeedUpdate()
        runCurrent()

        assertEquals(1, sdk.formRequests)
        assertEquals(1, initializations)
        sdk.completeForm(error = null)
        advanceUntilIdle()
        assertTrue(controller.state.value.canRequestAds)

        val recreated = controller(
            sdk,
            initializer = AdsSdkInitializer { it() },
        )
        assertTrue(recreated.state.value.consentAllowsAds)
        assertTrue(recreated.state.value.privacyOptionsRequired)
        assertFalse(recreated.state.value.adsSdkReady)
    }

    @Test
    fun `form errors retry serially and stop after the bounded attempts`() = runTest {
        val sdk = FakeConsentSdk(ConsentSnapshot(false, false))
        val controller = controller(sdk)
        val activity = testActivity()

        controller.gatherConsent(activity)
        repeat(3) {
            sdk.succeedUpdate()
            sdk.completeForm("form failed")
            advanceUntilIdle()
        }

        assertEquals(3, sdk.updateRequests)
        assertEquals(3, sdk.formRequests)
        controller.gatherConsent(activity)
        assertEquals(3, sdk.updateRequests)
    }

    @Test
    fun `destroyed activity never opens a consent form`() = runTest {
        val sdk = FakeConsentSdk(ConsentSnapshot(true, false))
        val controller = controller(sdk, activityIsUsable = { false })

        controller.gatherConsent(testActivity())
        sdk.succeedUpdate()

        assertEquals(0, sdk.formRequests)
    }

    @Test
    fun `eligibility changes while initialization is queued do not lose initialization`() =
        runTest {
            val sdk = FakeConsentSdk(ConsentSnapshot(true, false))
            var initializations = 0
            val controller = controller(
                sdk,
                initializer = AdsSdkInitializer {
                    initializations += 1
                    it()
                },
            )

            controller.setEligible(true)
            controller.setEligible(false)
            runCurrent()
            assertEquals(0, initializations)

            controller.setEligible(true)
            runCurrent()
            assertEquals(1, initializations)

            val second = controller(
                sdk,
                initializer = AdsSdkInitializer {
                    initializations += 1
                    it()
                },
            )
            second.setEligible(true)
            second.setEligible(false)
            second.setEligible(true)
            runCurrent()
            assertEquals(2, initializations)
        }

    @Test
    fun `concurrent consent and initialization callbacks preserve both state changes`() = runTest {
        val sdk = FakeConsentSdk(ConsentSnapshot(true, true))
        var initializationComplete: () -> Unit = {}
        val controller = controller(
            sdk,
            initializer = AdsSdkInitializer { initializationComplete = it },
        )
        controller.setEligible(true)
        runCurrent()

        sdk.currentSnapshot = ConsentSnapshot(false, false)
        controller.showPrivacyOptions(testActivity())
        val start = CountDownLatch(1)
        val initialized = thread {
            start.await()
            initializationComplete()
        }
        val consentUpdated = thread {
            start.await()
            sdk.completePrivacy(null)
        }
        start.countDown()
        initialized.join()
        consentUpdated.join()

        assertFalse(controller.state.value.consentAllowsAds)
        assertFalse(controller.state.value.privacyOptionsRequired)
        assertTrue(controller.state.value.adsSdkReady)
        assertFalse(controller.state.value.canRequestAds)
    }

    @Test
    fun `privacy options republishes the snapshot and forwards SDK errors`() = runTest {
        val sdk = FakeConsentSdk(ConsentSnapshot(false, false))
        val controller = controller(sdk)
        var reportedError: String? = null

        controller.showPrivacyOptions(testActivity()) { reportedError = it }
        sdk.currentSnapshot = ConsentSnapshot(true, true)
        sdk.completePrivacy("privacy form failed")

        assertEquals("privacy form failed", reportedError)
        assertTrue(controller.state.value.consentAllowsAds)
        assertTrue(controller.state.value.privacyOptionsRequired)
    }

    @Test
    fun `ads initialization logs only local start and success milestones`() = runTest {
        val messages = mutableListOf<String>()
        val controller = controller(
            FakeConsentSdk(ConsentSnapshot(true, false)),
            initializer = AdsSdkInitializer { it() },
            log = messages::add,
        )

        controller.setEligible(true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "Mobile Ads initialization started",
                "Mobile Ads initialization succeeded",
            ),
            messages,
        )
    }

    private fun TestScope.controller(
        sdk: FakeConsentSdk,
        initializer: AdsSdkInitializer = AdsSdkInitializer { _ -> },
        activityIsUsable: (Activity) -> Boolean = { true },
        log: (String) -> Unit = {},
    ) = AdsConsentController(
        scope = this,
        consentSdk = sdk,
        adsSdkInitializer = initializer,
        retryDelay = {},
        activityIsUsable = activityIsUsable,
        adsDispatcher = StandardTestDispatcher(testScheduler),
        log = log,
    )

    private class FakeConsentSdk(
        var currentSnapshot: ConsentSnapshot,
        private val snapshotAfterRequest: ConsentSnapshot? = null,
    ) : ConsentSdk {
        var updateRequests = 0
        var formRequests = 0
        private var updateSuccess: (() -> Unit)? = null
        private var updateFailure: (() -> Unit)? = null
        private var formComplete: ((String?) -> Unit)? = null
        private var privacyComplete: ((String?) -> Unit)? = null

        override fun snapshot(): ConsentSnapshot = currentSnapshot

        override fun requestConsentInfoUpdate(
            activity: Activity,
            onSuccess: () -> Unit,
            onFailure: () -> Unit,
        ) {
            updateRequests += 1
            snapshotAfterRequest?.let { currentSnapshot = it }
            updateSuccess = onSuccess
            updateFailure = onFailure
        }

        override fun loadAndShowConsentFormIfRequired(
            activity: Activity,
            onComplete: (String?) -> Unit,
        ) {
            formRequests += 1
            formComplete = onComplete
        }

        override fun showPrivacyOptions(activity: Activity, onComplete: (String?) -> Unit) {
            privacyComplete = onComplete
        }

        fun succeedUpdate() {
            updateSuccess?.also { updateSuccess = null }?.invoke()
        }

        fun failUpdate() {
            updateFailure?.also { updateFailure = null }?.invoke()
        }

        fun completeForm(error: String?) {
            formComplete?.also { formComplete = null }?.invoke(error)
        }

        fun completePrivacy(error: String?) {
            privacyComplete?.also { privacyComplete = null }?.invoke(error)
        }
    }
}
