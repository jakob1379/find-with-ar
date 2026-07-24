package dev.arsignallocator.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

object PermissionPolicy {
    /**
     * Fine location remains deliberate on Android 12+: this app explicitly derives a physical
     * location from scan results and must not make the neverForLocation assertion.
     */
    @SuppressLint("InlinedApi")
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        // Android 12+ requires coarse and fine to be requested together.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun missingPermissions(
        grantedPermissions: Set<String>,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> = requiredPermissions(sdkInt).filterNot(grantedPermissions::contains)

    fun permissionsToRequest(
        missingPermissions: Collection<String>,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> {
        val requestLocationPair = sdkInt >= Build.VERSION_CODES.S &&
            missingPermissions.any {
                it == Manifest.permission.ACCESS_COARSE_LOCATION ||
                    it == Manifest.permission.ACCESS_FINE_LOCATION
            }
        return requiredPermissions(sdkInt).filter {
            it in missingPermissions ||
                requestLocationPair && (
                    it == Manifest.permission.ACCESS_COARSE_LOCATION ||
                        it == Manifest.permission.ACCESS_FINE_LOCATION
                    )
        }
    }
}
