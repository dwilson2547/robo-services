package com.robo.racelogger.vm

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robo.racelogger.BleUuids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

// ── Public state types ────────────────────────────────────────────────────────

enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

enum class DeviceStatus { BOOT, WAITING, READY, UNKNOWN }

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
)

data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val secured: Boolean,
)

data class MqttConfig(
    val host: String = "",
    val port: String = "",
    val topic: String = "",
    val user: String = "",
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class RaceLoggerViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val wifiManager =
        application.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // BLE connection
    private val _bleState = MutableStateFlow(BleState.DISCONNECTED)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus.UNKNOWN)
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    // Byte 1 of STATUS notify: bit0=GPS, bit1=PPS, bit2=CAN, bit3=IMU
    private val _gpsLocked  = MutableStateFlow<Boolean?>(null)
    val gpsLocked: StateFlow<Boolean?> = _gpsLocked.asStateFlow()
    private val _ppsLocked  = MutableStateFlow<Boolean?>(null)
    val ppsLocked: StateFlow<Boolean?> = _ppsLocked.asStateFlow()
    private val _canFlow    = MutableStateFlow<Boolean?>(null)
    val canFlow: StateFlow<Boolean?> = _canFlow.asStateFlow()
    private val _imuOk      = MutableStateFlow<Boolean?>(null)
    val imuOk: StateFlow<Boolean?> = _imuOk.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // Config values read from device on connect
    private val _mqttConfig = MutableStateFlow(MqttConfig())
    val mqttConfig: StateFlow<MqttConfig> = _mqttConfig.asStateFlow()

    private val _deviceWifiSsid = MutableStateFlow("")
    val deviceWifiSsid: StateFlow<String> = _deviceWifiSsid.asStateFlow()

    // Transient result banners (auto-cleared)
    private val _mqttSaveResult = MutableStateFlow<String?>(null)
    val mqttSaveResult: StateFlow<String?> = _mqttSaveResult.asStateFlow()

    private val _wifiSaveResult = MutableStateFlow<String?>(null)
    val wifiSaveResult: StateFlow<String?> = _wifiSaveResult.asStateFlow()

    private val _stagingResult = MutableStateFlow<String?>(null)
    val stagingResult: StateFlow<String?> = _stagingResult.asStateFlow()

    // WiFi scan
    private val _wifiNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val wifiNetworks: StateFlow<List<WifiNetwork>> = _wifiNetworks.asStateFlow()

    private val _wifiScanning = MutableStateFlow(false)
    val wifiScanning: StateFlow<Boolean> = _wifiScanning.asStateFlow()

    private var gatt: BluetoothGatt? = null
    @Volatile private var userDisconnected = false
    private val writeAck = Channel<Boolean>(1)
    private val pendingReads = ArrayDeque<UUID>()

    // ── WiFi scan receiver ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val results = wifiManager.scanResults
                .filter { it.SSID.isNotEmpty() }
                .distinctBy { it.SSID }
                .sortedByDescending { it.level }
                .map { sr ->
                    WifiNetwork(
                        ssid = sr.SSID,
                        rssi = sr.level,
                        secured = sr.capabilities.contains("WPA") ||
                                  sr.capabilities.contains("WEP") ||
                                  sr.capabilities.contains("SAE"),
                    )
                }
            _wifiNetworks.value = results
            _wifiScanning.value = false
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onCleared() {
        super.onCleared()
        @Suppress("MissingPermission")
        gatt?.close()
        try { getApplication<Application>().unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        _scannedDevices.value = emptyList()
        _bleState.value = BleState.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
        viewModelScope.launch {
            delay(15_000)
            if (_bleState.value == BleState.SCANNING) stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_bleState.value == BleState.SCANNING) _bleState.value = BleState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, knownName: String? = null) {
        stopScan()
        gatt?.close()
        userDisconnected = false
        _deviceName.value = knownName ?: device.name ?: device.address
        _bleState.value = BleState.CONNECTING
        gatt = device.connectGatt(getApplication(), false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnected = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _bleState.value = BleState.DISCONNECTED
        _deviceStatus.value = DeviceStatus.UNKNOWN
        _gpsLocked.value = null
        _ppsLocked.value = null
        _canFlow.value = null
        _imuOk.value = null
        _mqttConfig.value = MqttConfig()
        _deviceWifiSsid.value = ""
    }

    fun saveMqttConfig(host: String, port: String, topic: String, user: String, pass: String) {
        val g = gatt ?: return
        val svc = g.getService(BleUuids.SERVICE) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                gattWrite(svc.getCharacteristic(BleUuids.MQTT_HOST), host.toByteArray())
                gattWrite(svc.getCharacteristic(BleUuids.MQTT_PORT), port.toByteArray())
                gattWrite(svc.getCharacteristic(BleUuids.MQTT_TOPIC), topic.toByteArray())
                gattWrite(svc.getCharacteristic(BleUuids.MQTT_USER), user.toByteArray())
                if (pass.isNotBlank()) {
                    gattWrite(svc.getCharacteristic(BleUuids.MQTT_PASS), pass.toByteArray())
                }
                gattWrite(svc.getCharacteristic(BleUuids.COMMIT), byteArrayOf(0x01))
            }.isSuccess
            withContext(Dispatchers.Main) {
                _mqttSaveResult.value = if (ok) "Saved" else "Write failed — check connection"
            }
            delay(3_000)
            withContext(Dispatchers.Main) { _mqttSaveResult.value = null }
        }
    }

    fun saveWifiConfig(ssid: String, pass: String) {
        val g = gatt ?: return
        val svc = g.getService(BleUuids.SERVICE) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                gattWrite(svc.getCharacteristic(BleUuids.WIFI_SSID), ssid.toByteArray())
                if (pass.isNotBlank()) {
                    gattWrite(svc.getCharacteristic(BleUuids.WIFI_PASS), pass.toByteArray())
                }
                gattWrite(svc.getCharacteristic(BleUuids.WIFI_COMMIT), byteArrayOf(0x01))
            }.isSuccess
            withContext(Dispatchers.Main) {
                _wifiSaveResult.value = if (ok) "Sent — device is restarting" else "Write failed — check connection"
            }
            // BLE will drop when device restarts; nav back to scan happens via bleState observer
        }
    }

    fun stagingPush() {
        val g = gatt ?: return
        val svc = g.getService(BleUuids.SERVICE) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                gattWrite(svc.getCharacteristic(BleUuids.STAGING), byteArrayOf(0x01))
            }.isSuccess
            withContext(Dispatchers.Main) {
                _stagingResult.value = if (ok) "Marker sent" else "Failed"
            }
            delay(2_000)
            withContext(Dispatchers.Main) { _stagingResult.value = null }
        }
    }

    @SuppressLint("MissingPermission")
    fun startWifiScan() {
        _wifiScanning.value = true
        _wifiNetworks.value = emptyList()
        wifiManager.startScan()
        // Fallback: if receiver doesn't fire within 10s, stop the spinner
        viewModelScope.launch {
            delay(10_000)
            if (_wifiScanning.value) _wifiScanning.value = false
        }
    }

    // ── BLE scan callback ─────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            @Suppress("MissingPermission")
            // scanRecord.deviceName reads directly from the advertising packet;
            // device.name reads Android's BLE cache which is often null on first scan.
            val name = result.scanRecord?.deviceName ?: device.name ?: "(unnamed)"
            val existing = _scannedDevices.value.toMutableList()
            val idx = existing.indexOfFirst { it.device.address == device.address }
            val entry = ScannedDevice(device, name, result.rssi)
            if (idx >= 0) existing[idx] = entry else existing.add(entry)
            _scannedDevices.value = existing
        }
    }

    // ── BLE GATT callback ─────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _bleState.value = BleState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _bleState.value = BleState.DISCONNECTED
                    _deviceStatus.value = DeviceStatus.UNKNOWN
                    if (!userDisconnected) {
                        viewModelScope.launch {
                            delay(5_000)
                            if (_bleState.value == BleState.DISCONNECTED && !userDisconnected) {
                                val device = gatt.device ?: return@launch
                                connect(device)
                            }
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(BleUuids.SERVICE) ?: return
            val statusChar = svc.getCharacteristic(BleUuids.STATUS) ?: return
            gatt.setCharacteristicNotification(statusChar, true)
            val cccd = statusChar.getDescriptor(BleUuids.CCCD) ?: return
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.characteristic.uuid == BleUuids.STATUS) {
                pendingReads.clear()
                pendingReads.addAll(listOf(
                    BleUuids.MQTT_HOST, BleUuids.MQTT_PORT,
                    BleUuids.MQTT_TOPIC, BleUuids.MQTT_USER,
                    BleUuids.WIFI_SSID,
                ))
                kickNextRead(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleRead(characteristic.uuid, characteristic.value ?: byteArrayOf())
                kickNextRead(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic.uuid, value)
            kickNextRead(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeAck.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNotify(characteristic.uuid, characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(characteristic.uuid, value)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun handleRead(uuid: UUID, value: ByteArray) {
        val str = value.toString(Charsets.UTF_8).trim()
        when (uuid) {
            BleUuids.MQTT_HOST  -> _mqttConfig.value = _mqttConfig.value.copy(host = str)
            BleUuids.MQTT_PORT  -> _mqttConfig.value = _mqttConfig.value.copy(port = str)
            BleUuids.MQTT_TOPIC -> _mqttConfig.value = _mqttConfig.value.copy(topic = str)
            BleUuids.MQTT_USER  -> _mqttConfig.value = _mqttConfig.value.copy(user = str)
            BleUuids.WIFI_SSID  -> _deviceWifiSsid.value = str
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        if (uuid == BleUuids.STATUS && value.isNotEmpty()) {
            _deviceStatus.value = when (value[0].toInt() and 0xFF) {
                0x00 -> DeviceStatus.BOOT
                0x01 -> DeviceStatus.WAITING
                0x02 -> DeviceStatus.READY
                else -> DeviceStatus.UNKNOWN
            }
            if (value.size >= 2) {
                val flags = value[1].toInt() and 0xFF
                _gpsLocked.value = (flags and 0x01) != 0
                _ppsLocked.value = (flags and 0x02) != 0
                _canFlow.value   = (flags and 0x04) != 0
                _imuOk.value     = (flags and 0x08) != 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun kickNextRead(gatt: BluetoothGatt) {
        val next = pendingReads.removeFirstOrNull() ?: return
        val svc = gatt.getService(BleUuids.SERVICE) ?: return
        val char = svc.getCharacteristic(next) ?: return
        gatt.readCharacteristic(char)
    }

    @SuppressLint("MissingPermission")
    private suspend fun gattWrite(char: BluetoothGattCharacteristic, data: ByteArray) {
        val g = gatt ?: error("not connected")
        while (writeAck.tryReceive().isSuccess) {}
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        if (!queued) error("writeCharacteristic returned false")
        val ok = withTimeout(5_000) { writeAck.receive() }
        if (!ok) error("GATT write returned status error")
    }
}
