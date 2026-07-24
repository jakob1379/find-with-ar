package dev.arsignallocator.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import dev.arsignallocator.core.ElapsedRealtimeClock
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.TargetHandle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
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
    fun discover(durationMillis: Long = 20_000L): Flow<ScanPacket>
    fun track(target: TargetHandle): Flow<ScanPacket>
    fun completeScan(): Flow<ScanPacket>
}

internal data class RadioIdentity(
    val transport: BluetoothTransport,
    val ephemeralAddress: String,
)

internal class SessionDeviceIdentities {
    private val keys = linkedMapOf<RadioIdentity, DeviceKey>()

    @Synchronized
    fun key(transport: BluetoothTransport, ephemeralAddress: String): DeviceKey =
        keys.getOrPut(RadioIdentity(transport, ephemeralAddress)) {
            DeviceKey("D${(keys.size + 1).toString().padStart(2, '0')}")
        }

    @Synchronized
    fun size(): Int = keys.size
}

internal fun continuousCompleteScan(
    supportsLowEnergy: Boolean,
    scanLowEnergyWindow: (SessionDeviceIdentities) -> Flow<ScanPacket>,
    scanClassicInquiry: (SessionDeviceIdentities) -> Flow<ScanPacket>,
): Flow<ScanPacket> = flow {
    val identities = SessionDeviceIdentities()
    while (currentCoroutineContext().isActive) {
        if (supportsLowEnergy) emitAll(scanLowEnergyWindow(identities))
        if (!currentCoroutineContext().isActive) break
        emitAll(scanClassicInquiry(identities))
    }
}

