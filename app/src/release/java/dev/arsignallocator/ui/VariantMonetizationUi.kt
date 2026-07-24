@file:Suppress("UNUSED_PARAMETER")

package dev.arsignallocator.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.arsignallocator.monetization.BillingState

internal class VariantMonetizationUi(
    val effectiveBillingState: BillingState,
    val allowAds: Boolean = false,
)

@Composable
internal fun rememberVariantMonetizationUi(billingState: BillingState): VariantMonetizationUi =
    remember(billingState) { VariantMonetizationUi(billingState) }

@Composable
internal fun VariantMonetizationSettings(ui: VariantMonetizationUi) = Unit
