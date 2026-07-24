package dev.arsignallocator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.ble.AndroidBleScanner
import dev.arsignallocator.core.ElapsedRealtimeClock
import dev.arsignallocator.localization.LocalizationAdapter
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.ui.SignalLocatorApp
import java.io.File

class MainActivity : ComponentActivity() {
    private var pendingExport: File? = null
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
                traceDirectory = traceDirectory,
                onExport = ::exportTrace,
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

    private fun exportTrace(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "No completed trace is available", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = file
        exportLauncher.launch(file.name)
    }
}
