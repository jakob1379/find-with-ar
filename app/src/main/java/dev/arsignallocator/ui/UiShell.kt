@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arsignallocator.ui

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.arsignallocator.R
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.LocatorUiState
import java.io.File

internal enum class DevicePage { PICKER, SETTINGS, HELP, ABOUT }

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
) {
    var query by remember { mutableStateOf("") }
    var showUnnamed by remember { mutableStateOf(false) }
    val groups = groupDeviceCandidates(state.candidates, query)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Nearby devices") }) },
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
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Last local trace", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onBack: () -> Unit,
    onPro: () -> Unit,
    onDataHistory: () -> Unit,
    onPrivacyChoices: () -> Unit,
    onHelp: () -> Unit,
    onContactSupport: () -> Unit,
    onAbout: () -> Unit,
) {
    ShellScaffold("Settings", onBack) {
        item {
            SettingsAction(
                title = "Pro",
                detail = "Complete Scan + no ads",
                onClick = onPro,
            )
        }
        item {
            SettingsAction(
                title = "Data",
                detail = "Session history, export, sharing, and deletion",
                onClick = onDataHistory,
            )
        }
        item {
            SettingsAction(
                title = "Privacy and ads",
                detail = "Privacy policy and privacy choices",
                onClick = onPrivacyChoices,
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
    }
}

@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    ShellScaffold("Help and safety", onBack) {
        item { HelpSafetyContent() }
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
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
