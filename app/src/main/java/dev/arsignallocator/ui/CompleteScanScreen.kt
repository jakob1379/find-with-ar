package dev.arsignallocator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    val estimates = state.completeScanDevices.mapIndexedNotNull { index, device ->
        val visualization = selectEstimateVisualization(device.estimate)
        device.estimate?.takeIf { visualization != EstimateVisualization.HIDDEN }?.let {
            val color = completeScanMapColor(index)
            MapEstimateItem(
                key = device.key,
                estimate = it,
                showPoint = visualization == EstimateVisualization.POINT,
                regionColor = color,
                markerColor = color,
            )
        }
    }
    BackHandler(onBack = onFinishScan)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Complete Scan",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "${state.observedDeviceCount} observed · " +
                    "${state.localizedDeviceCount} localized",
                color = Color.White,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!LocalInspectionMode.current) {
                AndroidView(
                    factory = { arPoseSource.surfaceView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            TopDownMap(
                breadcrumbs = state.breadcrumbs,
                estimates = estimates,
                projection = projection,
                showBreadcrumbs = true,
                talkBackSummary =
                    "${state.observedDeviceCount} devices observed and " +
                        "${state.localizedDeviceCount} localized on the room map.",
                focusedKey = state.focusedDeviceKey,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.completeScanDevices.isEmpty()) {
                item {
                    Text(
                        "Not enough signal yet. Keep moving while nearby devices advertise " +
                            "or remain discoverable.",
                        color = Color.White,
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
            item {
                Button(
                    onClick = onFinishScan,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Finish Complete Scan"
                    },
                ) {
                    Text("Finish scan")
                }
            }
        }
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
                Text(if (focused) "Focused" else "Focus on map")
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
