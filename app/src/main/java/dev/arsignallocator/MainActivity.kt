package dev.arsignallocator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.ble.AndroidBleScanner
import dev.arsignallocator.core.ElapsedRealtimeClock
import dev.arsignallocator.localization.LocalizationAdapter
import dev.arsignallocator.monetization.AdsEligibility
import dev.arsignallocator.monetization.AdsConsentController
import dev.arsignallocator.monetization.BillingEntitlementController
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.canInitializeAds
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceHistoryStore
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceSummary
import dev.arsignallocator.ui.SignalLocatorApp
import dev.arsignallocator.ui.rememberVariantMonetizationUi
import java.io.File

class MainActivity : ComponentActivity() {
    private var pendingExport: File? = null
    private lateinit var traceDirectory: File
    private lateinit var billing: BillingEntitlementController
    private lateinit var consent: AdsConsentController
    private lateinit var traceHistory: TraceHistoryStore
    private lateinit var viewModel: LocatorViewModel
    @Volatile
    private var effectiveCompleteScanEntitlement = EntitlementState.FREE
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri ->
        val source = completedTraceAt(pendingExport?.path, traceDirectory)
        pendingExport = null
        if (uri == null || source == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Could not open the selected destination")
        }.onFailure {
            Toast.makeText(this, "The trace could not be exported", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consent = AdsConsentController(applicationContext, lifecycleScope)
        billing = BillingEntitlementController(applicationContext)
        billing.refresh()
        traceDirectory = File(filesDir, "traces")
        pendingExport = completedTraceAt(
            savedInstanceState?.getString(PENDING_EXPORT_PATH),
            traceDirectory,
        )
        traceHistory = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(TraceHistoryStore::class.java))
                    return TraceHistoryStore(TraceRecorder(traceDirectory)) as T
                }
            },
        )[TraceHistoryStore::class.java]
        val scanner = AndroidBleScanner(applicationContext)
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LocatorViewModel::class.java))
                    return LocatorViewModel(
                        scanner = scanner,
                        locationEngine = LocalizationAdapter(),
                        synchronizer = SampleSynchronizer(),
                        traceHistoryStore = traceHistory,
                        clock = ElapsedRealtimeClock,
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        completeScanEntitlement = { effectiveCompleteScanEntitlement },
                        locationEngineFactory = { LocalizationAdapter() },
                        synchronizerFactory = { SampleSynchronizer() },
                    ) as T
                }
            },
        )[LocatorViewModel::class.java]
        val arPoseSource = ArCorePoseSource(this, lifecycle)
        viewModel.bindPoseSource(arPoseSource)

        setContent {
            val storeBillingState by billing.state.collectAsStateWithLifecycle()
            val consentState by consent.state.collectAsStateWithLifecycle()
            val variantMonetizationUi = rememberVariantMonetizationUi(storeBillingState)
            val billingState = variantMonetizationUi.effectiveBillingState
            SideEffect {
                effectiveCompleteScanEntitlement = billingState.entitlement
            }
            LaunchedEffect(
                billingState.entitlement,
                billingState.isAuthoritative,
                variantMonetizationUi.allowAds,
            ) {
                consent.setEligible(
                    canInitializeAds(
                        AdsEligibility(
                            adsEnabled = BuildConfig.ADS_ENABLED,
                            isDebugBuild = BuildConfig.DEBUG,
                            debugTestAdsEnabled = variantMonetizationUi.allowAds,
                            entitlement = billingState.entitlement,
                            billingAuthoritative = billingState.isAuthoritative,
                        ),
                    ),
                )
            }
            SignalLocatorApp(
                viewModel = viewModel,
                traceHistory = traceHistory,
                arPoseSource = arPoseSource,
                billingState = billingState,
                consentState = consentState,
                variantMonetizationUi = variantMonetizationUi,
                onExport = ::exportTrace,
                onShare = ::shareTrace,
                onSendSupport = ::sendSupport,
                onPurchasePro = ::purchasePro,
                onRestorePro = billing::refresh,
                privacyPolicyAvailable = BuildConfig.PRIVACY_URL.isNotBlank(),
                onPrivacyPolicy = ::openPrivacyPolicy,
                onPrivacyChoices = { consent.showPrivacyOptions(this) },
            )
        }
    }

    private fun showShellMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun purchasePro() {
        if (billing.state.value.entitlement == EntitlementState.PENDING) {
            showShellMessage("Your Pro purchase is still pending")
        } else if (!billing.launchPurchase(this)) {
            showShellMessage(
                billing.state.value.retryableError
                    ?: "Pro purchase is temporarily unavailable",
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::billing.isInitialized) billing.refresh()
        if (::consent.isInitialized) consent.gatherConsent(this)
    }

    override fun onStart() {
        super.onStart()
        if (::viewModel.isInitialized) viewModel.onAppStarted()
    }

    override fun onStop() {
        if (::viewModel.isInitialized) viewModel.onAppStopped()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        completedTraceAt(pendingExport?.path, traceDirectory)?.let {
            outState.putString(PENDING_EXPORT_PATH, it.path)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::billing.isInitialized) billing.close()
        super.onDestroy()
    }

    private fun openPrivacyPolicy() {
        val url = BuildConfig.PRIVACY_URL
        if (url.isBlank()) {
            showShellMessage("Privacy policy unavailable in this build")
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showShellMessage("No browser is available to open the privacy policy")
        }
    }

    private fun exportTrace(file: File) {
        if (!isCompletedTrace(file, traceDirectory)) {
            Toast.makeText(this, "No completed trace is available", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = file
        exportLauncher.launch(file.name)
    }

    private fun shareTrace(file: File) {
        val uri = traceUri(file) ?: return
        val chooser = Intent.createChooser(shareTraceIntent(contentResolver, uri), "Share trace")
        if (!launchExternal(chooser, ::startActivity)) {
            showShellMessage("No app is available to share the trace")
        }
    }

    private fun sendSupport(summary: TraceSummary?, attachment: File?) {
        val email = BuildConfig.SUPPORT_EMAIL
        if (email.isBlank()) {
            showShellMessage("Support email is not configured for this build")
            return
        }
        val uri = attachment?.let(::traceUri) ?: if (attachment == null) null else return
        val intent = supportIntent(
            supportEmail = email,
            details = SupportDetails(
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = Build.VERSION.RELEASE,
                phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                sessionSummary = summary?.redactedDescription(),
            ),
            attachment = uri,
            contentResolver = contentResolver,
        )
        val outgoing = if (uri == null) intent else Intent.createChooser(intent, "Email support")
        if (!launchExternal(outgoing, ::startActivity)) {
            showShellMessage("No email app is available. Copy the support email instead")
        }
    }

    private fun traceUri(file: File): Uri? {
        if (!isCompletedTrace(file, traceDirectory)) {
            showShellMessage("No completed trace is available")
            return null
        }
        return runCatching {
            FileProvider.getUriForFile(this, "$packageName.trace-provider", file)
        }.getOrElse {
            showShellMessage("The trace could not be shared")
            null
        }
    }

    private companion object {
        const val PENDING_EXPORT_PATH = "pending_export_path"
    }
}
