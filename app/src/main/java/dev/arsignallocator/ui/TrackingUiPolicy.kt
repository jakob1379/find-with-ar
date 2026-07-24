package dev.arsignallocator.ui

import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.ESTIMATE_STATUS_GRID_MULTIMODAL
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.model.POINT_ESTIMATE_STATUSES

internal enum class EstimateVisualization {
    HIDDEN,
    REGION,
    POINT,
}

internal enum class TrackingLayout {
    PORTRAIT_BOTTOM,
    LANDSCAPE_SIDE_RAIL,
    LANDSCAPE_BOTTOM,
}

internal enum class PublicTrackingState(
    val title: String,
    val instruction: String,
    val freezesEstimate: Boolean = false,
) {
    SIGNAL_FOUND(
        "Signal found",
        "Move sideways while keeping the camera uncovered.",
    ),
    BROAD_AREA(
        "Collecting",
        "Broad area. Keep moving around the target from different sides.",
    ),
    NARROWING(
        "Narrowing",
        "Keep moving to confirm the most likely area.",
    ),
    LIKELY_AREA_FOUND(
        "Likely area found",
        "Search within the highlighted area and keep moving to confirm it.",
    ),
    SIGNAL_LOST(
        "Signal lost",
        "Move back toward the last stronger signal.",
        freezesEstimate = true,
    ),
    TRACKING_PAUSED(
        "Tracking paused",
        "Keep the camera uncovered and move slowly.",
        freezesEstimate = true,
    ),
    AR_INACTIVE(
        "Starting AR",
        "Preparing augmented reality.",
        freezesEstimate = true,
    ),
    AR_CHECKING(
        "Checking AR",
        "Checking whether this phone supports AR.",
        freezesEstimate = true,
    ),
    CAMERA_PERMISSION_REQUIRED(
        "Camera permission needed",
        "Allow camera access to start tracking.",
        freezesEstimate = true,
    ),
    INSTALL_REQUESTED(
        "Finish AR setup",
        "Finish installing AR support, then return.",
        freezesEstimate = true,
    ),
    AR_UNSUPPORTED(
        "AR unavailable",
        "This phone does not support AR tracking.",
        freezesEstimate = true,
    ),
    AR_READY(
        "Starting tracking",
        "Move the phone slowly to start tracking.",
        freezesEstimate = true,
    ),
    CAMERA_UNAVAILABLE(
        "Camera unavailable",
        "Close other camera apps, then retry.",
        freezesEstimate = true,
    ),
    AR_ERROR(
        "AR error",
        "Return to Devices and retry AR.",
        freezesEstimate = true,
    ),
    ;
}

internal data class TrackingUiPolicy(
    val publicState: PublicTrackingState,
    val signalLost: Boolean,
) {
    val freezesEstimate: Boolean = publicState.freezesEstimate || signalLost
}

internal data class TrackingStatusContent(
    val title: String,
    val instruction: String,
)

internal data class SignalPresentation(
    val label: String,
    val progress: Float?,
)

internal fun selectEstimateVisualization(estimate: EstimateUiModel?): EstimateVisualization {
    if (estimate == null) return EstimateVisualization.HIDDEN
    val hasMean = estimate.meanXMetres.isFiniteValue() && estimate.meanZMetres.isFiniteValue()
    val hasRegion = estimate.probabilityCells.any {
        it.probability.isFinite() && it.probability > 0.0
    } || (
        hasMean &&
            estimate.covarianceXX.isFiniteValue() &&
            estimate.covarianceXZ.isFiniteValue() &&
            estimate.covarianceZZ.isFiniteValue()
        )
    val supportsPoint = estimate.status in POINT_ESTIMATE_STATUSES &&
        hasMean &&
        hasRegion &&
        estimate.regionAreaSquareMetres.isPositiveFinite() &&
        (estimate.ambiguity == null || estimate.ambiguity <= MAX_POINT_AMBIGUITY)
    return when {
        supportsPoint -> EstimateVisualization.POINT
        hasRegion -> EstimateVisualization.REGION
        else -> EstimateVisualization.HIDDEN
    }
}

internal fun trackingUiPolicy(
    state: LocatorUiState,
    nowElapsedNanos: Long,
): TrackingUiPolicy {
    val signalLost = signalIsLost(state, nowElapsedNanos)
    val publicState = when (state.arStatus.status) {
        ArRuntimeStatus.INACTIVE -> PublicTrackingState.AR_INACTIVE
        ArRuntimeStatus.CHECKING -> PublicTrackingState.AR_CHECKING
        ArRuntimeStatus.CAMERA_PERMISSION_REQUIRED -> {
            PublicTrackingState.CAMERA_PERMISSION_REQUIRED
        }
        ArRuntimeStatus.INSTALL_REQUESTED -> PublicTrackingState.INSTALL_REQUESTED
        ArRuntimeStatus.UNSUPPORTED -> PublicTrackingState.AR_UNSUPPORTED
        ArRuntimeStatus.READY -> PublicTrackingState.AR_READY
        ArRuntimeStatus.TRACKING_PAUSED -> PublicTrackingState.TRACKING_PAUSED
        ArRuntimeStatus.CAMERA_UNAVAILABLE -> PublicTrackingState.CAMERA_UNAVAILABLE
        ArRuntimeStatus.ERROR -> PublicTrackingState.AR_ERROR
        ArRuntimeStatus.TRACKING -> when {
            signalLost -> PublicTrackingState.SIGNAL_LOST
            state.metrics.acceptedCount < MIN_ACCEPTED_OBSERVATIONS ||
                state.metrics.pathLengthMetres < MIN_PATH_LENGTH_METRES -> {
                PublicTrackingState.SIGNAL_FOUND
            }
            else -> when (selectEstimateVisualization(state.estimate)) {
                EstimateVisualization.HIDDEN -> PublicTrackingState.BROAD_AREA
                EstimateVisualization.POINT -> PublicTrackingState.LIKELY_AREA_FOUND
                EstimateVisualization.REGION -> if (
                    state.estimate?.status == ESTIMATE_STATUS_GRID_MULTIMODAL ||
                    (state.estimate?.ambiguity ?: 1.0) > MAX_POINT_AMBIGUITY
                ) {
                    PublicTrackingState.BROAD_AREA
                } else {
                    PublicTrackingState.NARROWING
                }
            }
        }
    }
    return TrackingUiPolicy(publicState, signalLost)
}

