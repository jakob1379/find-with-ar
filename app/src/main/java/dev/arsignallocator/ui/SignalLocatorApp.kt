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
