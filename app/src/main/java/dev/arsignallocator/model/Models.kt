package dev.arsignallocator.model

import java.io.File

@JvmInline
value class DeviceKey(val value: String)

enum class BluetoothTransport {
    LOW_ENERGY,
    CLASSIC,
}

/** Internal, process-local handle. Its radio address must never be persisted or shown. */
class TargetHandle internal constructor(
    val key: DeviceKey,
    internal val ephemeralAddress: String,
    val advertisedName: String?,
    val manufacturerIds: Set<Int>,
    val serviceUuids: Set<String>,
    val transport: BluetoothTransport = BluetoothTransport.LOW_ENERGY,
    val bonded: Boolean = false,
)

data class BleObservation(
    val observedAtPlatformNanos: Long,
    val receivedAtElapsedNanos: Long,
    val deviceKey: DeviceKey,
    val rssiDbm: Int,
    val txPowerDbm: Int?,
    val advertisedName: String?,
    val manufacturerIds: Set<Int>,
    val serviceUuids: Set<String>,
    val transport: BluetoothTransport = BluetoothTransport.LOW_ENERGY,
)

data class DeviceCandidate(
    val handle: TargetHandle,
    val latestRssiDbm: Int,
    val medianRssiDbm: Double,
    val observationsPerSecond: Double,
    val lastSeenElapsedNanos: Long,
) {
    val key: DeviceKey get() = handle.key
    val displayName: String get() = handle.advertisedName?.takeIf(String::isNotBlank)
        ?: "Bluetooth device ${handle.key.value}"
}

enum class PoseTrackingState { TRACKING, PAUSED, STOPPED }

data class PoseObservation(
    /** ARCore Frame timestamp. ARCore does not define this timestamp's clock domain. */
    val capturedAtPlatformNanos: Long,
    /** Timestamp taken in this callback using elapsedRealtimeNanos. */
    val receivedAtElapsedNanos: Long,
    val xMetres: Double,
    val yMetres: Double,
    val zMetres: Double,
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val qw: Double,
    val trackingState: PoseTrackingState,
)

data class SynchronizedObservation(
    val ble: BleObservation,
    val pose: PoseObservation?,
    val poseAssociationAgeNanos: Long?,
    val accepted: Boolean,
    val rejectionReason: String?,
    val filteredRssiDbm: Double?,
    val rssiVarianceDb2: Double?,
)

data class EstimateUiModel(
    val status: String,
    val meanXMetres: Double?,
    val meanZMetres: Double?,
    val covarianceXX: Double?,
    val covarianceXZ: Double?,
    val covarianceZZ: Double?,
    val regionProbability: Double?,
    val regionAreaSquareMetres: Double?,
    val ambiguity: Double?,
    val probabilityCells: List<ProbabilityCell> = emptyList(),
)

data class ProbabilityCell(
    val xMetres: Double,
    val zMetres: Double,
    val probability: Double,
    val resolutionMetres: Double,
)

enum class ArRuntimeStatus {
    INACTIVE,
    CHECKING,
    CAMERA_PERMISSION_REQUIRED,
    INSTALL_REQUESTED,
    UNSUPPORTED,
    READY,
    TRACKING,
    TRACKING_PAUSED,
    CAMERA_UNAVAILABLE,
    ERROR,
}

data class ArStatus(
    val status: ArRuntimeStatus,
    val detail: String? = null,
    val recoverable: Boolean = false,
)

enum class AppScreen { DEVICES, TRACKING, DEBUG }

data class TrackingMetrics(
    val rawRssiDbm: Int? = null,
    val rollingMedianRssiDbm: Double? = null,
    val receivedCount: Int = 0,
    val acceptedCount: Int = 0,
    val rejectedNoPoseCount: Int = 0,
    val lastSeenElapsedNanos: Long? = null,
    val pathLengthMetres: Double = 0.0,
)

data class LocatorUiState(
    val screen: AppScreen = AppScreen.DEVICES,
    val isDiscovering: Boolean = false,
    val discoverySecondsRemaining: Int = 0,
    val candidates: List<DeviceCandidate> = emptyList(),
    val selected: DeviceCandidate? = null,
    val metrics: TrackingMetrics = TrackingMetrics(),
    val arStatus: ArStatus = ArStatus(ArRuntimeStatus.INACTIVE),
    val estimate: EstimateUiModel? = null,
    val breadcrumbs: List<Pair<Double, Double>> = emptyList(),
    val guidance: String = "Select a device to begin",
    val recoverableError: String? = null,
    val lastTraceFile: File? = null,
)
