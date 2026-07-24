package dev.arsignallocator.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun `Android 12 requests modern Bluetooth and physical-location permissions`() {
        val permissions = PermissionPolicy.requiredPermissions(sdkInt = 31)

        assertTrue(Manifest.permission.CAMERA in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
    }

    @Test
    fun `Android 11 relies on manifest legacy Bluetooth permissions`() {
        val permissions = PermissionPolicy.requiredPermissions(sdkInt = 30)

        assertEquals(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            permissions,
        )
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in permissions)
    }

    @Test
    fun `Android 12 retries coarse and fine when only fine is missing`() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            PermissionPolicy.permissionsToRequest(
                missingPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                sdkInt = 31,
            ),
        )
    }

    @Test
    fun `Android 12 retries coarse and fine when only coarse is missing`() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            PermissionPolicy.permissionsToRequest(
                missingPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                sdkInt = 31,
            ),
        )
    }

    @Test
    fun `Android 11 retries only the missing location permission`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            PermissionPolicy.permissionsToRequest(
                missingPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                sdkInt = 30,
            ),
        )
    }
}
