@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arsignallocator.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import dev.arsignallocator.monetization.AdRequestContext
import dev.arsignallocator.monetization.BannerAdLoader
import dev.arsignallocator.monetization.BannerFormat
import dev.arsignallocator.monetization.BannerSurface
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.ConsentState
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.ManagedBannerLoader
import dev.arsignallocator.monetization.PrivacyOptionsRequirement
import dev.arsignallocator.monetization.bannerFormat

internal enum class ProSheetOrigin {
    COMPLETE_SCAN,
    SETTINGS,
}

@Composable
internal fun ProPurchaseSheet(
    billingState: BillingState,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pro — no ads",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(proEntitlementStatus(billingState))
            billingState.localizedPrice?.let {
                Text("One-time purchase · $it", style = MaterialTheme.typography.titleMedium)
            }
            billingState.retryableError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (billingState.entitlement !in PRO_ENTITLEMENTS) {
                val purchaseAvailable =
                    billingState.entitlement == EntitlementState.FREE &&
                        billingState.isAuthoritative &&
                        billingState.localizedPrice != null
                Button(
                    onClick = if (purchaseAvailable) onPurchase else onRestore,
                    enabled = !billingState.purchaseInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            billingState.purchaseInProgress -> "Opening Google Play…"
                            billingState.entitlement == EntitlementState.PENDING ->
                                "Check purchase status"
                            purchaseAvailable && billingState.retryableError != null ->
                                "Retry purchase for ${billingState.localizedPrice}"
                            purchaseAvailable -> "Buy Pro for ${billingState.localizedPrice}"
                            else -> "Retry Google Play"
                        },
                    )
                }
                if (purchaseAvailable) {
                    TextButton(
                        onClick = onRestore,
                        enabled = !billingState.purchaseInProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Restore purchases")
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Not now")
            }
        }
    }
}

internal fun proEntitlementStatus(billingState: BillingState): String = when {
    billingState.purchaseInProgress -> "Waiting for Google Play."
    billingState.entitlement == EntitlementState.PRO_CONFIRMED ->
        "Pro is active and ads are disabled."
    billingState.entitlement == EntitlementState.PRO_CACHED ->
        "Pro is available offline and ads are disabled."
    billingState.entitlement == EntitlementState.PENDING ->
        "Purchase pending. Pro stays locked until Google Play confirms it."
    billingState.isAuthoritative -> "Free"
    else -> "Checking purchases with Google Play."
}

@Composable
internal fun ProSettingsCard(billingState: BillingState, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Pro — no ads", style = MaterialTheme.typography.titleMedium)
            Text(proEntitlementStatus(billingState), style = MaterialTheme.typography.bodySmall)
            Text(
                billingState.localizedPrice ?: "Price unavailable",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun PrivacyAndAdsCard(
    consentState: ConsentState,
    privacyPolicyAvailable: Boolean,
    onPrivacyPolicy: () -> Unit,
    onPrivacyChoices: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Privacy and ads", style = MaterialTheme.typography.titleMedium)
            if (privacyPolicyAvailable) {
                TextButton(onClick = onPrivacyPolicy) { Text("Privacy policy") }
            } else {
                Text(
                    "Privacy policy unavailable in this build.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (consentState.privacyOptionsRequirement) {
                PrivacyOptionsRequirement.REQUIRED -> {
                    TextButton(
                        onClick = onPrivacyChoices,
                        enabled = !consentState.isRefreshing,
                    ) {
                        Text(
                            if (consentState.isRefreshing) {
                                "Opening Google privacy choices…"
                            } else {
                                "Google privacy choices"
                            },
                        )
                    }
                }
                PrivacyOptionsRequirement.NOT_REQUIRED -> Text(
                    "Google privacy choices are not required.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PrivacyOptionsRequirement.UNKNOWN -> Text(
                    if (consentState.isRefreshing) {
                        "Checking whether Google privacy choices are required…"
                    } else {
                        "Whether Google privacy choices are required is unavailable."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            consentState.privacyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun ManagedBannerSlot(
    surface: BannerSurface,
    widthDp: Int,
    requestContext: AdRequestContext,
    modifier: Modifier = Modifier,
    loaderFactory: (androidx.lifecycle.LifecycleOwner) -> ManagedBannerLoader =
        ::BannerAdLoader,
    content: @Composable (AdView) -> Unit = { view ->
        AndroidView(factory = { view })
    },
) {
    val format = bannerFormat(surface) ?: return
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val loader = remember(lifecycleOwner) { loaderFactory(lifecycleOwner) }
    var loadedView by remember(loader) { mutableStateOf<AdView?>(null) }

    DisposableEffect(loader) {
        onDispose { loader.dispose() }
    }
    DisposableEffect(activity, widthDp, format, requestContext, loader) {
        loadedView = null
        loader.update(
            activity = activity,
            widthDp = widthDp.coerceAtLeast(1),
            format = format,
            requestContext = requestContext,
            onLoaded = { loadedView = it },
            onFailed = { loadedView = null },
        )
        onDispose {
            loadedView = null
            loader.clear()
        }
    }

    val view = loadedView ?: return
    Box(
        modifier = if (format == BannerFormat.FIXED_320_BY_50) {
            modifier.size(width = 320.dp, height = 50.dp)
        } else {
            modifier.fillMaxWidth().heightIn(min = 50.dp)
        },
        contentAlignment = Alignment.Center,
    ) {
        key(view) { content(view) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val PRO_ENTITLEMENTS = setOf(
    EntitlementState.PRO_CACHED,
    EntitlementState.PRO_CONFIRMED,
)
