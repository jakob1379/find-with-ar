package dev.arsignallocator.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import dev.arsignallocator.core.ElapsedRealtimeClock
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.TargetHandle
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

data class ScanPacket(
    val target: TargetHandle,
    val observation: BleObservation,
)

sealed class BleAvailability {
    data object Available : BleAvailability()
    data object Unsupported : BleAvailability()
    data object Disabled : BleAvailability()
    data class PermissionDenied(val detail: String) : BleAvailability()
}

class BleScanException(message: String, val errorCode: Int? = null) : Exception(message)

interface BleScanner {
    fun availability(): BleAvailability
    fun discover(durationMillis: Long = 12_000L): Flow<ScanPacket>
    fun track(target: TargetHandle): Flow<ScanPacket>
}

class AndroidBleScanner(
    context: Context,
    private val clock: MonotonicClock = ElapsedRealtimeClock,
) : BleScanner {
    private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
    private val identityLock = Any()
    private val identities = linkedMapOf<String, DeviceKey>()

    @SuppressLint("MissingPermission")
    override fun availability(): BleAvailability = try {
        val adapter = manager?.adapter ?: return BleAvailability.Unsupported
        if (adapter.isEnabled) BleAvailability.Available else BleAvailability.Disabled
    } catch (error: SecurityException) {
        BleAvailability.PermissionDenied(error.message ?: "Bluetooth permission is missing")
    }

    override fun discover(durationMillis: Long): Flow<ScanPacket> {
        synchronized(identityLock) { identities.clear() }
        return scan(target = null, durationMillis = durationMillis, lowLatency = false)
    }

    override fun track(target: TargetHandle): Flow<ScanPacket> =
        scan(target = target, durationMillis = null, lowLatency = true)

    @SuppressLint("MissingPermission")
    private fun scan(
        target: TargetHandle?,
        durationMillis: Long?,
        lowLatency: Boolean,
    ): Flow<ScanPacket> = callbackFlow {
        val adapter = manager?.adapter
            ?: throw BleScanException("This phone does not support Bluetooth")
        if (!adapter.isEnabled) throw BleScanException("Bluetooth is turned off")
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
            ?: throw BleScanException("Bluetooth LE scanning is unavailable")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emitResult)
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleScanException(scanFailureMessage(errorCode), errorCode))
            }

            private fun emitResult(result: ScanResult) {
                val packet = try {
                    result.toPacket(target)
                } catch (ignored: SecurityException) {
                    close(BleScanException("Bluetooth permission was revoked"))
                    null
                }
                if (packet != null) trySend(packet)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(
                if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED,
            )
            .setReportDelay(0L)
            .build()

        try {
            scanner.startScan(emptyList(), settings, callback)
        } catch (error: SecurityException) {
            throw BleScanException("Bluetooth scan permission is missing")
        } catch (error: IllegalStateException) {
            throw BleScanException(error.message ?: "Bluetooth scanner could not start")
        }

        val timeout = durationMillis?.let { duration ->
            launch {
                delay(duration)
                close()
            }
        }

        awaitClose {
            timeout?.cancel()
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // The OS permission can be revoked while a scan is active.
            } catch (_: IllegalStateException) {
                // Bluetooth can be switched off while a scan is active.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toPacket(selected: TargetHandle?): ScanPacket? {
        val address = device.address.uppercase(Locale.ROOT)
        if (selected != null && selected.ephemeralAddress != address) return null

        val record = scanRecord
        val manufacturerIds = buildSet {
            val data = record?.manufacturerSpecificData ?: return@buildSet
            repeat(data.size()) { index -> add(data.keyAt(index)) }
        }
        val services = record?.serviceUuids.orEmpty().mapTo(linkedSetOf()) { it.uuid.toString() }
        val key = synchronized(identityLock) {
            identities.getOrPut(address) {
                DeviceKey("D${(identities.size + 1).toString().padStart(2, '0')}")
            }
        }
        val handle = selected ?: TargetHandle(
            key = key,
            ephemeralAddress = address,
            advertisedName = record?.deviceName,
            manufacturerIds = manufacturerIds,
            serviceUuids = services,
        )
        val txPower = record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }
        val observation = BleObservation(
            observedAtPlatformNanos = timestampNanos,
            receivedAtElapsedNanos = clock.nowNanos(),
            deviceKey = handle.key,
            rssiDbm = rssi,
            txPowerDbm = txPower,
            advertisedName = record?.deviceName ?: handle.advertisedName,
            manufacturerIds = manufacturerIds.ifEmpty { handle.manufacturerIds },
            serviceUuids = services.ifEmpty { handle.serviceUuids },
        )
        return ScanPacket(handle, observation)
    }

    private fun scanFailureMessage(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A Bluetooth scan is already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Android could not register the Bluetooth scanner; toggle Bluetooth and retry"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support this BLE scan"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android reported an internal Bluetooth error"
        else -> "Bluetooth scan failed (code $code)"
    }
}
