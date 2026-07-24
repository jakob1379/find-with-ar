@file:Suppress("UNUSED_PARAMETER")

package dev.arsignallocator.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.arsignallocator.model.LocatorUiState
import java.io.File

internal const val variantTrackingPanelAvailable = false

@Composable
internal fun VariantTrackingAction(open: Boolean, onToggle: () -> Unit) = Unit

@Composable
internal fun VariantTrackingPanel(
    state: LocatorUiState,
    visualizations: TrackingVisualizations,
    onVisualizationsChange: (TrackingVisualizations) -> Unit,
    onExport: (File) -> Unit,
    modifier: Modifier = Modifier,
) = Unit
