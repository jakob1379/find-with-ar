package dev.arsignallocator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.arsignallocator.monetization.canInitializeAds
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.ui.SignalLocatorApp
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingExport: File? = null
    private lateinit var billing: BillingEntitlementController
    private lateinit var consent: AdsConsentController
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Could not open the selected destination")
        }.onFailure { error ->
            Toast.makeText(this, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
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
        val traceDirectory = File(filesDir, "traces")
        val scanner = AndroidBleScanner(applicationContext)
        val viewModel = ViewModelProvider(
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
                onLeaveTracking = viewModel::stopAndReturnToDevices,
                onFoundTarget = { viewModel.completeTrace(TraceOutcome.YES) },
                onCompleteScan = {
                    showShellMessage("Complete Scan Pro is not connected in this build")
                },
                onPro = { showShellMessage("Pro purchases are not connected in this build") },
                onDataHistory = { showShellMessage("Session history is not connected in this build") },
                onPrivacyChoices = {
                    showShellMessage("Privacy choices are not connected in this build")
                },
                onContactSupport = {
                    showShellMessage("The publisher support address has not been configured")
                },
                onNotices = { showShellMessage("Third-party notices are not connected in this build") },
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
        if (!file.exists()) {
            Toast.makeText(this, "No completed trace is available", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = file
        exportLauncher.launch(file.name)
    }
}
