@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arsignallocator.ui

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arsignallocator.BuildConfig
import dev.arsignallocator.CompleteScanStartResult
import dev.arsignallocator.LocatorViewModel
import dev.arsignallocator.ar.ArProjectionSnapshot
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.model.AppScreen
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.monetization.AdRequestContext
import dev.arsignallocator.monetization.AdsEligibility
import dev.arsignallocator.monetization.BannerSurface
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.CompleteScanRoute
import dev.arsignallocator.monetization.ConsentState
import dev.arsignallocator.monetization.completeScanRoute
import dev.arsignallocator.permissions.PermissionPolicy
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceSummary
import dev.arsignallocator.trace.TraceStore
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun SignalLocatorApp(
    viewModel: LocatorViewModel,
    traceStore: TraceStore,
    arPoseSource: ArCorePoseSource,
    billingState: BillingState,
    consentState: ConsentState,
    variantMonetizationUi: VariantMonetizationUi,
    onExport: (File) -> Unit,
    onShare: (File) -> Unit,
    onSendSupport: (TraceSummary?, File?) -> Unit,
    onPurchasePro: () -> Unit = {},
    onRestorePro: () -> Unit = {},
    privacyPolicyAvailable: Boolean = false,
    onPrivacyPolicy: () -> Unit = {},
    onPrivacyChoices: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val traceSummaries by traceStore.summaries.collectAsStateWithLifecycle()
    val historyError by traceStore.error.collectAsStateWithLifecycle()
    val preferences = remember(context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var onboardingComplete by remember {
        mutableStateOf(preferences.getBoolean(ONBOARDING_COMPLETE, false))
    }
    var devicePage by remember { mutableStateOf(DevicePage.PICKER) }
    var exitSurveyQuestion by remember { mutableStateOf<String?>(null) }
    val onTrackingExit: (TrackingExit) -> Unit = { exit ->
        val outcome = trackingExitOutcome(exit)
        if (outcome == null) {
            exitSurveyQuestion = surveyQuestionFor(state.screen)
        } else {
            viewModel.completeTrace(outcome)
        }
    }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var permissionWasRequested by remember {
        mutableStateOf(preferences.getBoolean(PERMISSIONS_REQUESTED, false))
    }
    var proSheetOrigin by rememberSaveable { mutableStateOf<ProSheetOrigin?>(null) }
    val overlayVisible = proSheetOrigin != null || exitSurveyQuestion != null
    val adRequestContext = AdRequestContext(
        eligibility = AdsEligibility(
            adsEnabled = BuildConfig.ADS_ENABLED,
            isDebugBuild = BuildConfig.DEBUG,
            debugTestAdsEnabled = variantMonetizationUi.allowAds,
            entitlement = billingState.entitlement,
            billingAuthoritative = billingState.isAuthoritative,
        ),
        screenAllowsAds = currentScreenAllowsAds(
            screen = state.screen,
            devicePage = devicePage,
            overlayVisible = overlayVisible,
        ),
        consentAllowsAds = consentState.consentAllowsAds,
        adsSdkReady = consentState.adsSdkReady,
    )
    val requestCompleteScan = {
        when (completeScanRoute(billingState.entitlement)) {
            CompleteScanRoute.START -> {
                if (viewModel.startCompleteScan() == CompleteScanStartResult.REQUIRES_PRO) {
                    proSheetOrigin = ProSheetOrigin.COMPLETE_SCAN
                }
            }
            CompleteScanRoute.SHOW_PRO -> proSheetOrigin = ProSheetOrigin.COMPLETE_SCAN
        }
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
        if (state.screen == AppScreen.DEVICES) exitSurveyQuestion = null
    }
    BackHandler(enabled = state.screen == AppScreen.DEVICES && devicePage != DevicePage.PICKER) {
        devicePage = when (devicePage) {
            DevicePage.PICKER -> DevicePage.PICKER
            DevicePage.SETTINGS -> DevicePage.PICKER
            DevicePage.DATA, DevicePage.HELP, DevicePage.SUPPORT, DevicePage.ABOUT ->
                DevicePage.SETTINGS
            DevicePage.NOTICES -> DevicePage.ABOUT
        }
    }
    LaunchedEffect(proSheetOrigin, billingState.entitlement) {
        if (
            proSheetOrigin == ProSheetOrigin.COMPLETE_SCAN &&
            completeScanRoute(billingState.entitlement) == CompleteScanRoute.START
        ) {
            if (viewModel.startCompleteScan() != CompleteScanStartResult.REQUIRES_PRO) {
                proSheetOrigin = null
            }
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
                    onCompleteScan = requestCompleteScan,
                    onSettings = { devicePage = DevicePage.SETTINGS },
                    onExport = onExport,
                    onDelete = traceStore::deleteAllTraces,
                    banner = {
                        if (proSheetOrigin == null) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                ManagedBannerSlot(
                                    surface = BannerSurface.PICKER,
                                    widthDp = maxWidth.value.roundToInt(),
                                    requestContext = adRequestContext,
                                )
                            }
                        }
                    },
                )
                DevicePage.SETTINGS -> SettingsScreen(
                    billingState = billingState,
                    consentState = consentState,
                    variantMonetizationUi = variantMonetizationUi,
                    privacyPolicyAvailable = privacyPolicyAvailable,
                    onBack = { devicePage = DevicePage.PICKER },
                    onPro = { proSheetOrigin = ProSheetOrigin.SETTINGS },
                    onDataHistory = { devicePage = DevicePage.DATA },
                    onPrivacyPolicy = onPrivacyPolicy,
                    onPrivacyChoices = onPrivacyChoices,
                    onHelp = { devicePage = DevicePage.HELP },
                    onContactSupport = { devicePage = DevicePage.SUPPORT },
                    onAbout = { devicePage = DevicePage.ABOUT },
                )
                DevicePage.DATA -> DataHistoryScreen(
                    summaries = traceSummaries,
                    error = historyError,
                    onBack = { devicePage = DevicePage.SETTINGS },
                    onExport = onExport,
                    onShare = onShare,
                    onDelete = traceStore::deleteTrace,
                    onDeleteAll = traceStore::deleteAllTraces,
                )
                DevicePage.HELP -> HelpScreen(onBack = { devicePage = DevicePage.SETTINGS })
                DevicePage.SUPPORT -> ContactSupportScreen(
                    supportEmail = BuildConfig.SUPPORT_EMAIL,
                    summaries = traceSummaries,
                    onBack = { devicePage = DevicePage.SETTINGS },
                    onSend = onSendSupport,
                )
                DevicePage.ABOUT -> AboutScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    onBack = { devicePage = DevicePage.SETTINGS },
                    onNotices = { devicePage = DevicePage.NOTICES },
                )
                DevicePage.NOTICES -> NoticesScreen(onBack = { devicePage = DevicePage.ABOUT })
            }
            state.screen == AppScreen.COMPLETE_SCAN -> CompleteScanScreen(
                state = state,
                arPoseSource = arPoseSource,
                onFocus = viewModel::focusCompleteScanDevice,
                onFinishScan = {
                    exitSurveyQuestion = surveyQuestionFor(AppScreen.COMPLETE_SCAN)
                },
            )
            else -> TrackingScreen(
                state = state,
                arPoseSource = arPoseSource,
                onLeaveSession = { onTrackingExit(TrackingExit.DEVICES) },
                onFoundTarget = { onTrackingExit(TrackingExit.FOUND_TARGET) },
                onToggleVariantPanel = {
                    if (state.screen == AppScreen.DEBUG) viewModel.showTracking() else viewModel.showDebug()
                },
                onRetryAr = arPoseSource::retry,
                onExport = onExport,
                adRequestContext = adRequestContext,
            )
        }
        if (proSheetOrigin != null) {
            ProPurchaseSheet(
                billingState = billingState,
                onPurchase = onPurchasePro,
                onRestore = onRestorePro,
                onDismiss = { proSheetOrigin = null },
            )
        }
        exitSurveyQuestion?.let { question ->
            EndSessionSurvey(
                question = question,
                onSubmit = { answer ->
                    exitSurveyQuestion = null
                    viewModel.completeTrace(answer.outcome, answer.reason, answer.note)
                },
                onDismiss = { exitSurveyQuestion = null },
            )
        }
    }
}

