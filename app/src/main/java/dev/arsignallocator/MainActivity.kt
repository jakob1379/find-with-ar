package dev.arsignallocator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.ble.AndroidBleScanner
import dev.arsignallocator.core.ElapsedRealtimeClock
import dev.arsignallocator.localization.LocalizationAdapter
import dev.arsignallocator.monetization.AdsEligibility
import dev.arsignallocator.monetization.AdsConsentController
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.BillingEntitlementController
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.canInitializeAds
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceSummary
import dev.arsignallocator.ui.SignalLocatorApp
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingExport: File? = null
    private lateinit var traceDirectory: File
    private lateinit var billing: BillingEntitlementController
    private lateinit var consent: AdsConsentController
    private lateinit var viewModel: LocatorViewModel
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
        consent.gatherConsent(this)
        billing = BillingEntitlementController(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                billing.state.collect(::updateAdsEligibility)
            }
        }
        billing.refresh()
        traceDirectory = File(filesDir, "traces")
        pendingExport = completedTraceAt(
            savedInstanceState?.getString(PENDING_EXPORT_PATH),
            traceDirectory,
        )
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
                        traceRecorder = TraceRecorder(traceDirectory),
                        clock = ElapsedRealtimeClock,
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        completeScanEntitlement = { billing.state.value.entitlement },
                        locationEngineFactory = { LocalizationAdapter() },
                        synchronizerFactory = { SampleSynchronizer() },
                    ) as T
                }
            },
        )[LocatorViewModel::class.java]
        val arPoseSource = ArCorePoseSource(this, lifecycle)
        viewModel.bindPoseSource(arPoseSource)

        setContent {
            SignalLocatorApp(
                viewModel = viewModel,
                arPoseSource = arPoseSource,
                onExport = ::exportTrace,
                onShare = ::shareTrace,
                onSendSupport = ::sendSupport,
                onCompleteScanPurchase = {
                    if (billing.state.value.entitlement == EntitlementState.PENDING) {
                        showShellMessage("Your Pro purchase is still pending")
                    } else if (!billing.launchPurchase(this)) {
                        showShellMessage(
                            billing.state.value.retryableError
                                ?: "Pro purchase is temporarily unavailable",
                        )
                    }
                },
                onPro = { showShellMessage("Pro purchases are not connected in this build") },
                onPrivacyChoices = {
                    showShellMessage("Privacy choices are not connected in this build")
                },
            )
        }
    }

    private fun showShellMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (::billing.isInitialized) billing.refresh()
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

    private fun updateAdsEligibility(state: BillingState) {
        val eligible = canInitializeAds(
            AdsEligibility(
                adsEnabled = BuildConfig.ADS_ENABLED,
                isDebugBuild = BuildConfig.DEBUG,
                debugTestAdsEnabled = BuildConfig.DEBUG_TEST_ADS_ENABLED,
                entitlement = state.entitlement,
                billingAuthoritative = state.isAuthoritative,
            ),
        )
        consent.setEligible(eligible)
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