class AndroidBleScanner(
    context: Context,
    private val clock: MonotonicClock = ElapsedRealtimeClock,
) : BleScanner {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)

    @SuppressLint("MissingPermission")
    override fun availability(): BleAvailability = try {
        val adapter = manager?.adapter ?: return BleAvailability.Unsupported
        if (adapter.isEnabled) BleAvailability.Available else BleAvailability.Disabled
    } catch (error: SecurityException) {
        BleAvailability.PermissionDenied(error.message ?: "Bluetooth permission is missing")
    }

    override fun discover(durationMillis: Long): Flow<ScanPacket> = flow {
        val identities = SessionDeviceIdentities()
        val leDuration = if (
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        ) {
            minOf(durationMillis, LE_DISCOVERY_MILLIS)
        } else {
            0L
        }
        val classicDuration = durationMillis - leDuration
        if (leDuration > 0L) {
            emitAll(
                scanLe(
                    target = null,
                    durationMillis = leDuration,
                    lowLatency = false,
                    identities = identities,
                ),
            )
        }
        if (classicDuration > 0L) {
            emitAll(
                scanClassic(
                    target = null,
                    durationMillis = classicDuration,
                    identities = identities,
                ),
            )
        }
    }

    override fun track(target: TargetHandle): Flow<ScanPacket> = when (target.transport) {
        BluetoothTransport.CLASSIC -> scanClassic(
            target = target,
            durationMillis = null,
            identities = null,
        )
        BluetoothTransport.LOW_ENERGY -> scanLe(
            target = target,
            durationMillis = null,
            lowLatency = true,
            identities = null,
        )
    }

    override fun completeScan(): Flow<ScanPacket> = continuousCompleteScan(
        supportsLowEnergy = appContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE,
        ),
        scanLowEnergyWindow = { identities ->
            scanLe(
                target = null,
                durationMillis = COMPLETE_SCAN_LE_WINDOW_MILLIS,
                lowLatency = false,
                identities = identities,
            )
        },
        scanClassicInquiry = { identities ->
            scanClassic(target = null, durationMillis = null, identities = identities)
        },
    )

    @SuppressLint("MissingPermission")
    private fun scanLe(
        target: TargetHandle?,
        durationMillis: Long?,
        lowLatency: Boolean,
        identities: SessionDeviceIdentities?,
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
                    result.toPacket(target, identities)
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
    private fun scanClassic(
        target: TargetHandle?,
        durationMillis: Long?,
        identities: SessionDeviceIdentities?,
    ): Flow<ScanPacket> = callbackFlow {
        val adapter = manager?.adapter
            ?: throw BleScanException("This phone does not support Bluetooth")
        if (!adapter.isEnabled) throw BleScanException("Bluetooth is turned off")

        var discoveryStarted = false
        var restartJob: Job? = null
        fun beginDiscovery() {
            try {
                discoveryStarted = adapter.startDiscovery()
                if (!discoveryStarted) {
                    close(BleScanException("Android could not start Bluetooth Classic discovery"))
                }
            } catch (error: SecurityException) {
                close(BleScanException("Bluetooth scan permission is missing"))
            } catch (error: IllegalStateException) {
                close(BleScanException(error.message ?: "Bluetooth discovery could not start"))
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val packet = try {
                            intent.toClassicPacket(target, identities)
                        } catch (_: SecurityException) {
                            close(BleScanException("Bluetooth permission was revoked"))
                            null
                        }
                        if (packet != null) {
                            trySend(packet)
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (!discoveryStarted) return
                        discoveryStarted = false
                        if (target == null) {
                            close()
                        } else {
                            restartJob?.cancel()
                            restartJob = launch {
                                delay(CLASSIC_RESTART_DELAY_MILLIS)
                                beginDiscovery()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            adapter.cancelDiscovery()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        } catch (error: SecurityException) {
            throw BleScanException("Bluetooth scan permission is missing")
        }

        val startJob = launch {
            delay(CLASSIC_START_DELAY_MILLIS)
            beginDiscovery()
        }
        val timeout = durationMillis?.let { duration ->
            launch {
                delay(duration)
                close()
            }
        }

        awaitClose {
            startJob.cancel()
            restartJob?.cancel()
            timeout?.cancel()
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { adapter.cancelDiscovery() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun Intent.toClassicPacket(
        selected: TargetHandle?,
        identities: SessionDeviceIdentities?,
    ): ScanPacket? {
        val device = bluetoothDevice() ?: return null
        val rssi = getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
        if (rssi !in -127..20) return null
        val address = device.address.uppercase(Locale.ROOT)
        if (selected != null && selected.ephemeralAddress != address) return null
        val receivedAtNanos = clock.nowNanos()
        val services = device.uuids.orEmpty().mapTo(linkedSetOf()) { it.uuid.toString() }
        val transport = selected?.transport ?: BluetoothTransport.CLASSIC
        val key = selected?.key ?: checkNotNull(identities).key(transport, address)
        val name = getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
        val handle = selected ?: TargetHandle(
            key = key,
            ephemeralAddress = address,
            advertisedName = name,
            manufacturerIds = emptySet(),
            serviceUuids = services,
            transport = transport,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
        )
        return ScanPacket(
            target = handle,
            observation = BleObservation(
                observedAtPlatformNanos = receivedAtNanos,
                receivedAtElapsedNanos = receivedAtNanos,
                deviceKey = handle.key,
                rssiDbm = rssi,
                txPowerDbm = null,
                advertisedName = name ?: handle.advertisedName,
                manufacturerIds = handle.manufacturerIds,
                serviceUuids = services.ifEmpty { handle.serviceUuids },
                transport = transport,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toPacket(
        selected: TargetHandle?,
        identities: SessionDeviceIdentities?,
    ): ScanPacket? {
        if (rssi !in -127..20) return null
        val address = device.address.uppercase(Locale.ROOT)
        if (selected != null && selected.ephemeralAddress != address) return null

        val record = scanRecord
        val manufacturerIds = buildSet {
            val data = record?.manufacturerSpecificData ?: return@buildSet
            repeat(data.size()) { index -> add(data.keyAt(index)) }
        }
        val services = record?.serviceUuids.orEmpty().mapTo(linkedSetOf()) { it.uuid.toString() }
        val key = selected?.key
            ?: checkNotNull(identities).key(BluetoothTransport.LOW_ENERGY, address)
        val handle = selected ?: TargetHandle(
            key = key,
            ephemeralAddress = address,
            advertisedName = record?.deviceName,
            manufacturerIds = manufacturerIds,
            serviceUuids = services,
            transport = BluetoothTransport.LOW_ENERGY,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
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
            transport = BluetoothTransport.LOW_ENERGY,
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

    private companion object {
        const val LE_DISCOVERY_MILLIS = 6_000L
        const val COMPLETE_SCAN_LE_WINDOW_MILLIS = 6_000L
        const val CLASSIC_START_DELAY_MILLIS = 250L
        const val CLASSIC_RESTART_DELAY_MILLIS = 300L
    }
}