internal enum class TrackingExit { FOUND_TARGET, DEVICES }

internal fun trackingExitOutcome(exit: TrackingExit): TraceOutcome? = when (exit) {
    TrackingExit.FOUND_TARGET -> TraceOutcome.YES
    TrackingExit.DEVICES -> null
}

internal fun surveyQuestionFor(screen: AppScreen): String? = when (screen) {
    AppScreen.TRACKING, AppScreen.DEBUG -> SINGLE_TARGET_SURVEY_QUESTION
    AppScreen.COMPLETE_SCAN -> COMPLETE_SCAN_SURVEY_QUESTION
    AppScreen.DEVICES -> null
}

internal fun currentScreenAllowsAds(
    screen: AppScreen,
    devicePage: DevicePage,
    overlayVisible: Boolean,
): Boolean = !overlayVisible && when (screen) {
    AppScreen.DEVICES -> devicePage == DevicePage.PICKER
    AppScreen.TRACKING, AppScreen.DEBUG -> true
    AppScreen.COMPLETE_SCAN -> false
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
    onLeaveSession: () -> Unit,
    onFoundTarget: () -> Unit,
    onToggleVariantPanel: () -> Unit,
    onRetryAr: () -> Unit,
    onExport: (File) -> Unit,
    adRequestContext: AdRequestContext,
) {
    val projection by arPoseSource.projection.collectAsStateWithLifecycle()
    var visualizations by remember { mutableStateOf(TrackingVisualizations()) }
    val lastSeen = state.metrics.lastSeenElapsedNanos ?: state.selected?.lastSeenElapsedNanos
    var nowElapsedNanos by remember(state.selected?.key, lastSeen) {
        mutableLongStateOf(SystemClock.elapsedRealtimeNanos())
    }
    val variantPanelOpen = variantTrackingPanelAvailable && state.screen == AppScreen.DEBUG
    val policy = trackingUiPolicy(state, nowElapsedNanos)
    val displayedEstimate = rememberVisibleEstimate(
        targetKey = state.selected?.key,
        current = state.estimate,
        frozen = policy.freezesEstimate,
    )
    val statusContent = trackingStatusContent(policy, displayedEstimate != null)
    val automaticVisualization = selectEstimateVisualization(displayedEstimate)
    val showHeatmap = if (variantTrackingPanelAvailable) {
        visualizations.arHeatmap
    } else {
        automaticVisualization != EstimateVisualization.HIDDEN
    }
    val showEllipse = if (variantTrackingPanelAvailable) {
        visualizations.arEllipse
    } else {
        automaticVisualization != EstimateVisualization.HIDDEN
    }
    val showPoint = if (variantTrackingPanelAvailable) {
        visualizations.arPoint
    } else {
        automaticVisualization == EstimateVisualization.POINT
    }
    val showMap = if (variantTrackingPanelAvailable) {
        visualizations.topDownMap
    } else {
        automaticVisualization != EstimateVisualization.HIDDEN
    }
    val showBreadcrumbs = if (variantTrackingPanelAvailable) {
        visualizations.breadcrumbPath
    } else {
        true
    }
    LaunchedEffect(state.selected?.key, lastSeen) {
        while (true) {
            nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
            delay(SIGNAL_FRESHNESS_TICK_MILLIS)
        }
    }
    BackHandler(onBack = onLeaveSession)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val layout = selectTrackingLayout(maxWidth.value, maxHeight.value)
        val bannerWidthDp = maxWidth.value.roundToInt()
        val bottomControlsMaxHeight =
            maxHeight * if (layout == TrackingLayout.PORTRAIT_BOTTOM) 0.44f else 0.52f
        val camera: @Composable (Modifier) -> Unit = { modifier ->
            TrackingCamera(
                state = state,
                projection = projection,
                arPoseSource = arPoseSource,
                estimate = displayedEstimate,
                visualization = automaticVisualization,
                showHeatmap = showHeatmap,
                showEllipse = showEllipse,
                showPoint = showPoint,
                showMap = showMap,
                showBreadcrumbs = showBreadcrumbs,
                statusContent = statusContent,
                onRetryAr = onRetryAr,
                modifier = modifier,
            )
        }
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            TrackingControls(
                state = state,
                policy = policy,
                statusContent = statusContent,
                variantPanelOpen = variantPanelOpen,
                visualizations = visualizations,
                onVisualizationsChange = { visualizations = it },
                onLeaveSession = onLeaveSession,
                onFoundTarget = onFoundTarget,
                onToggleVariantPanel = onToggleVariantPanel,
                onExport = onExport,
                modifier = modifier,
            )
        }

        when (layout) {
            TrackingLayout.LANDSCAPE_SIDE_RAIL -> Row(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(320.dp).fillMaxHeight()) {
                    controls(Modifier.weight(1f).fillMaxWidth())
                    ManagedBannerSlot(
                        surface = BannerSurface.TRACKING_RAIL,
                        widthDp = 320,
                        requestContext = adRequestContext,
                    )
                }
            }
            TrackingLayout.PORTRAIT_BOTTOM,
            TrackingLayout.LANDSCAPE_BOTTOM,
            -> Column(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxWidth())
                controls(
                    Modifier.fillMaxWidth().heightIn(
                        max = bottomControlsMaxHeight,
                    ),
                )
                ManagedBannerSlot(
                    surface = if (layout == TrackingLayout.PORTRAIT_BOTTOM) {
                        BannerSurface.TRACKING_PORTRAIT
                    } else {
                        BannerSurface.TRACKING_NARROW_LANDSCAPE
                    },
                    widthDp = bannerWidthDp,
                    requestContext = adRequestContext,
                )
            }
        }
    }
}

