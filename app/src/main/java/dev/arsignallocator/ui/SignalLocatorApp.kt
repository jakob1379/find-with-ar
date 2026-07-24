@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arsignallocator.ui

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arsignallocator.BuildConfig
import dev.arsignallocator.LocatorViewModel
import dev.arsignallocator.ar.ArProjectionSnapshot
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.model.AppScreen
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.permissions.PermissionPolicy
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun SignalLocatorApp(
    viewModel: LocatorViewModel,
    arPoseSource: ArCorePoseSource,
    traceDirectory: File,
    onExport: (File) -> Unit,
    onCompleteScan: () -> Unit = {},
    onPro: () -> Unit = {},
    onDataHistory: () -> Unit = {},
    onPrivacyChoices: () -> Unit = {},
    onContactSupport: () -> Unit = {},
    onNotices: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences = remember(context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var onboardingComplete by remember {
        mutableStateOf(preferences.getBoolean(ONBOARDING_COMPLETE, false))
    }
    var devicePage by remember { mutableStateOf(DevicePage.PICKER) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var permissionWasRequested by remember {
        mutableStateOf(preferences.getBoolean(PERMISSIONS_REQUESTED, false))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefresh += 1
        arPoseSource.retry()
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionRefresh += 1
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                permissionRefresh += 1
            }
        }
        context.registerReceiver(receiver, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingPermissions = remember(permissionRefresh) {
        PermissionPolicy.requiredPermissions().filter { permission ->
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    val bluetoothState = remember(permissionRefresh, missingPermissions) {
        bluetoothState(context, missingPermissions.isEmpty())
    }
    val runtimeReady = onboardingComplete &&
        missingPermissions.isEmpty() &&
        bluetoothState == BluetoothUiState.ENABLED

    LaunchedEffect(runtimeReady) { viewModel.setRuntimeReady(runtimeReady) }
    LaunchedEffect(state.screen, runtimeReady) {
        arPoseSource.setActive(runtimeReady && state.screen != AppScreen.DEVICES)
    }
    BackHandler(enabled = state.screen == AppScreen.DEVICES && devicePage != DevicePage.PICKER) {
        devicePage = when (devicePage) {
            DevicePage.PICKER -> DevicePage.PICKER
            DevicePage.SETTINGS -> DevicePage.PICKER
            DevicePage.HELP, DevicePage.ABOUT -> DevicePage.SETTINGS
        }
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        when {
            !onboardingComplete -> OnboardingScreen(
                onContinue = {
                    preferences.edit().putBoolean(ONBOARDING_COMPLETE, true).apply()
                    onboardingComplete = true
                },
            )
            missingPermissions.isNotEmpty() -> PermissionScreen(
                missingPermissions = missingPermissions,
                permissionWasRequested = permissionWasRequested,
                onRequest = {
                    permissionWasRequested = true
                    preferences.edit().putBoolean(PERMISSIONS_REQUESTED, true).apply()
                    permissionLauncher.launch(
                        PermissionPolicy.permissionsToRequest(missingPermissions).toTypedArray(),
                    )
                },
                onOpenSettings = { context.openAppSettings() },
            )
            bluetoothState != BluetoothUiState.ENABLED -> BluetoothScreen(
                unsupported = bluetoothState == BluetoothUiState.UNSUPPORTED,
                onEnable = {
                    bluetoothLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                },
            )
            state.screen == AppScreen.DEVICES -> when (devicePage) {
                DevicePage.PICKER -> DevicePickerScreen(
                    state = state,
                    onRefresh = viewModel::startDiscovery,
                    onSelect = {
                        devicePage = DevicePage.PICKER
                        viewModel.selectDevice(it.handle.key)
                    },
                    onCompleteScan = onCompleteScan,
                    onSettings = { devicePage = DevicePage.SETTINGS },
                    onExport = onExport,
                    onDelete = { viewModel.deleteLocalTraces(traceDirectory) },
                )
                DevicePage.SETTINGS -> SettingsScreen(
                    onBack = { devicePage = DevicePage.PICKER },
                    onPro = onPro,
                    onDataHistory = onDataHistory,
                    onPrivacyChoices = onPrivacyChoices,
                    onHelp = { devicePage = DevicePage.HELP },
                    onContactSupport = onContactSupport,
                    onAbout = { devicePage = DevicePage.ABOUT },
                )
                DevicePage.HELP -> HelpScreen(onBack = { devicePage = DevicePage.SETTINGS })
                DevicePage.ABOUT -> AboutScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    onBack = { devicePage = DevicePage.SETTINGS },
                    onNotices = onNotices,
                )
            }
            else -> TrackingScreen(
                state = state,
                arPoseSource = arPoseSource,
                onBack = viewModel::stopAndReturnToDevices,
                onToggleVariantPanel = {
                    if (state.screen == AppScreen.DEBUG) viewModel.showTracking() else viewModel.showDebug()
                },
                onRetryAr = arPoseSource::retry,
                onExport = onExport,
            )
        }
    }
}

private enum class BluetoothUiState { ENABLED, DISABLED, UNSUPPORTED, PERMISSION_MISSING }

internal data class TrackingVisualizations(
    val arHeatmap: Boolean = true,
    val arEllipse: Boolean = true,
    val arPoint: Boolean = true,
    val topDownMap: Boolean = true,
    val breadcrumbPath: Boolean = true,
)

private fun bluetoothState(context: Context, permissionsGranted: Boolean): BluetoothUiState {
    if (!permissionsGranted) return BluetoothUiState.PERMISSION_MISSING
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BluetoothUiState.UNSUPPORTED
        if (adapter.isEnabled) BluetoothUiState.ENABLED else BluetoothUiState.DISABLED
    } catch (_: SecurityException) {
        BluetoothUiState.PERMISSION_MISSING
    }
}

@Composable
private fun TrackingScreen(
    state: LocatorUiState,
    arPoseSource: ArCorePoseSource,
    onBack: () -> Unit,
    onToggleVariantPanel: () -> Unit,
    onRetryAr: () -> Unit,
    onExport: (File) -> Unit,
) {
    val projection by arPoseSource.projection.collectAsStateWithLifecycle()
    var visualizations by remember { mutableStateOf(TrackingVisualizations()) }
    val variantPanelOpen = variantTrackingPanelAvailable && state.screen == AppScreen.DEBUG
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val panelModifier = if (landscape) {
            Modifier.align(Alignment.CenterStart)
                .padding(top = 64.dp)
                .width(minOf(maxWidth * 0.36f, 280.dp))
                .fillMaxHeight()
        } else {
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        }

        if (!LocalInspectionMode.current) {
            AndroidView(
                factory = { arPoseSource.surfaceView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        ArFloorOverlay(
            projection = projection,
            estimate = state.estimate,
            showHeatmap = visualizations.arHeatmap,
            showEllipse = visualizations.arEllipse,
            showPoint = visualizations.arPoint,
            modifier = Modifier.fillMaxSize(),
        )
        val showMap = visualizations.topDownMap
        val showBreadcrumbs = visualizations.breadcrumbPath
        if (showMap || showBreadcrumbs) {
            TopDownMap(
                breadcrumbs = state.breadcrumbs,
                estimate = state.estimate,
                projection = projection,
                showEstimate = showMap,
                showBreadcrumbs = showBreadcrumbs,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp).size(176.dp),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f)).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Devices", color = Color.White) }
            Text(state.selected?.displayName.orEmpty(), color = Color.White)
            if (variantTrackingPanelAvailable) {
                VariantTrackingAction(
                    open = variantPanelOpen,
                    onToggle = onToggleVariantPanel,
                )
            } else {
                Spacer(Modifier.width(80.dp))
            }
        }

        if (variantPanelOpen) {
            VariantTrackingPanel(
                state,
                visualizations,
                onVisualizationsChange = { visualizations = it },
                onExport = onExport,
                modifier = if (landscape) panelModifier else panelModifier.fillMaxHeight(0.48f),
            )
        } else {
            TrackingStatusPanel(state, panelModifier)
        }

        if (state.arStatus.status in setOf(
                ArRuntimeStatus.ERROR,
                ArRuntimeStatus.CAMERA_UNAVAILABLE,
                ArRuntimeStatus.INSTALL_REQUESTED,
                ArRuntimeStatus.UNSUPPORTED,
            )
        ) {
            Card(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(state.arStatus.detail ?: "AR is unavailable")
                    if (state.arStatus.recoverable) {
                        TextButton(onClick = onRetryAr) { Text("Retry AR") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArFloorOverlay(
    projection: ArProjectionSnapshot?,
    estimate: EstimateUiModel?,
    showHeatmap: Boolean,
    showEllipse: Boolean,
    showPoint: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshot = projection ?: return
    val currentEstimate = estimate ?: return
    if (!showHeatmap && !showEllipse && !showPoint) return
    Canvas(modifier) {
        fun project(x: Double, z: Double): Offset? = snapshot.project(x, 0.0, z)?.let { point ->
            Offset(point.x * size.width, point.y * size.height)
        }

        if (showHeatmap) {
            val heatCells = currentEstimate.probabilityCells
                .sortedByDescending { it.probability }
                .take(AR_HEAT_CELL_LIMIT)
            val maxProbability = heatCells.maxOfOrNull { it.probability } ?: 0.0
            if (maxProbability > 0.0) {
                heatCells.forEach { cell ->
                    project(cell.xMetres, cell.zMetres)?.let { location ->
                        val weight = (cell.probability / maxProbability).coerceIn(0.0, 1.0)
                        drawCircle(
                            color = Color(0xFFFFB74D).copy(alpha = (0.10 + weight * 0.50).toFloat()),
                            radius = (5.0 + weight * 10.0).dp.toPx().coerceAtMost(18.dp.toPx()),
                            center = location,
                        )
                    }
                }
            }
        }

        val meanX = currentEstimate.meanXMetres
        val meanZ = currentEstimate.meanZMetres
        val xx = currentEstimate.covarianceXX
        val xz = currentEstimate.covarianceXZ
        val zz = currentEstimate.covarianceZZ
        if (showEllipse && meanX != null && meanZ != null && xx != null && xz != null && zz != null) {
            val trace = xx + zz
            val difference = xx - zz
            val root = sqrt(max(0.0, difference * difference + 4.0 * xz * xz))
            val majorRadius = sqrt(max(0.0, (trace + root) / 2.0) * CHI_SQUARED_90)
            val minorRadius = sqrt(max(0.0, (trace - root) / 2.0) * CHI_SQUARED_90)
            val angle = 0.5 * atan2(2.0 * xz, difference)
            val cosAngle = cos(angle)
            val sinAngle = sin(angle)
            val ellipse = (0 until AR_ELLIPSE_SEGMENTS).mapNotNull { index ->
                val theta = 2.0 * PI * index / AR_ELLIPSE_SEGMENTS
                val localX = majorRadius * cos(theta)
                val localZ = minorRadius * sin(theta)
                project(
                    meanX + cosAngle * localX - sinAngle * localZ,
                    meanZ + sinAngle * localX + cosAngle * localZ,
                )
            }
            if (ellipse.size >= AR_ELLIPSE_SEGMENTS * 3 / 4) {
                val path = Path().apply {
                    moveTo(ellipse.first().x, ellipse.first().y)
                    ellipse.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Color(0x33FFD166))
                drawPath(path, Color(0xFFFFD166), style = Stroke(width = 3.dp.toPx()))
            }
        }
        if (showPoint && meanX != null && meanZ != null) {
            project(meanX, meanZ)?.let { location ->
                drawCircle(Color(0xFFFFD166), 11.dp.toPx(), location)
                drawCircle(Color.White, 11.dp.toPx(), location, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun TrackingStatusPanel(state: LocatorUiState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(state.guidance, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            val signal = (((state.metrics.rollingMedianRssiDbm ?: -100.0) + 100.0) / 70.0)
                .coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(
                progress = { signal },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("colder", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                Text("warmer", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TopDownMap(
    breadcrumbs: List<Pair<Double, Double>>,
    estimate: EstimateUiModel?,
    projection: ArProjectionSnapshot?,
    showEstimate: Boolean,
    showBreadcrumbs: Boolean,
    modifier: Modifier = Modifier,
) {
    val visibleBreadcrumbs = breadcrumbs.takeIf { showBreadcrumbs }.orEmpty()
    val visibleEstimate = estimate.takeIf { showEstimate }
    val points = buildList {
        addAll(visibleBreadcrumbs)
        projection?.cameraPose?.let { add(it.xMetres to it.zMetres) }
        visibleEstimate?.probabilityCells?.forEach { add(it.xMetres to it.zMetres) }
        if (visibleEstimate?.meanXMetres != null && visibleEstimate.meanZMetres != null) {
            add(visibleEstimate.meanXMetres to visibleEstimate.meanZMetres)
        }
    }
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.70f)) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            if (points.isEmpty()) {
                drawCircle(Color.White.copy(alpha = 0.8f), radius = 4.dp.toPx(), center = center)
                return@Canvas
            }
            val minX = points.minOf { it.first } - 0.75
            val maxX = points.maxOf { it.first } + 0.75
            val minZ = points.minOf { it.second } - 0.75
            val maxZ = points.maxOf { it.second } + 0.75
            val scale = min(size.width / (maxX - minX).toFloat(), size.height / (maxZ - minZ).toFloat())
            fun project(x: Double, z: Double): Offset =
                topDownMapOffset(x, z, minX, minZ, scale)

            val maxProbability = visibleEstimate?.probabilityCells?.maxOfOrNull { it.probability } ?: 1.0
            visibleEstimate?.probabilityCells?.forEach { cell ->
                val cellSize = (cell.resolutionMetres * scale).toFloat().coerceAtLeast(1f)
                val location = project(cell.xMetres, cell.zMetres)
                val alpha = (0.12 + 0.68 * cell.probability / maxProbability).toFloat()
                drawRect(
                    color = Color(0xFFFFB74D).copy(alpha = alpha),
                    topLeft = Offset(location.x - cellSize / 2, location.y - cellSize / 2),
                    size = Size(cellSize, cellSize),
                )
            }
            visibleBreadcrumbs.zipWithNext().forEach { (from, to) ->
                drawLine(Color(0xFF80CBC4), project(from.first, from.second), project(to.first, to.second), 2.dp.toPx())
            }
            visibleBreadcrumbs.forEach {
                drawCircle(Color(0xFFB2DFDB), 2.5.dp.toPx(), project(it.first, it.second))
            }

            val meanX = visibleEstimate?.meanXMetres
            val meanZ = visibleEstimate?.meanZMetres
            val xx = visibleEstimate?.covarianceXX
            val xz = visibleEstimate?.covarianceXZ
            val zz = visibleEstimate?.covarianceZZ
            if (meanX != null && meanZ != null && xx != null && xz != null && zz != null) {
                val trace = xx + zz
                val difference = xx - zz
                val root = sqrt(max(0.0, difference * difference + 4.0 * xz * xz))
                val major = max(0.0, (trace + root) / 2.0)
                val minor = max(0.0, (trace - root) / 2.0)
                val chi90 = 4.605170186
                val majorRadius = (sqrt(major * chi90) * scale).toFloat()
                val minorRadius = (sqrt(minor * chi90) * scale).toFloat()
                val angleDegrees = (0.5 * atan2(2.0 * xz, difference) * 180.0 / PI).toFloat()
                val estimateCenter = project(meanX, meanZ)
                rotate(angleDegrees, pivot = estimateCenter) {
                    drawOval(
                        color = Color(0x66FFEB3B),
                        topLeft = Offset(estimateCenter.x - majorRadius, estimateCenter.y - minorRadius),
                        size = Size(majorRadius * 2, minorRadius * 2),
                    )
                }
            }
            if (meanX != null && meanZ != null) {
                drawCircle(Color.Yellow, 4.dp.toPx(), project(meanX, meanZ))
            }
            val cameraPose = projection?.cameraPose
            val userCenter = project(
                cameraPose?.xMetres ?: visibleBreadcrumbs.lastOrNull()?.first ?: 0.0,
                cameraPose?.zMetres ?: visibleBreadcrumbs.lastOrNull()?.second ?: 0.0,
            )
            val heading = projection?.cameraHeadingDegrees
            if (heading == null) {
                drawCircle(Color.White, 4.dp.toPx(), userCenter)
            } else {
                val arrow = Path().apply {
                    moveTo(userCenter.x, userCenter.y - 10.dp.toPx())
                    lineTo(userCenter.x - 7.dp.toPx(), userCenter.y + 7.dp.toPx())
                    lineTo(userCenter.x, userCenter.y + 4.dp.toPx())
                    lineTo(userCenter.x + 7.dp.toPx(), userCenter.y + 7.dp.toPx())
                    close()
                }
                rotate(heading, pivot = userCenter) {
                    drawPath(arrow, Color(0xFF4285F4))
                    drawPath(arrow, Color.White, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

internal fun topDownMapOffset(
    x: Double,
    z: Double,
    minX: Double,
    minZ: Double,
    scale: Float,
): Offset = Offset(
    x = ((x - minX) * scale).toFloat(),
    y = ((z - minZ) * scale).toFloat(),
)

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private const val AR_HEAT_CELL_LIMIT = 160
private const val AR_ELLIPSE_SEGMENTS = 48
private const val CHI_SQUARED_90 = 4.605170186
private const val PREFERENCES_NAME = "ui_shell"
private const val ONBOARDING_COMPLETE = "onboarding_complete"
private const val PERMISSIONS_REQUESTED = "permissions_requested"
