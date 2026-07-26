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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arsignallocator.BuildConfig
import dev.arsignallocator.CompleteScanStartResult
import dev.arsignallocator.LocatorViewModel
import dev.arsignallocator.ar.ArCorePoseSource
import dev.arsignallocator.model.AppScreen
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
import kotlin.math.roundToInt

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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val traceSummaries by traceStore.summaries.collectAsStateWithLifecycle()
    val historyError by traceStore.error.collectAsStateWithLifecycle()
    val runtime = rememberRuntimeUiState(arPoseSource)
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
    LaunchedEffect(runtime.ready) { viewModel.setRuntimeReady(runtime.ready) }
    LaunchedEffect(state.screen, runtime.ready) {
        arPoseSource.setActive(runtime.ready && state.screen != AppScreen.DEVICES)
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
            !runtime.onboardingComplete -> OnboardingScreen(onContinue = runtime.onContinue)
            runtime.missingPermissions.isNotEmpty() -> PermissionScreen(
                missingPermissions = runtime.missingPermissions,
                permissionWasRequested = runtime.permissionWasRequested,
                onRequest = runtime.onRequestPermissions,
                onOpenSettings = runtime.onOpenSettings,
            )
            runtime.bluetoothState != BluetoothUiState.ENABLED -> BluetoothScreen(
                unsupported = runtime.bluetoothState == BluetoothUiState.UNSUPPORTED,
                onEnable = runtime.onEnableBluetooth,
            )
            state.screen == AppScreen.DEVICES -> DevicePageContent(
                page = devicePage,
                state = state,
                viewModel = viewModel,
                traceStore = traceStore,
                billingState = billingState,
                consentState = consentState,
                variantMonetizationUi = variantMonetizationUi,
                summaries = traceSummaries,
                historyError = historyError,
                adRequestContext = adRequestContext,
                showBanner = proSheetOrigin == null,
                privacyPolicyAvailable = privacyPolicyAvailable,
                onNavigate = { devicePage = it },
                onCompleteScan = requestCompleteScan,
                onShowPro = { proSheetOrigin = ProSheetOrigin.SETTINGS },
                onExport = onExport,
                onShare = onShare,
                onSendSupport = onSendSupport,
                onPrivacyPolicy = onPrivacyPolicy,
                onPrivacyChoices = onPrivacyChoices,
            )
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

@Composable
private fun DevicePageContent(
    page: DevicePage,
    state: LocatorUiState,
    viewModel: LocatorViewModel,
    traceStore: TraceStore,
    billingState: BillingState,
    consentState: ConsentState,
    variantMonetizationUi: VariantMonetizationUi,
    summaries: List<TraceSummary>,
    historyError: String?,
    adRequestContext: AdRequestContext,
    showBanner: Boolean,
    privacyPolicyAvailable: Boolean,
    onNavigate: (DevicePage) -> Unit,
    onCompleteScan: () -> Unit,
    onShowPro: () -> Unit,
    onExport: (File) -> Unit,
    onShare: (File) -> Unit,
    onSendSupport: (TraceSummary?, File?) -> Unit,
    onPrivacyPolicy: () -> Unit,
    onPrivacyChoices: () -> Unit,
) {
    when (page) {
        DevicePage.PICKER -> DevicePickerScreen(
            state = state,
            onRefresh = viewModel::startDiscovery,
            onSelect = {
                onNavigate(DevicePage.PICKER)
                viewModel.selectDevice(it.handle.key)
            },
            onCompleteScan = onCompleteScan,
            onSettings = { onNavigate(DevicePage.SETTINGS) },
            onExport = onExport,
            onDelete = traceStore::deleteAllTraces,
            banner = {
                if (showBanner) {
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
            onBack = { onNavigate(DevicePage.PICKER) },
            onPro = onShowPro,
            onDataHistory = { onNavigate(DevicePage.DATA) },
            onPrivacyPolicy = onPrivacyPolicy,
            onPrivacyChoices = onPrivacyChoices,
            onHelp = { onNavigate(DevicePage.HELP) },
            onContactSupport = { onNavigate(DevicePage.SUPPORT) },
            onAbout = { onNavigate(DevicePage.ABOUT) },
        )
        DevicePage.DATA -> DataHistoryScreen(
            summaries = summaries,
            error = historyError,
            onBack = { onNavigate(DevicePage.SETTINGS) },
            onExport = onExport,
            onShare = onShare,
            onDelete = traceStore::deleteTrace,
            onDeleteAll = traceStore::deleteAllTraces,
        )
        DevicePage.HELP -> HelpScreen(onBack = { onNavigate(DevicePage.SETTINGS) })
        DevicePage.SUPPORT -> ContactSupportScreen(
            supportEmail = BuildConfig.SUPPORT_EMAIL,
            summaries = summaries,
            onBack = { onNavigate(DevicePage.SETTINGS) },
            onSend = onSendSupport,
        )
        DevicePage.ABOUT -> AboutScreen(
            appVersion = BuildConfig.VERSION_NAME,
            onBack = { onNavigate(DevicePage.SETTINGS) },
            onNotices = { onNavigate(DevicePage.NOTICES) },
        )
        DevicePage.NOTICES -> NoticesScreen(onBack = { onNavigate(DevicePage.ABOUT) })
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

private data class RuntimeUiState(
    val onboardingComplete: Boolean,
    val missingPermissions: List<String>,
    val permissionWasRequested: Boolean,
    val bluetoothState: BluetoothUiState,
    val onContinue: () -> Unit,
    val onRequestPermissions: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onEnableBluetooth: () -> Unit,
) {
    val ready: Boolean
        get() = onboardingComplete &&
            missingPermissions.isEmpty() &&
            bluetoothState == BluetoothUiState.ENABLED
}

@Composable
private fun rememberRuntimeUiState(arPoseSource: ArCorePoseSource): RuntimeUiState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var onboardingComplete by remember {
        mutableStateOf(preferences.getBoolean(ONBOARDING_COMPLETE, false))
    }
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
        context.registerReceiver(
            receiver,
            IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingPermissions = remember(context, permissionRefresh) {
        PermissionPolicy.requiredPermissions().filter { permission ->
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    val bluetoothState = remember(context, permissionRefresh, missingPermissions) {
        bluetoothState(context, missingPermissions.isEmpty())
    }
    return RuntimeUiState(
        onboardingComplete = onboardingComplete,
        missingPermissions = missingPermissions,
        permissionWasRequested = permissionWasRequested,
        bluetoothState = bluetoothState,
        onContinue = {
            preferences.edit().putBoolean(ONBOARDING_COMPLETE, true).apply()
            onboardingComplete = true
        },
        onRequestPermissions = {
            permissionWasRequested = true
            preferences.edit().putBoolean(PERMISSIONS_REQUESTED, true).apply()
            permissionLauncher.launch(
                PermissionPolicy.permissionsToRequest(missingPermissions).toTypedArray(),
            )
        },
        onOpenSettings = context::openAppSettings,
        onEnableBluetooth = {
            bluetoothLauncher.launch(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),
            )
        },
    )
}

private enum class BluetoothUiState { ENABLED, DISABLED, UNSUPPORTED, PERMISSION_MISSING }

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

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private const val PREFERENCES_NAME = "ui_shell"
private const val ONBOARDING_COMPLETE = "onboarding_complete"
private const val PERMISSIONS_REQUESTED = "permissions_requested"