@Composable
internal fun rememberVisibleEstimate(
    targetKey: DeviceKey?,
    current: EstimateUiModel?,
    frozen: Boolean,
): EstimateUiModel? {
    val remembered = remember(targetKey) { RememberedEstimate(current) }
    return remembered.update(current, frozen)
}

@Composable
internal fun TrackingCamera(
    state: LocatorUiState,
    projection: ArProjectionSnapshot?,
    arPoseSource: ArCorePoseSource,
    estimate: EstimateUiModel?,
    visualization: EstimateVisualization,
    showHeatmap: Boolean,
    showEllipse: Boolean,
    showPoint: Boolean,
    showMap: Boolean,
    showBreadcrumbs: Boolean,
    statusContent: TrackingStatusContent,
    onRetryAr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black)) {
        if (!LocalInspectionMode.current) {
            AndroidView(
                factory = { arPoseSource.surfaceView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        ArFloorOverlay(
            projection = projection,
            estimate = estimate,
            showHeatmap = showHeatmap,
            showEllipse = showEllipse,
            showPoint = showPoint,
            modifier = Modifier.fillMaxSize(),
        )
        if (showMap || showBreadcrumbs) {
            TopDownMap(
                breadcrumbs = state.breadcrumbs,
                estimates = if (showMap && estimate != null) {
                    listOf(MapEstimateItem(state.selected?.key, estimate, showPoint))
                } else {
                    emptyList()
                },
                projection = projection,
                showBreadcrumbs = showBreadcrumbs,
                talkBackSummary = estimateTalkBackSummary(statusContent, visualization),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(176.dp),
            )
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
                    Text(statusContent.instruction)
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

internal data class MapEstimateItem(
    val key: DeviceKey?,
    val estimate: EstimateUiModel,
    val showPoint: Boolean,
    val regionColor: Color = Color(0xFFFFB74D),
    val markerColor: Color = Color.Yellow,
)

@Composable
internal fun TopDownMap(
    breadcrumbs: List<Pair<Double, Double>>,
    estimates: List<MapEstimateItem>,
    projection: ArProjectionSnapshot?,
    showBreadcrumbs: Boolean,
    talkBackSummary: String,
    focusedKey: DeviceKey? = null,
    modifier: Modifier = Modifier,
) {
    val visibleBreadcrumbs = breadcrumbs.takeIf { showBreadcrumbs }.orEmpty()
    val points = buildList {
        addAll(visibleBreadcrumbs)
        projection?.cameraPose?.let { add(it.xMetres to it.zMetres) }
        estimates.forEach { item ->
            item.estimate.probabilityCells.forEach { add(it.xMetres to it.zMetres) }
            if (item.estimate.meanXMetres != null && item.estimate.meanZMetres != null) {
                add(item.estimate.meanXMetres to item.estimate.meanZMetres)
            }
        }
    }
    Surface(
        modifier.semantics { contentDescription = talkBackSummary },
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.70f),
    ) {
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

            estimates.forEach { item ->
                val focusAlpha = if (focusedKey == null || focusedKey == item.key) 1f else 0.38f
                val maxProbability =
                    item.estimate.probabilityCells.maxOfOrNull { it.probability } ?: 1.0
                item.estimate.probabilityCells.forEach { cell ->
                    val cellSize = (cell.resolutionMetres * scale).toFloat().coerceAtLeast(1f)
                    val location = project(cell.xMetres, cell.zMetres)
                    val alpha =
                        ((0.12 + 0.68 * cell.probability / maxProbability) * focusAlpha).toFloat()
                    drawRect(
                        color = item.regionColor.copy(alpha = alpha),
                        topLeft = Offset(location.x - cellSize / 2, location.y - cellSize / 2),
                        size = Size(cellSize, cellSize),
                    )
                }
            }
            visibleBreadcrumbs.zipWithNext().forEach { (from, to) ->
                drawLine(Color(0xFF80CBC4), project(from.first, from.second), project(to.first, to.second), 2.dp.toPx())
            }
            visibleBreadcrumbs.forEach {
                drawCircle(Color(0xFFB2DFDB), 2.5.dp.toPx(), project(it.first, it.second))
            }

            estimates.forEach { item ->
                val estimate = item.estimate
                val focusAlpha = if (focusedKey == null || focusedKey == item.key) 1f else 0.38f
                val meanX = estimate.meanXMetres
                val meanZ = estimate.meanZMetres
                val xx = estimate.covarianceXX
                val xz = estimate.covarianceXZ
                val zz = estimate.covarianceZZ
                if (meanX != null && meanZ != null && xx != null && xz != null && zz != null) {
                    val trace = xx + zz
                    val difference = xx - zz
                    val root = sqrt(max(0.0, difference * difference + 4.0 * xz * xz))
                    val major = max(0.0, (trace + root) / 2.0)
                    val minor = max(0.0, (trace - root) / 2.0)
                    val majorRadius = (sqrt(major * CHI_SQUARED_90) * scale).toFloat()
                    val minorRadius = (sqrt(minor * CHI_SQUARED_90) * scale).toFloat()
                    val angleDegrees =
                        (0.5 * atan2(2.0 * xz, difference) * 180.0 / PI).toFloat()
                    val estimateCenter = project(meanX, meanZ)
                    rotate(angleDegrees, pivot = estimateCenter) {
                        drawOval(
                            color = item.markerColor.copy(alpha = 0.40f * focusAlpha),
                            topLeft = Offset(
                                estimateCenter.x - majorRadius,
                                estimateCenter.y - minorRadius,
                            ),
                            size = Size(majorRadius * 2, minorRadius * 2),
                        )
                    }
                }
                if (item.showPoint && meanX != null && meanZ != null) {
                    drawCircle(
                        item.markerColor.copy(alpha = focusAlpha),
                        4.dp.toPx(),
                        project(meanX, meanZ),
                    )
                }
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
private const val SIGNAL_FRESHNESS_TICK_MILLIS = 500L
