package com.robo.phonegateway.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Transmit PHY the link settled on: 1 = 1M, 2 = 2M, 3 = coded. */
    private val onPhy: (Int) -> Unit = {},
    /** The connected dongle's firmware version (null when disconnected or unknown). */
    private val onFirmware: (String?) -> Unit = {},
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val prefs = context.getSharedPreferences("dongle", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    /**
     * GATT callbacks run here, one at a time. Without a handler Android delivers
     * notifications on several binder threads at once, so lines were processed out
     * of order and the (unsynchronised) clock and counters raced.
     */
    private var gattThread: HandlerThread? = null
    /** Set once notifications are enabled: the dongle can take commands. */
    @Volatile private var ready: BluetoothGatt? = null
    private var scanning = false
    private var running = false
    @Volatile private var mtu = 23
    // OTA: acknowledgements of control/data writes, and status notifications
    private val otaAcks = Channel<Boolean>(Channel.CONFLATED)
    private val otaStatus = Channel<String>(Channel.UNLIMITED)
    private val versions = Channel<String?>(Channel.CONFLATED) // each version read after a connect

    fun start() = main.post {
        if (running) return@post
        running = true
        connectOrScan()
    }

    fun stop() = main.post {
        running = false
        stopScan()
        ready = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        gattThread?.quitSafely()
        gattThread = null
        onState("stopped")
    }

    /** The dongle this app pairs with (remembered on first connect), else null. */
    val rememberedAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)

    /** Name and address of the dongle while it is ready for commands, else null. */
    val connectedDevice: String?
        get() = ready?.device?.let { "${it.name ?: "dongle"} ${it.address}" }

    /** CAN ids the dongle should forward (empty = all), sent on every connect. */
    var filterIds: List<Int>
        get() = CanFilter.ids(prefs)
        set(value) = CanFilter.save(prefs, value)

    /** Re-send the saved filter to a connected dongle (it applies at once). */
    fun sendFilter() = main.post { ready?.let { writeFilter(it) } }

    /** Returns false if the dongle has no RX characteristic to write to. */
    private fun writeFilter(g: BluetoothGatt): Boolean {
        val rx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX) ?: return false
        return write(g, rx, CanFilter.command(CanFilter.ids(prefs)).toByteArray(Charsets.UTF_8))
    }

    /** Queues a write with response; false if the stack refused it. */
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }

    /** True while a dongle is connected that has the OTA service. */
    val canUpdate: Boolean
        get() = ready?.getService(OTA_SERVICE)?.getCharacteristic(OTA_CTRL) != null

    /**
     * Installs [image] on the connected dongle (see [DongleOta]). The dongle restarts on
     * success, so this client reconnects to it as usual. Throws [DongleOta.Failed].
     */
    suspend fun update(image: ByteArray, version: String, onProgress: (DongleOta.Progress) -> Unit) {
        val g = ready ?: throw DongleOta.Failed("dongle not connected")
        val svc = g.getService(OTA_SERVICE) ?: throw DongleOta.Failed("this dongle's firmware has no OTA service")
        val ctrl = svc.getCharacteristic(OTA_CTRL) ?: throw DongleOta.Failed("no OTA control characteristic")
        val data = svc.getCharacteristic(OTA_DATA) ?: throw DongleOta.Failed("no OTA data characteristic")
        val link = object : DongleOta.Link {
            suspend fun acked(c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
                while (otaAcks.tryReceive().isSuccess) Unit
                if (ready != g || !write(g, c, value)) return false
                return withTimeoutOrNull(WRITE_TIMEOUT_MS) { otaAcks.receive() } ?: false
            }
            override suspend fun control(command: String) = acked(ctrl, command.toByteArray(Charsets.UTF_8))
            override suspend fun data(chunk: ByteArray) = acked(data, chunk)
            override suspend fun status(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) { otaStatus.receive() }
            override fun clearStatus() { while (otaStatus.tryReceive().isSuccess) Unit }
            override suspend fun reconnectedVersion(timeoutMs: Long) =
                withTimeoutOrNull(timeoutMs) { versions.receive() }
        }
        while (versions.tryReceive().isSuccess) Unit
        DongleOta.upload(link, image, version, chunkSize = mtu - 3, onProgress = onProgress)
    }

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
        val thread = gattThread ?: HandlerThread("dongle-gatt").also { it.start(); gattThread = it }
        // ask for the 2M PHY up front: twice the air rate of 1M, which a busy bus
        // (~30 KB/s of lines through the 23-id filter on the Impala) outgrows
        gatt = device.connectGatt(
            context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_2M_MASK, Handler(thread.looper),
        )
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
                        g.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                        )
                        // larger MTU so a notification carries more lines; discover either way
                        if (!g.requestMtu(517)) g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (ready == g) ready = null
                        onFirmware(null)
                        otaStatus.trySend(DongleOta.DISCONNECTED)
                        g.close()
                        if (gatt == g) gatt = null
                        onState("disconnected (status $status)")
                        main.postDelayed(::connectOrScan, RETRY_MS)
                    }
                }
            }
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onPhy(txPhy)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@DongleClient.mtu = mtu
            onMtu(mtu)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            if (tx == null || tx.getDescriptor(CCCD) == null) {
                onState("dongle has no NUS TX characteristic")
                g.disconnect()
                return
            }
            // Filter first, then notifications: the dongle starts every connection
            // unfiltered, so this way the stream it sends is filtered from its first line.
            if (!writeFilter(g)) enableNotifications(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            // the connect-time filter write is done; a later re-send (sendFilter) needs nothing more
            if (c.uuid == NUS_RX && ready != g) enableNotifications(g)
            if (c.uuid == OTA_CTRL || c.uuid == OTA_DATA) otaAcks.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        private fun enableNotifications(g: BluetoothGatt) {
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            if (tx == null || !subscribe(g, tx)) {
                onState("could not enable dongle notifications")
                g.disconnect()
            }
        }

        /** Turns on notifications for [c]; the CCCD write completes in onDescriptorWrite. */
        private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
            val cccd = c.getDescriptor(CCCD) ?: return false
            if (!g.setCharacteristicNotification(c, true)) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            return true
        }

        // Connect chain: filter -> NUS TX notifications -> OTA status notifications ->
        // firmware version -> ready. One GATT operation at a time; the OTA steps are
        // skipped on firmware without the OTA service.
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            when (d.characteristic.uuid) {
                NUS_TX -> {
                    val otaStatusChar = g.getService(OTA_SERVICE)?.getCharacteristic(OTA_STATUS)
                    if (otaStatusChar == null || !subscribe(g, otaStatusChar)) readVersion(g)
                }
                OTA_STATUS -> readVersion(g)
            }
        }

        private fun readVersion(g: BluetoothGatt) {
            val v = g.getService(OTA_SERVICE)?.getCharacteristic(OTA_VERSION)
            if (v == null || !g.readCharacteristic(v)) versionRead(g, null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (c.uuid != OTA_VERSION) return
            versionRead(g, if (status == BluetoothGatt.GATT_SUCCESS) value.toString(Charsets.UTF_8).trim() else null)
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || c.uuid != OTA_VERSION) return
            @Suppress("DEPRECATION")
            versionRead(g, if (status == BluetoothGatt.GATT_SUCCESS) c.value?.toString(Charsets.UTF_8)?.trim() else null)
        }

        private fun versionRead(g: BluetoothGatt, version: String?) {
            onFirmware(version)
            versions.trySend(version)
            markReady(g)
        }

        /** Notifications are on, already filtered: the dongle can take commands. */
        private fun markReady(g: BluetoothGatt) {
            ready = g
            val ids = CanFilter.ids(prefs)
            val filtered = if (ids.isEmpty()) "all ids" else "${ids.size} ids"
            onState("connected to ${g.device.name ?: g.device.address} ($filtered)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            changed(c, value)
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) changed(c, c.value ?: return)
        }

        private fun changed(c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                NUS_TX -> onNotification(value.toString(Charsets.UTF_8), System.currentTimeMillis())
                OTA_STATUS -> otaStatus.trySend(value.toString(Charsets.UTF_8).trim())
            }
        }
    }

    companion object {
        private val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val OTA_SERVICE: UUID = UUID.fromString("6E410001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_CTRL: UUID = UUID.fromString("6E410002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_DATA: UUID = UUID.fromString("6E410003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_STATUS: UUID = UUID.fromString("6E410004-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_VERSION: UUID = UUID.fromString("6E410005-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val KEY_ADDRESS = "address"
        private const val RETRY_MS = 3_000L
    }
}
