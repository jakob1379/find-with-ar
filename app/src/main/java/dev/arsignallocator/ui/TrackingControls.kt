package dev.arsignallocator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.arsignallocator.R
import dev.arsignallocator.model.LocatorUiState
import java.io.File

@Composable
internal fun TrackingControls(
    state: LocatorUiState,
    policy: TrackingUiPolicy,
    statusContent: TrackingStatusContent,
    variantPanelOpen: Boolean,
    visualizations: TrackingVisualizations,
    onVisualizationsChange: (TrackingVisualizations) -> Unit,
    onLeaveSession: () -> Unit,
    onFoundTarget: () -> Unit,
    onToggleVariantPanel: () -> Unit,
    onExport: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = Color.Black.copy(alpha = 0.92f)) {
        if (variantPanelOpen) {
            Column {
                TrackingSessionHeader(
                    state = state,
                    onLeaveSession = onLeaveSession,
                    onFoundTarget = onFoundTarget,
                    onToggleVariantPanel = onToggleVariantPanel,
                )
                VariantTrackingPanel(
                    state,
                    visualizations,
                    onVisualizationsChange = onVisualizationsChange,
                    onExport = onExport,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        } else {
            TrackingStatusPanel(
                state = state,
                policy = policy,
                statusContent = statusContent,
                onLeaveSession = onLeaveSession,
                onFoundTarget = onFoundTarget,
                onToggleVariantPanel = onToggleVariantPanel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TrackingSessionHeader(
    state: LocatorUiState,
    onLeaveSession: () -> Unit,
    onFoundTarget: () -> Unit,
    onToggleVariantPanel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.selected?.displayName.orEmpty(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        TrackingSessionActions(onLeaveSession, onFoundTarget)
        VariantTrackingAction(open = true, onToggle = onToggleVariantPanel)
    }
}

@Composable
private fun TrackingStatusPanel(
    state: LocatorUiState,
    policy: TrackingUiPolicy,
    statusContent: TrackingStatusContent,
    onLeaveSession: () -> Unit,
    onFoundTarget: () -> Unit,
    onToggleVariantPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signal = signalPresentation(policy.signalLost, state.metrics.rollingMedianRssiDbm)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                state.selected?.displayName.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    statusContent.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(statusContent.instruction, color = Color.White)
            }
        }
        item {
            Text(
                signal.label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        signal.progress?.let { progress ->
            item {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clearAndSetSemantics { },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.tracking_signal_colder),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        stringResource(R.string.tracking_signal_warmer),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        item { TrackingSessionActions(onLeaveSession, onFoundTarget) }
        if (variantTrackingPanelAvailable) {
            item {
                VariantTrackingAction(open = false, onToggle = onToggleVariantPanel)
            }
        }
    }
}

@Composable
private fun TrackingSessionActions(
    onLeaveSession: () -> Unit,
    onFoundTarget: () -> Unit,
) {
    val foundDescription = stringResource(R.string.tracking_found_accessibility)
    val devicesDescription = stringResource(R.string.tracking_devices_accessibility)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onFoundTarget,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = foundDescription
            },
        ) {
            Text(stringResource(R.string.tracking_found))
        }
        FilledTonalButton(
            onClick = onLeaveSession,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = devicesDescription
            },
        ) {
            Text(stringResource(R.string.tracking_devices))
        }
    }
}

@Composable
internal fun TrackingBannerSlot(
    visible: Boolean,
    sideRail: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = if (sideRail) {
            modifier.size(width = 320.dp, height = 50.dp)
        } else {
            modifier.fillMaxWidth().heightIn(min = 50.dp)
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
