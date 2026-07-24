package dev.arsignallocator.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DebugEntitlementOverride {
    private val _state = MutableStateFlow<EntitlementState?>(null)
    val state = _state.asStateFlow()

    fun setFakePro(enabled: Boolean?) {
        _state.value = enabled?.let {
            if (it) EntitlementState.PRO_CONFIRMED else EntitlementState.FREE
        }
    }
}
