package dev.arsignallocator.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.OnConsentInfoUpdateFailureListener
import com.google.android.ump.ConsentInformation.OnConsentInfoUpdateSuccessListener
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dev.arsignallocator.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface ConsentSdk {
    fun snapshot(): ConsentSnapshot

    fun requestConsentInfoUpdate(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    )

    fun loadAndShowConsentFormIfRequired(
        activity: Activity,
        onComplete: (String?) -> Unit,
    )

    fun showPrivacyOptions(activity: Activity, onComplete: (String?) -> Unit)
}

private class UmpConsentSdk(context: Context) : ConsentSdk {
    private val consentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)

    override fun snapshot() = ConsentSnapshot(
        consentAllowsAds = consentInformation.canRequestAds(),
        privacyOptionsRequired =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
    )

    override fun requestConsentInfoUpdate(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            OnConsentInfoUpdateSuccessListener(onSuccess),
            OnConsentInfoUpdateFailureListener { onFailure() },
        )
    }

    override fun loadAndShowConsentFormIfRequired(
        activity: Activity,
        onComplete: (String?) -> Unit,
    ) {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
            onComplete(it?.message)
        }
    }

    override fun showPrivacyOptions(activity: Activity, onComplete: (String?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onComplete(it?.message) }
    }
}

internal fun interface AdsSdkInitializer {
    fun initialize(onComplete: () -> Unit)
}

class AdsConsentController internal constructor(
    private val scope: CoroutineScope,
    private val consentSdk: ConsentSdk,
    private val adsSdkInitializer: AdsSdkInitializer,
    private val retryDelay: suspend (Int) -> Unit = {
        delay(consentRetryDelayMillis(it))
    },
    private val activityIsUsable: (Activity) -> Boolean = {
        !it.isFinishing && !it.isDestroyed
    },
    private val adsDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val consentRetries = ConsentRetryGate(MAX_CONSENT_ATTEMPTS)
    private val adsInitializationStarted = AtomicBoolean()
    @Volatile private var eligible = false
    @Volatile private var adsSdkReady = false
    private val _state = MutableStateFlow(ConsentState())
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        consentSdk = UmpConsentSdk(context),
        adsSdkInitializer = AdsSdkInitializer { onComplete ->
            MobileAds.initialize(
                context.applicationContext,
                InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build(),
            ) { onComplete() }
        },
        log = { Log.i(LOG_TAG, it) },
    )

    init {
        publishConsentState()
    }

    fun setEligible(eligible: Boolean) {
        if (this.eligible == eligible) return
        this.eligible = eligible
        publishState()
        initializeAdsIfAllowed()
    }

    fun gatherConsent(activity: Activity) {
        if (!consentRetries.begin()) return
        consentSdk.requestConsentInfoUpdate(
            activity,
            {
                publishConsentState()
                initializeAdsIfAllowed()
                if (activityIsUsable(activity)) {
                    consentSdk.loadAndShowConsentFormIfRequired(activity) { error ->
                        publishConsentState()
                        initializeAdsIfAllowed()
                        if (error == null) {
                            consentRetries.finish(success = true)
                        } else {
                            retryConsent(activity)
                        }
                    }
                } else {
                    consentRetries.finish(success = false)
                }
            },
            {
                publishConsentState()
                initializeAdsIfAllowed()
                retryConsent(activity)
            },
        )
        // UMP exposes usable previous-session consent as soon as the update request starts.
        publishConsentState()
        initializeAdsIfAllowed()
    }

    fun showPrivacyOptions(activity: Activity, onComplete: (String?) -> Unit = {}) {
        consentSdk.showPrivacyOptions(activity) { error ->
            publishConsentState()
            initializeAdsIfAllowed()
            onComplete(error)
        }
    }

    private fun publishConsentState() {
        publishState(consentSdk.snapshot())
    }

    private fun publishState(snapshot: ConsentSnapshot? = null) {
        _state.update {
            updatedConsentState(
                previous = it,
                snapshot = snapshot,
                adsEligible = eligible,
                adsSdkReady = adsSdkReady,
            )
        }
    }

    private fun retryConsent(activity: Activity) {
        val failure = consentRetries.finish(success = false) ?: return
        val activityReference = WeakReference(activity)
        scope.launch {
            retryDelay(failure)
            val currentActivity = activityReference.get() ?: return@launch
            if (activityIsUsable(currentActivity)) {
                gatherConsent(currentActivity)
            }
        }
    }

    private fun initializeAdsIfAllowed() {
        if (!eligible ||
            !_state.value.consentAllowsAds ||
            !adsInitializationStarted.compareAndSet(false, true)
        ) {
            return
        }
        scope.launch(adsDispatcher) {
            if (!eligible) {
                adsInitializationStarted.set(false)
                publishState()
                initializeAdsIfAllowed()
                return@launch
            }
            log("Mobile Ads initialization started")
            adsSdkInitializer.initialize {
                adsSdkReady = true
                log("Mobile Ads initialization succeeded")
                publishState()
            }
        }
    }

    companion object {
        private const val LOG_TAG = "AdsConsentController"
        private const val MAX_CONSENT_ATTEMPTS = 3
    }
}