internal fun trackingStatusContent(
    policy: TrackingUiPolicy,
    estimateAvailable: Boolean,
): TrackingStatusContent {
    val state = policy.publicState
    val stateInstruction = if (
        state == PublicTrackingState.TRACKING_PAUSED && estimateAvailable
    ) {
        "Last stable estimate shown. ${state.instruction}"
    } else {
        state.instruction
    }
    if (!policy.signalLost) return TrackingStatusContent(state.title, stateInstruction)
    val signalInstruction = if (estimateAvailable) {
        "Last seen estimate shown. Move back toward the last stronger signal."
    } else {
        "No location estimate is available yet. Move back toward the last stronger signal."
    }
    return if (state == PublicTrackingState.SIGNAL_LOST) {
        TrackingStatusContent(state.title, signalInstruction)
    } else {
        TrackingStatusContent(
            title = "${state.title} and signal lost",
            instruction = "$stateInstruction $signalInstruction",
        )
    }
}

internal fun visibleEstimate(
    lastStable: EstimateUiModel?,
    current: EstimateUiModel?,
    frozen: Boolean,
): EstimateUiModel? = if (frozen) lastStable else current

internal class RememberedEstimate(initial: EstimateUiModel?) {
    private var estimate = initial
    private var frozen = false

    fun update(current: EstimateUiModel?, freeze: Boolean): EstimateUiModel? {
        if (!freeze || !frozen) estimate = current
        frozen = freeze
        return visibleEstimate(estimate, current, freeze)
    }
}

internal fun selectTrackingLayout(widthDp: Float, heightDp: Float): TrackingLayout = when {
    widthDp <= heightDp -> TrackingLayout.PORTRAIT_BOTTOM
    widthDp - SIDE_RAIL_WIDTH_DP >= MIN_CAMERA_WIDTH_DP -> TrackingLayout.LANDSCAPE_SIDE_RAIL
    else -> TrackingLayout.LANDSCAPE_BOTTOM
}

internal fun estimateTalkBackSummary(
    status: TrackingStatusContent,
    visualization: EstimateVisualization,
): String {
    val estimate = when (visualization) {
        EstimateVisualization.HIDDEN -> "No location estimate is shown yet."
        EstimateVisualization.REGION -> "A broad probability region is shown."
        EstimateVisualization.POINT -> "A likely point and its uncertainty region are shown."
    }
    return "Estimate map. ${status.title}. $estimate ${status.instruction}"
}

internal fun signalWarmthLabel(rollingMedianRssiDbm: Double?): String = when {
    rollingMedianRssiDbm == null -> "Waiting for signal"
    rollingMedianRssiDbm < -85.0 -> "Colder"
    rollingMedianRssiDbm < -70.0 -> "Cool"
    rollingMedianRssiDbm < -55.0 -> "Warm"
    else -> "Warmer"
}

internal fun signalPresentation(
    signalLost: Boolean,
    rollingMedianRssiDbm: Double?,
): SignalPresentation {
    val warmth = signalWarmthLabel(rollingMedianRssiDbm)
    if (signalLost) {
        return SignalPresentation(
            label = rollingMedianRssiDbm?.let { "Last seen: $warmth" } ?: "No recent signal",
            progress = null,
        )
    }
    return SignalPresentation(
        label = "Signal: $warmth",
        progress = (((rollingMedianRssiDbm ?: -100.0) + 100.0) / 70.0)
            .coerceIn(0.0, 1.0).toFloat(),
    )
}

private fun signalIsLost(state: LocatorUiState, nowElapsedNanos: Long): Boolean {
    val lastSeen = state.metrics.lastSeenElapsedNanos
        ?: state.selected?.lastSeenElapsedNanos
        ?: return false
    val timeout = if (state.selected?.handle?.transport == BluetoothTransport.CLASSIC) {
        CLASSIC_SIGNAL_LOST_NANOS
    } else {
        BLE_SIGNAL_LOST_NANOS
    }
    return nowElapsedNanos - lastSeen > timeout
}

private fun Double?.isFiniteValue(): Boolean = this != null && isFinite()

private fun Double?.isPositiveFinite(): Boolean = this != null && isFinite() && this > 0.0

private const val SIDE_RAIL_WIDTH_DP = 320f
private const val MIN_CAMERA_WIDTH_DP = 480f
private const val MIN_ACCEPTED_OBSERVATIONS = 3
private const val MIN_PATH_LENGTH_METRES = 1.0
private const val MAX_POINT_AMBIGUITY = 0.20
private const val BLE_SIGNAL_LOST_NANOS = 5_000_000_000L
private const val CLASSIC_SIGNAL_LOST_NANOS = 20_000_000_000L
