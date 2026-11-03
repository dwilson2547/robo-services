package com.robo.phonegateway.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Connects to the dbc-forge dongle (Nordic UART service) and hands every TX
 * notification to [onNotification] with its receive time.
 *
 * Connects to the last dongle used, or the first NUS device seen, and keeps
 * reconnecting for as long as it is started: in a car the dongle comes and goes
 * with the ignition. Callers must hold BLUETOOTH_SCAN and BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class DongleClient(
    private val context: Context,
    private val onNotification: (payload: String, receivedAtMs: Long) -> Unit,
    private val onState: (String) -> Unit,
    private val onMtu: (Int) -> Unit = {},
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val prefs = context.getSharedPreferences("dongle", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var running = false

    fun start() = main.post {
        if (running) return@post
        running = true
        connectOrScan()
    }

    fun stop() = main.post {
        running = false
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onState("stopped")
    }

    /** CAN ids the dongle should forward (empty = all), sent on every connect. */
    var filterIds: List<Int>
        get() = CanFilter.ids(prefs)
        set(value) = CanFilter.save(prefs, value)

    /** Forget the remembered dongle, so the next NUS device seen is used. */
    fun forget() = prefs.edit().remove(KEY_ADDRESS).apply()

    private fun connectOrScan() {
        if (!running) return
        if (adapter == null || !adapter.isEnabled) {
            onState("bluetooth off")
            main.postDelayed(::connectOrScan, RETRY_MS)
            return
        }
        // Scan even when an address is remembered: connectGatt with autoConnect=false
        // to a device that is not advertising just times out, and scanning finds it
        // as soon as the dongle powers up.
        startScan()
    }

    private fun startScan() {
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: run {
            main.postDelayed(::connectOrScan, RETRY_MS)
            return
        }
        scanning = true
        onState("scanning")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val remembered = prefs.getString(KEY_ADDRESS, null)
            if (remembered != null && remembered != device.address) return
            main.post { connect(device) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onState("scan failed ($errorCode)")
            main.postDelayed(::connectOrScan, RETRY_MS)
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!running || gatt != null) return
        stopScan()
        onState("connecting to ${device.name ?: device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        prefs.edit().putString(KEY_ADDRESS, g.device.address).apply()
                        // shorter connection interval (~11-15 ms instead of ~45 ms): the
                        // dongle can send several times more notifications per second
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // larger MTU so a notification carries more lines; discover either way
                        if (!g.requestMtu(517)) g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        g.close()
                        if (gatt == g) gatt = null
                        onState("disconnected (status $status)")
                        main.postDelayed(::connectOrScan, RETRY_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onMtu(mtu)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            val cccd = tx?.getDescriptor(CCCD)
            if (tx == null || cccd == null || !g.setCharacteristicNotification(tx, true)) {
                onState("dongle has no NUS TX characteristic")
                g.disconnect()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            // notifications are on; now tell the dongle which ids to forward
            val rx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX)
            val ids = CanFilter.ids(prefs)
            if (rx != null) {
                val command = CanFilter.command(ids).toByteArray(Charsets.UTF_8)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(rx, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    rx.value = command
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(rx)
                }
            }
            val filtered = if (ids.isEmpty()) "all ids" else "${ids.size} ids"
            onState("connected to ${g.device.name ?: g.device.address} ($filtered)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == NUS_TX) onNotification(value.toString(Charsets.UTF_8), System.currentTimeMillis())
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && c.uuid == NUS_TX) {
                @Suppress("DEPRECATION")
                onNotification(c.value.toString(Charsets.UTF_8), System.currentTimeMillis())
            }
        }
    }

    companion object {
        private val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val KEY_ADDRESS = "address"
        private const val RETRY_MS = 3_000L
    }
}
