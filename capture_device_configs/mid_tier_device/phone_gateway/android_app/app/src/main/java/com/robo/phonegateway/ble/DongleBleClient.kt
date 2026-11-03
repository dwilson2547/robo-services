package com.robo.phonegateway.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/**
 * BLE client for the XIAO C6 dongle (dbc-forge firmware). Transport, line parsers and
 * the 16-bit timestamp-wrap reconstruction are copied from dbc-forge's
 * CanBusViewModel as-is; OTA/UI concerns are dropped.
 *
 * The dongle streams newline-delimited text over the Nordic UART Service TX
 * characteristic:
 *   CAN frame: `STD,0x123,8,DE,AD,BE,EF,00,11,22,33,<fwTs>` (or `EXT,...`)
 *   IMU:       `IMU,qw,qx,qy,qz,ax,ay,az,gx,gy,gz,<fwTs>`
 * where <fwTs> is the firmware's capture counter (millis % 65536).
 */
class DongleBleClient(private val context: Context) {

    interface Listener {
        fun onConnectionChanged(connected: Boolean, deviceName: String)
        fun onCanFrame(frame: CanFrame) {}
        fun onImuSample(sample: ImuSample) {}
        fun onParseErrors(count: Long) {}
    }

    val canChannel = Channel<CanFrame>(Channel.UNLIMITED)
    val imuChannel = Channel<ImuSample>(Channel.UNLIMITED)

    var listener: Listener? = null
    var isConnected: Boolean = false
        private set

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter

    private var gatt: BluetoothGatt? = null
    private var userInitiatedDisconnect = false
    private var reconnectAttempts = 0
    private var lastAddress: String? = null
    private var parseErrors = 0L

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (device.name?.contains("XIAO", ignoreCase = true) == true ||
                device.name?.contains("DBC", ignoreCase = true) == true
            ) {
                connect(device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectAttempts = 0
                    listener?.onConnectionChanged(true, g.device.name ?: g.device.address)
                    if (!g.requestMtu(517)) g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    isConnected = false
                    listener?.onConnectionChanged(false, "")
                    if (!userInitiatedDisconnect) scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val nusSvc = g.getService(UART_SERVICE_UUID)
            val tx = nusSvc?.getCharacteristic(UART_TX_UUID)
            if (tx != null && g.setCharacteristicNotification(tx, true)) {
                val cccd = tx.getDescriptor(CCCD_UUID)
                @Suppress("DEPRECATION")
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                cccd?.let { g.writeDescriptor(it) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == UART_TX_UUID) handleNotification(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == UART_TX_UUID) handleNotification(characteristic.value)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        lastAddress?.let { addr ->
            runCatching { bluetoothAdapter?.getRemoteDevice(addr) }?.getOrNull()?.let {
                connect(it); return
            }
        }
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        userInitiatedDisconnect = false
        lastAddress = device.address
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        reconnectAttempts++
        val addr = lastAddress ?: return
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(addr) }.getOrNull() ?: return
        android.os.Handler(context.mainLooper).postDelayed({ connect(device) }, 3000L)
    }

    // ── Notification → line parsing (copied from dbc-forge) ──────────────────

    private fun handleNotification(value: ByteArray?) {
        val payload = value?.toString(Charsets.UTF_8).orEmpty().trim()
        if (payload.isEmpty()) return
        val now = System.currentTimeMillis()
        val rawFrames = mutableListOf<CanFrame>()
        val rawImu = mutableListOf<ImuSample>()
        payload.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            if (line.startsWith("IMU,")) {
                parseImuLine(line, now)?.let { rawImu.add(it) } ?: run { parseErrors++ }
            } else {
                parseFrameLine(line, now)?.let { rawFrames.add(it) } ?: run { parseErrors++ }
            }
        }
        // Reconstruct intra-packet timestamps from the firmware's 16-bit
        // (millis % 65536) capture counter. The latest entry maps to `now`,
        // earlier ones to `now - age`. The counter WRAPS every 65.536 s, so the
        // numerical max is not the temporally-latest entry across a wrap — use
        // signed circular offsets from a pivot instead (all entries in a packet
        // are within a few hundred ms, far under the ±32768 ms unambiguous range).
        val allFwTs = rawFrames.mapNotNull { it.firmwareTimestampMs } +
            rawImu.mapNotNull { it.firmwareTimestampMs }
        if (allFwTs.isNotEmpty()) {
            val pivot = allFwTs.first()
            fun signedOff(fw: Long): Long =
                (((fw - pivot) % 65536L + 65536L + 32768L) % 65536L) - 32768L
            val refOff = allFwTs.maxOf { signedOff(it) } // latest entry
            rawFrames.forEach { frame ->
                val adjusted = frame.firmwareTimestampMs?.let { fw ->
                    val age = (refOff - signedOff(fw)).coerceAtLeast(0L)
                    frame.copy(timestampMs = now - age, firmwareTimestampMs = null)
                } ?: frame
                canChannel.trySend(adjusted)
                listener?.onCanFrame(adjusted)
            }
            rawImu.forEach { sample ->
                val adjusted = sample.firmwareTimestampMs?.let { fw ->
                    val age = (refOff - signedOff(fw)).coerceAtLeast(0L)
                    sample.copy(timestampMs = now - age, firmwareTimestampMs = null)
                } ?: sample
                imuChannel.trySend(adjusted)
                listener?.onImuSample(adjusted)
            }
        } else {
            rawFrames.forEach { canChannel.trySend(it); listener?.onCanFrame(it) }
            rawImu.forEach { imuChannel.trySend(it); listener?.onImuSample(it) }
        }
        if (parseErrors > 0) listener?.onParseErrors(parseErrors)
    }

    // `STD,0x123,8,DE,AD,...` / `EXT,...` with optional trailing fw timestamp
    private fun parseFrameLine(line: String, timestampMs: Long): CanFrame? {
        val parts = line.split(",")
        if (parts.size < 3) return null
        return runCatching {
            val isExtended = parts[0].trim() == "EXT"
            val id = parts[1].trim().removePrefix("0x").toInt(16)
            val dlc = parts[2].trim().toInt()
            val data = ByteArray(dlc) { i ->
                parts.getOrNull(3 + i)?.trim()?.toInt(16)?.toByte() ?: 0
            }
            val fwTs = parts.getOrNull(3 + dlc)?.trim()?.toLongOrNull()
            CanFrame(timestampMs, id, isExtended, data, firmwareTimestampMs = fwTs)
        }.getOrNull()
    }

    // IMU,qw,qx,qy,qz,ax,ay,az,gx,gy,gz,<fwTs>
    private fun parseImuLine(line: String, timestampMs: Long): ImuSample? {
        val parts = line.split(",")
        if (parts.size < 11) return null
        return runCatching {
            fun f(i: Int) = parts[i].trim().toFloat()
            val fwTs = parts.getOrNull(11)?.trim()?.toLongOrNull()
            ImuSample(
                timestampMs = timestampMs,
                qw = f(1), qx = f(2), qy = f(3), qz = f(4),
                ax = f(5), ay = f(6), az = f(7),
                gx = f(8), gy = f(9), gz = f(10),
                firmwareTimestampMs = fwTs,
            )
        }.getOrNull()
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
