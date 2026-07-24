package dev.arsignallocator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arsignallocator.BuildConfig
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.DebugEntitlementOverride
import dev.arsignallocator.monetization.EntitlementState

internal class VariantMonetizationUi(
    val effectiveBillingState: BillingState,
    val allowAds: Boolean,
    val overrideState: EntitlementState?,
    val onOverrideChange: (EntitlementState?) -> Unit,
    val onAllowAdsChange: (Boolean) -> Unit,
)

@Composable
internal fun rememberVariantMonetizationUi(billingState: BillingState): VariantMonetizationUi {
    val entitlementOverride = remember { DebugEntitlementOverride() }
    val overrideState by entitlementOverride.state.collectAsStateWithLifecycle()
    var allowAds by rememberSaveable { mutableStateOf(BuildConfig.DEBUG_TEST_ADS_ENABLED) }
    return VariantMonetizationUi(
        effectiveBillingState = overrideBillingState(billingState, overrideState),
        allowAds = allowAds,
        overrideState = overrideState,
        onOverrideChange = {
            entitlementOverride.setFakePro(
                when (it) {
                    EntitlementState.PRO_CONFIRMED -> true
                    EntitlementState.FREE -> false
                    else -> null
                },
            )
        },
        onAllowAdsChange = { allowAds = it },
    )
}

internal fun overrideBillingState(
    billingState: BillingState,
    overrideState: EntitlementState?,
): BillingState = overrideState?.let {
    billingState.copy(
        entitlement = it,
        isAuthoritative = true,
        purchaseInProgress = false,
        retryableError = null,
    )
} ?: billingState

@Composable
internal fun VariantMonetizationSettings(ui: VariantMonetizationUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Developer monetization", style = MaterialTheme.typography.titleMedium)
            listOf(
                null to "Play entitlement",
                EntitlementState.FREE to "Fake free",
                EntitlementState.PRO_CONFIRMED to "Fake Pro",
            ).forEach { (state, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = ui.overrideState == state,
                        role = Role.RadioButton,
                        onClick = { ui.onOverrideChange(state) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = ui.overrideState == state, onClick = null)
                    Text(label)
                }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = ui.allowAds,
                    role = Role.Switch,
                    onValueChange = ui.onAllowAdsChange,
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show test ads")
                Switch(checked = ui.allowAds, onCheckedChange = null)
            }
        }
    }
}
