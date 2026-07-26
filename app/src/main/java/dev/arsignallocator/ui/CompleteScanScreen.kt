@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arsignallocator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.CompleteScanDevice
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.LocatorUiState

@Composable
internal fun CompleteScanScreen(
    state: LocatorUiState,
    arPoseSource: ArCorePoseSource,
    onFocus: (DeviceKey) -> Unit,
    onFinishScan: () -> Unit,
) {
    val projection by arPoseSource.projection.collectAsStateWithLifecycle()
    val focusedDevice = state.completeScanDevices
        .firstOrNull { it.key == state.focusedDeviceKey }
        ?: state.completeScanDevices.firstOrNull()
    val visualization = selectEstimateVisualization(focusedDevice?.estimate)
    BackHandler(onBack = onFinishScan)
    BottomSheetScaffold(
        sheetPeekHeight = 112.dp,
        sheetContent = {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Complete Scan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.observedDeviceCount} observed · " +
                            "${state.localizedDeviceCount} localized",
                    )
                    Text("Pull up for devices", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = onFinishScan,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Finish Complete Scan"
                    },
                ) {
                    Text("Finish")
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.completeScanDevices.isEmpty()) {
                    item {
                        Text(
                            "Not enough signal yet. Keep moving while nearby devices advertise " +
                                "or remain discoverable.",
                        )
                    }
                }
                items(state.completeScanDevices, key = { it.key.value }) { device ->
                    CompleteScanDeviceCard(
                        device = device,
                        focused = state.focusedDeviceKey == device.key,
                        pathLengthMetres = state.metrics.pathLengthMetres,
                        onFocus = { onFocus(device.key) },
                    )
                }
            }
        },
    ) {
        TrackingCamera(
            state = state,
            projection = projection,
            arPoseSource = arPoseSource,
            estimate = focusedDevice?.estimate,
            visualization = visualization,
            showHeatmap = visualization != EstimateVisualization.HIDDEN,
            showEllipse = visualization != EstimateVisualization.HIDDEN,
            showPoint = visualization == EstimateVisualization.POINT,
            showMap = true,
            showBreadcrumbs = true,
            statusContent = TrackingStatusContent(
                title = "Complete Scan",
                instruction = state.arStatus.detail
                    ?: "Move around the room to narrow each device's likely area.",
            ),
            onRetryAr = arPoseSource::retry,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CompleteScanDeviceCard(
    device: CompleteScanDevice,
    focused: Boolean,
    pathLengthMetres: Double,
    onFocus: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            Text("Session ${device.key.value}", style = MaterialTheme.typography.labelMedium)
            Text(
                when (device.transport) {
                    BluetoothTransport.LOW_ENERGY -> "Low Energy"
                    BluetoothTransport.CLASSIC -> "Classic discovery"
                },
            )
            Text(
                completeScanDeviceStatus(device, pathLengthMetres),
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(
                onClick = onFocus,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (focused) "Shown in AR" else "Show in AR")
            }
        }
    }
}

internal fun completeScanDeviceStatus(
    device: CompleteScanDevice,
    pathLengthMetres: Double,
): String = when {
    device.lastSeen -> "Last seen · estimate frozen"
    device.metrics.receivedCount < 3 -> "Not enough signal"
    device.metrics.acceptedCount < 3 || pathLengthMetres < 1.0 -> "More movement needed"
    selectEstimateVisualization(device.estimate) == EstimateVisualization.POINT ->
        "Likely area found"
    selectEstimateVisualization(device.estimate) == EstimateVisualization.REGION ->
        "More movement needed"
    else -> "Not enough signal"
}
