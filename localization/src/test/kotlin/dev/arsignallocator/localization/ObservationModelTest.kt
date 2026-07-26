package dev.arsignallocator.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationModelTest {
    @Test
    fun `analytic Jacobian agrees with central differences`() {
        val state = doubleArrayOf(1.2, -0.7, -54.0, 2.35)
        val sample = SpatialRssiSample(
            phonePosition = Vec2(-0.4, 1.1),
            phoneHeightMetres = 1.15,
            rssiDbm = -62.0,
            phoneForward = Vec2(-0.8, -0.6),
        )
        val analytic = RssiObservationModel.predictAndJacobian(
            state,
            sample,
            targetHeightMetres = 0.2,
            bodyShadowAttenuationDb = 6.0,
        ).jacobian
        val step = 1e-6

        for (index in state.indices) {
            val above = state.copyOf().also { it[index] += step }
            val below = state.copyOf().also { it[index] -= step }
            val numerical = (
                RssiObservationModel.predictAndJacobian(
                    above,
                    sample,
                    0.2,
                    bodyShadowAttenuationDb = 6.0,
                ).rssiDbm -
                    RssiObservationModel.predictAndJacobian(
                        below,
                        sample,
                        0.2,
                        bodyShadowAttenuationDb = 6.0,
                    ).rssiDbm
                ) / (2.0 * step)
            assertEquals("state index $index", numerical, analytic[index], 2e-6)
        }
    }
}
