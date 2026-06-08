package com.robo.phonecompanion

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var frameText: TextView
    private lateinit var scanButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val scanStopRunnable = Runnable { stopScanAndShowPicker() }
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val deviceRssi = linkedMapOf<String, Int>()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private val pendingFrames = ConcurrentLinkedQueue<String>()
    private val displayedLines = ArrayDeque<String>(FRAME_BUFFER_SIZE + 10)
    private val flushRunnable = object : Runnable {
        override fun run() {
            flushFrames()
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        frameText = findViewById(R.id.frameText)
        scanButton = findViewById(R.id.scanButton)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        scanButton.setOnClickListener {
            if (scanning) stopScanAndShowPicker() else startScan()
        }

        handler.post(flushRunnable)
        ensureRuntimePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.close()
    }

    private fun ensureRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.BLUETOOTH_SCAN
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) { status("Bluetooth is disabled"); return }
        ensureRuntimePermissions()
        devices.clear()
        deviceRssi.clear()
        scanning = true
        scanButton.text = getString(R.string.stop_scan)
        status("Scanning for BLE dongles...")
        adapter.bluetoothLeScanner.startScan(scanCallback)
        handler.removeCallbacks(scanStopRunnable)
        handler.postDelayed(scanStopRunnable, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        runOnUiThread { scanButton.text = getString(R.string.scan_for_dongle) }
    }

    private fun stopScanAndShowPicker() {
        stopScan()
        if (devices.isEmpty()) {
            status("No devices found. Tap Scan to try again.")
            return
        }
        showDevicePicker()
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val deviceList = devices.values.toList()
        val labels = deviceList.map { d ->
            val name = d.name ?: "(unnamed)"
            val rssi = deviceRssi[d.address]?.let { "$it dBm" } ?: "?"
            "$name\n${d.address}  $rssi"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(labels) { _, which -> connectDevice(deviceList[which]) }
            .setNegativeButton("Scan Again") { _, _ -> startScan() }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        handler.removeCallbacks(scanStopRunnable)
        stopScan()
        gatt?.close()
        status("Connecting to ${device.name ?: device.address}...")
        gatt = device.connectGatt(this, false, gattCallback)
    }

    private fun status(msg: String) {
        runOnUiThread { statusText.text = "Status: $msg" }
    }

    private fun appendFrame(line: String) {
        pendingFrames.add(line)
    }

    private fun flushFrames() {
        if (pendingFrames.isEmpty()) return
        var count = 0
        while (pendingFrames.isNotEmpty() && count < MAX_FRAMES_PER_FLUSH) {
            displayedLines.addLast(pendingFrames.poll() ?: break)
            count++
        }
        while (displayedLines.size > FRAME_BUFFER_SIZE) displayedLines.removeFirst()
        frameText.text = displayedLines.joinToString("\n")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            devices[device.address] = device
            deviceRssi[device.address] = result.rssi
            status("Found ${devices.size} device(s), latest: ${device.name ?: "(unnamed)"}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                status("Connected, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                status("Disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service: BluetoothGattService = gatt.getService(UART_SERVICE_UUID) ?: run {
                status("UART service not found")
                return
            }
            val tx: BluetoothGattCharacteristic = service.getCharacteristic(UART_TX_UUID) ?: run {
                status("UART TX characteristic not found")
                return
            }
            if (!gatt.setCharacteristicNotification(tx, true)) {
                status("Failed to enable notifications")
                return
            }
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
                status("Subscribed. Waiting for CAN frames...")
            } else {
                status("CCCD missing on TX characteristic")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNotification(characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }

        private fun handleNotification(uuid: UUID, value: ByteArray?) {
            if (uuid == UART_TX_UUID) {
                val payload = value?.toString(Charsets.UTF_8).orEmpty().trim()
                if (payload.isNotEmpty()) payload.lines().forEach { appendFrame(it) }
            }
        }
    }

    companion object {
        private val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val FLUSH_INTERVAL_MS = 100L
        private const val FRAME_BUFFER_SIZE = 200
        private const val MAX_FRAMES_PER_FLUSH = 50
    }
}
