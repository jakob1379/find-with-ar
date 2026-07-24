@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arsignallocator.ui

import android.Manifest
import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.arsignallocator.TRACE_CONTENT_DISCLOSURE
import dev.arsignallocator.R
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.redactedDescription
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.ConsentState
import dev.arsignallocator.trace.TraceSummary
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

internal enum class DevicePage { PICKER, SETTINGS, DATA, HELP, SUPPORT, ABOUT, NOTICES }

internal data class DeviceCandidateGroups(
    val named: List<DeviceCandidate>,
    val unnamed: List<DeviceCandidate>,
)

internal fun groupDeviceCandidates(
    candidates: List<DeviceCandidate>,
    query: String,
): DeviceCandidateGroups {
    val matching = if (query.isBlank()) {
        candidates
    } else {
        candidates.filter {
            it.handle.advertisedName?.contains(query.trim(), ignoreCase = true) == true
        }
    }
    return DeviceCandidateGroups(
        named = matching.filter { !it.handle.advertisedName.isNullOrBlank() },
        unnamed = matching.filter { it.handle.advertisedName.isNullOrBlank() },
    )
}

@Composable
internal fun OnboardingScreen(onContinue: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Find the likely area, not a false exact direction",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item { OnboardingIllustration() }
            item { HelpSafetyContent() }
            item {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun OnboardingIllustration() {
    val teal = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.colorScheme.tertiary
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).semantics {
            contentDescription =
                "A phone moves sideways around a device while a broad likely area narrows"
        },
    ) {
        val centre = Offset(size.width * 0.68f, size.height * 0.52f)
        drawCircle(teal.copy(alpha = 0.12f), size.minDimension * 0.38f, centre)
        drawCircle(teal.copy(alpha = 0.22f), size.minDimension * 0.24f, centre)
        drawCircle(gold, size.minDimension * 0.07f, centre)
        val phoneStart = Offset(size.width * 0.18f, size.height * 0.70f)
        val phoneEnd = Offset(size.width * 0.38f, size.height * 0.30f)
        drawLine(teal, phoneStart, phoneEnd, strokeWidth = 8f)
        drawLine(
            teal,
            phoneEnd,
            Offset(phoneEnd.x - 16f, phoneEnd.y + 3f),
            strokeWidth = 8f,
        )
        drawLine(
            teal,
            phoneEnd,
            Offset(phoneEnd.x - 3f, phoneEnd.y + 16f),
            strokeWidth = 8f,
        )
        drawRoundRect(
            color = Color(0xFF263238),
            topLeft = Offset(phoneStart.x - 18f, phoneStart.y - 30f),
            size = androidx.compose.ui.geometry.Size(36f, 60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
        )
    }
}

@Composable
internal fun HelpSafetyContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Only currently advertising Low Energy devices or discoverable Classic devices " +
                "can appear. Wake the target or put it in pairing mode.",
        )
        Text(
            "Keep the target still and stay in the same room. Move the phone sideways or around " +
                "the target so the likely area can narrow.",
        )
        Text(
            "The camera measures phone movement; frames are not recorded. Nearby-device scanning " +
                "and precise-location permission enable the estimate, but the app does not read " +
                "GPS coordinates.",
        )
        Text(
            "Measurements stay on this phone unless you explicitly export or share a trace.",
        )
        Text(
            "Estimates can be wrong. Watch for obstacles, stay aware of your surroundings, and " +
                "only locate devices you own or have permission to find.",
        )
        Text(
            "Complete Scan may observe other people's devices in radio range. Use it only in " +
                "spaces you have permission to survey.",
        )
        Text(
            "AR Device Finder is not an emergency or theft-recovery service.",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun PermissionScreen(
    missingPermissions: List<String>,
    permissionWasRequested: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            item {
                Text("Camera and nearby-device access", style = MaterialTheme.typography.headlineSmall)
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Text(
                    "The camera measures phone movement without recording frames. Nearby-device " +
                        "scanning and precise location let the app combine radio observations with " +
                        "that movement; GPS coordinates are not read.",
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Text(
                    "Needed now: " + missingPermissions.joinToString { permissionLabel(it) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
            item {
                Button(onClick = onRequest) {
                    Text(if (permissionWasRequested) "Try again" else "Allow access")
                }
            }
            if (permissionWasRequested) {
                item { TextButton(onClick = onOpenSettings) { Text("Open app settings") } }
                item {
                    Text(
                        "If Android no longer shows a permission prompt, enable access in app settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.CAMERA -> "camera"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "approximate location"
    Manifest.permission.ACCESS_FINE_LOCATION -> "precise location"
    Manifest.permission.BLUETOOTH_SCAN -> "nearby-device scanning"
    Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth status"
    else -> permission.substringAfterLast('.')
}

@Composable
internal fun BluetoothScreen(unsupported: Boolean, onEnable: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (unsupported) "Bluetooth is not supported" else "Bluetooth is turned off",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text("A target must be advertising or discoverable before it can appear.")
            if (!unsupported) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onEnable) { Text("Turn on Bluetooth") }
            }
        }
    }
}

@Composable
internal fun DevicePickerScreen(
    state: LocatorUiState,
    onRefresh: () -> Unit,
    onSelect: (DeviceCandidate) -> Unit,
    onCompleteScan: () -> Unit,
    onSettings: () -> Unit,
    onExport: (File) -> Unit,
    onDelete: () -> Unit,
    banner: @Composable () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var showUnnamed by remember { mutableStateOf(false) }
    val groups = groupDeviceCandidates(state.candidates, query)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Nearby devices") }) },
        bottomBar = banner,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Button(onClick = onCompleteScan, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Complete Scan")
                        Surface(
                            color = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Pro", Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan again")
                }
            }
            item {
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Settings")
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search device names") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    if (state.isDiscovering) {
                        "Scanning for ${state.discoverySecondsRemaining}s…"
                    } else {
                        state.guidance
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.isDiscovering) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.recoverableError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            items(groups.named, key = { it.key.value }) { candidate ->
                DeviceCard(candidate, onClick = { onSelect(candidate) })
            }
            if (groups.unnamed.isNotEmpty()) {
                item {
                    FilledTonalButton(
                        onClick = { showUnnamed = !showUnnamed },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showUnnamed) {
                                "Hide other devices"
                            } else {
                                "Show other devices (${groups.unnamed.size})"
                            },
                        )
                    }
                }
                if (showUnnamed) {
                    items(groups.unnamed, key = { it.key.value }) { candidate ->
                        DeviceCard(candidate, onClick = { onSelect(candidate) })
                    }
                }
            }
            if (!state.isDiscovering && groups.named.isEmpty() && groups.unnamed.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) {
                            "No suitable device appeared. Wake it or put it in pairing mode, then scan again."
                        } else {
                            "No currently observed named device matches this search."
                        },
                    )
                }
            }
            state.lastTraceFile?.let { trace ->
                item {
                    Text("Last local trace", style = MaterialTheme.typography.titleSmall)
                    FilledTonalButton(
                        onClick = { onExport(trace) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export")
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete local traces")
                    }
                }
            }
            item {
                Text(
                    "Low Energy targets must advertise. Classic targets must remain discoverable, " +
                        "usually in pairing mode. A paired but silent device cannot be found.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(candidate: DeviceCandidate, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                buildList {
                    if (candidate.handle.bonded) add("System paired")
                    add(
                        when (candidate.handle.transport) {
                            BluetoothTransport.LOW_ENERGY -> "Low Energy"
                            BluetoothTransport.CLASSIC -> "Classic discovery"
                        },
                    )
                    add(signalLabel(candidate.medianRssiDbm))
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun signalLabel(rssiDbm: Double): String = when {
    rssiDbm >= -60.0 -> "Strong signal"
    rssiDbm >= -78.0 -> "Medium signal"
    else -> "Weak signal"
}

@Composable
internal fun SettingsScreen(
    billingState: BillingState,
    consentState: ConsentState,
    variantMonetizationUi: VariantMonetizationUi,
    privacyPolicyAvailable: Boolean,
    onBack: () -> Unit,
    onPro: () -> Unit,
    onDataHistory: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onPrivacyChoices: () -> Unit,
    onHelp: () -> Unit,
    onContactSupport: () -> Unit,
    onAbout: () -> Unit,
) {
    ShellScaffold("Settings", onBack) {
        item {
            ProSettingsCard(billingState, onOpen = onPro)
        }
        item {
            SettingsAction(
                title = "Data",
                detail = "Session history, export, sharing, and deletion",
                onClick = onDataHistory,
            )
        }
        item {
            PrivacyAndAdsCard(
                consentState = consentState,
                privacyPolicyAvailable = privacyPolicyAvailable,
                onPrivacyPolicy = onPrivacyPolicy,
                onPrivacyChoices = onPrivacyChoices,
            )
        }
        item {
            SettingsAction(
                title = "Help and safety",
                detail = "Supported devices, movement, privacy, and troubleshooting",
                onClick = onHelp,
            )
        }
        item {
            SettingsAction(
                title = "Contact support",
                detail = "Start a support request",
                onClick = onContactSupport,
            )
        }
        item {
            SettingsAction(
                title = "About",
                detail = "Version and third-party notices",
                onClick = onAbout,
            )
        }
        item { VariantMonetizationSettings(variantMonetizationUi) }
    }
}

@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    ShellScaffold("Help and safety", onBack) {
        item { HelpSafetyContent() }
        item {
            Text(
                "Troubleshooting",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            Text(
                "Device missing: wake it, enable advertising, or keep a Classic device in " +
                    "pairing mode, then scan again. A paired but silent device cannot appear.",
            )
        }
        item {
            Text(
                "Area not narrowing: keep the target still, uncover the camera, and move " +
                    "sideways to collect measurements from different positions.",
            )
        }
        item {
            Text(
                "Signal or AR lost: return toward the last strong reading, move slowly, and " +
                    "wait for tracking to recover before relying on the displayed area.",
            )
        }
    }
}

@Composable
internal fun DataHistoryScreen(
    summaries: List<TraceSummary>,
    error: String?,
    onBack: () -> Unit,
    onExport: (File) -> Unit,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var transfer by remember { mutableStateOf<Pair<String, File>?>(null) }
    var delete by remember { mutableStateOf<File?>(null) }
    var deleteAll by remember { mutableStateOf(false) }
    ShellScaffold("Data", onBack) {
        error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (summaries.isEmpty()) {
            item { Text("No completed sessions yet.") }
        } else {
            items(summaries, key = { it.file.name }) { summary ->
                SessionHistoryCard(
                    summary = summary,
                    onExport = { transfer = "Export" to summary.file },
                    onShare = { transfer = "Share" to summary.file },
                    onDelete = { delete = summary.file },
                )
            }
            item {
                TextButton(
                    onClick = { deleteAll = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Delete all")
                }
            }
        }
    }
    transfer?.let { (action, file) ->
        DisclosureDialog(
            action = action,
            onConfirm = {
                transfer = null
                if (action == "Export") onExport(file) else onShare(file)
            },
            onDismiss = { transfer = null },
        )
    }
    delete?.let { file ->
        ConfirmationDialog(
            title = "Delete this session?",
            detail = "This local trace cannot be recovered after deletion.",
            confirmLabel = "Delete session",
            onConfirm = {
                delete = null
                onDelete(file)
            },
            onDismiss = { delete = null },
        )
    }
    if (deleteAll) {
        ConfirmationDialog(
            title = "Delete all sessions?",
            detail = "All local session traces will be permanently deleted.",
            confirmLabel = "Delete all",
            onConfirm = {
                deleteAll = false
                onDeleteAll()
            },
            onDismiss = { deleteAll = false },
        )
    }
}

@Composable
private fun SessionHistoryCard(
    summary: TraceSummary,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val started = remember(summary.startedAtEpochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(summary.startedAtEpochMillis))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                started,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(summary.redactedDescription())
            Text("Duration ${formatDuration(summary.durationMillis)}")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Export session from $started"
                    },
                ) {
                    Text("Export")
                }
                TextButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Share session from $started"
                    },
                ) {
                    Text("Share")
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Delete session from $started"
                    },
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

internal fun formatDuration(durationMillis: Long): String {
    val seconds = durationMillis / 1_000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun ContactSupportScreen(
    supportEmail: String,
    summaries: List<TraceSummary>,
    onBack: () -> Unit,
    onSend: (TraceSummary?, File?) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<TraceSummary?>(null) }
    var attachDisclosure by remember { mutableStateOf(false) }
    ShellScaffold("Contact support", onBack) {
        item {
            Text(
                "Support email",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(supportEmail.ifBlank { "Not configured for this build" })
            Button(
                enabled = supportEmail.isNotBlank(),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Support email", supportEmail)),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Copy support email")
            }
        }
        item {
            Text(
                "Include a redacted session summary (optional)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        summaries.forEach { summary ->
            item(key = summary.file.name) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { selected = if (selected == summary) null else summary },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == summary,
                        onClick = { selected = if (selected == summary) null else summary },
                    )
                    Text(summary.redactedDescription())
                }
            }
        }
        item {
            Button(
                enabled = supportEmail.isNotBlank(),
                onClick = { onSend(selected, null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Email support without a trace")
            }
        }
        item {
            Button(
                enabled = supportEmail.isNotBlank() && selected != null,
                onClick = { attachDisclosure = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Attach selected trace and email")
            }
        }
        item {
            Text(
                "The draft includes the app version, Android version, phone model, and the " +
                    "selected redacted session summary. You control the message before sending.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (attachDisclosure) {
        DisclosureDialog(
            action = "Attach",
            onConfirm = {
                attachDisclosure = false
                selected?.let { onSend(it, it.file) }
            },
            onDismiss = { attachDisclosure = false },
        )
    }
}

@Composable
internal fun AboutScreen(appVersion: String, onBack: () -> Unit, onNotices: () -> Unit) {
    ShellScaffold("About", onBack) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Version $appVersion")
        }
        item {
            SettingsAction(
                title = "Third-party notices",
                detail = "Licences and required notices",
                onClick = onNotices,
            )
        }
    }
}

@Composable
internal fun NoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notices = remember {
        context.resources.openRawResource(R.raw.third_party_notices)
            .bufferedReader()
            .use { it.readText() }
    }
    ShellScaffold("Third-party notices", onBack) {
        item { Text(notices) }
    }
}

@Composable
private fun DisclosureDialog(
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action trace?") },
        text = { Text(TRACE_CONTENT_DISCLOSURE) },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SettingsAction(title: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ShellScaffold(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
