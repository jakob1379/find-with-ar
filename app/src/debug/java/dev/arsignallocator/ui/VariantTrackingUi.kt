package dev.arsignallocator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.arsignallocator.model.LocatorUiState
import java.io.File

internal const val variantTrackingPanelAvailable = true

@Composable
internal fun VariantTrackingAction(open: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) {
        Text(if (open) "Find" else "Diagnostics", color = Color.White)
    }
}

@Composable
internal fun VariantTrackingPanel(
    state: LocatorUiState,
    visualizations: TrackingVisualizations,
    onVisualizationsChange: (TrackingVisualizations) -> Unit,
    onExport: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = Color.Black.copy(alpha = 0.82f)) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            item {
                Text(
                    "Developer diagnostics",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Text(
                    "Location visualizations",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(LocationVisualization.entries) { visualization ->
                VisualizationToggle(
                    visualization.label,
                    visualization.enabled(visualizations),
                    onToggle = {
                        onVisualizationsChange(visualizations.toggled(visualization))
                    },
                )
            }
            state.lastTraceFile?.let { trace ->
                item {
                    FilledTonalButton(onClick = { onExport(trace) }) {
                        Text("Export trace")
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.DarkGray)
            }
            item {
                Text(
                    "Session diagnostics",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item { DebugRow("AR", state.arStatus.status.name) }
            item {
                DebugRow(
                    "RSSI raw / median",
                    "${state.metrics.rawRssiDbm} / ${state.metrics.rollingMedianRssiDbm?.toInt()}",
                )
            }
            item { DebugRow("Packets", state.metrics.receivedCount.toString()) }
            item { DebugRow("Independent updates", state.metrics.acceptedCount.toString()) }
            item { DebugRow("Rejected: no pose", state.metrics.rejectedNoPoseCount.toString()) }
            item { DebugRow("Path", "${"%.2f".format(state.metrics.pathLengthMetres)} m") }
            item { DebugRow("Estimator", state.estimate?.status ?: "not initialized") }
            item {
                DebugRow(
                    "Ambiguity",
                    state.estimate?.ambiguity?.let { "%.3f".format(it) } ?: "—",
                )
            }
            item {
                DebugRow(
                    "Region area",
                    state.estimate?.regionAreaSquareMetres?.let { "%.2f m²".format(it) } ?: "—",
                )
            }
            item {
                DebugRow(
                    "Covariance xx/xz/zz",
                    state.estimate?.let {
                        "${it.covarianceXX} / ${it.covarianceXZ} / ${it.covarianceZZ}"
                    } ?: "—",
                )
            }
            state.recoverableError?.let { item { Text(it, color = Color(0xFFFFB4AB)) } }
        }
    }
}

internal enum class LocationVisualization(val label: String) {
    AR_HEATMAP("AR heatmap"),
    AR_ELLIPSE("AR ellipse"),
    AR_POINT("AR point"),
    TOP_DOWN_MAP("Top-down map"),
    BREADCRUMB_PATH("Breadcrumb path"),
    ;

    internal fun enabled(visualizations: TrackingVisualizations): Boolean = when (this) {
        AR_HEATMAP -> visualizations.arHeatmap
        AR_ELLIPSE -> visualizations.arEllipse
        AR_POINT -> visualizations.arPoint
        TOP_DOWN_MAP -> visualizations.topDownMap
        BREADCRUMB_PATH -> visualizations.breadcrumbPath
    }
}

internal fun TrackingVisualizations.toggled(
    visualization: LocationVisualization,
): TrackingVisualizations = when (visualization) {
    LocationVisualization.AR_HEATMAP -> copy(arHeatmap = !arHeatmap)
    LocationVisualization.AR_ELLIPSE -> copy(arEllipse = !arEllipse)
    LocationVisualization.AR_POINT -> copy(arPoint = !arPoint)
    LocationVisualization.TOP_DOWN_MAP -> copy(topDownMap = !topDownMap)
    LocationVisualization.BREADCRUMB_PATH -> copy(breadcrumbPath = !breadcrumbPath)
}

@Composable
private fun VisualizationToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            checked,
            role = Role.Switch,
            onValueChange = { onToggle() },
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray)
        Spacer(Modifier.width(12.dp))
        Text(value, color = Color.White)
    }
}
