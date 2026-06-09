package com.robo.phonecompanion.vm

import android.annotation.SuppressLint
import android.app.Application
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
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robo.phonecompanion.data.decoder.SignalDecoder
import com.robo.phonecompanion.data.model.CanFrame
import com.robo.phonecompanion.data.model.Dbc
import com.robo.phonecompanion.data.model.DbcMessage
import com.robo.phonecompanion.data.model.SessionMeta
import com.robo.phonecompanion.data.model.SidecarData
import com.robo.phonecompanion.data.model.VerificationStatus
import com.robo.phonecompanion.data.repository.SessionRepository
import com.robo.phonecompanion.data.repository.SidecarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// ── Public state types ────────────────────────────────────────────────────────

sealed class OtaState {
    object Idle : OtaState()
    data class Uploading(val sent: Int, val total: Int) : OtaState()
    object Verifying : OtaState()
    object Complete : OtaState()
    data class Error(val message: String) : OtaState()
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String, val rssi: Int, val frameRateHz: Float) :
        ConnectionState()
}

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
)

data class DisplayFrame(
    val frame: CanFrame,
    val message: DbcMessage?,
    val decodedSignals: Map<String, Double>,
    val seq: Long,
)

data class MessageState(
    val message: DbcMessage,
    val lastFrame: CanFrame,
    val decodedSignals: Map<String, Double>,
    val updateRateHz: Float,
)

data class UnknownIdState(
    val id: Int,
    val isExtended: Boolean,
    val lastFrame: CanFrame,
    val recentFrames: List<CanFrame>,
    val updateRateHz: Float,
    val triggeredInWindow: Boolean,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class CanBusViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // Connection
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // Frames
    private val _liveFrames = MutableStateFlow<List<DisplayFrame>>(emptyList())
    val liveFrames: StateFlow<List<DisplayFrame>> = _liveFrames.asStateFlow()

    private val _knownMessages = MutableStateFlow<Map<Int, MessageState>>(emptyMap())
    val knownMessages: StateFlow<Map<Int, MessageState>> = _knownMessages.asStateFlow()

    private val _unknownIds = MutableStateFlow<List<UnknownIdState>>(emptyList())
    val unknownIds: StateFlow<List<UnknownIdState>> = _unknownIds.asStateFlow()

    // Context
    private val _activeDbc = MutableStateFlow<Dbc?>(null)
    val activeDbc: StateFlow<Dbc?> = _activeDbc.asStateFlow()

    private val _activeDbcId = MutableStateFlow<String?>(null)
    val activeDbcId: StateFlow<String?> = _activeDbcId.asStateFlow()

    private val _activeSidecar = MutableStateFlow(SidecarData())
    val activeSidecar: StateFlow<SidecarData> = _activeSidecar.asStateFlow()

    // Discovery: trigger marker
    private val _triggerTimestamp = MutableStateFlow<Long?>(null)
    val triggerTimestamp: StateFlow<Long?> = _triggerTimestamp.asStateFlow()

    // Live filter
    private val _showKnownInLive = MutableStateFlow(true)
    val showKnownInLive: StateFlow<Boolean> = _showKnownInLive.asStateFlow()

    private val _showUnknownInLive = MutableStateFlow(true)
    val showUnknownInLive: StateFlow<Boolean> = _showUnknownInLive.asStateFlow()

    // Freeze
    private val _isFrozen = MutableStateFlow(false)
    val isFrozen: StateFlow<Boolean> = _isFrozen.asStateFlow()

    // Vehicle / Recording
    private val _activeVehicleId = MutableStateFlow<String?>(null)
    val activeVehicleId: StateFlow<String?> = _activeVehicleId.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // OTA
    private val _otaState = MutableStateFlow<OtaState>(OtaState.Idle)
    val otaState: StateFlow<OtaState> = _otaState.asStateFlow()

    private val _deviceFirmwareVersion = MutableStateFlow<String?>(null)
    val deviceFirmwareVersion: StateFlow<String?> = _deviceFirmwareVersion.asStateFlow()

    private var otaCtrlChar: BluetoothGattCharacteristic? = null
    private var otaDataChar: BluetoothGattCharacteristic? = null
    private var otaStatusChar: BluetoothGattCharacteristic? = null
    private var otaVersionChar: BluetoothGattCharacteristic? = null

    val isOtaServicePresent: Boolean get() = otaCtrlChar != null

    private val otaWriteAck = Channel<Boolean>(1)
    private val otaStatusChannel = Channel<String>(Channel.UNLIMITED)
    private val otaVersionChannel = Channel<String>(1)

    // Internal
    private val rawFrameChannel = Channel<CanFrame>(Channel.UNLIMITED)
    private val deviceRssiMap = mutableMapOf<String, Int>()
    private val deviceMap = mutableMapOf<String, BluetoothDevice>()
    private var gatt: BluetoothGatt? = null

    @Volatile private var activeSession: SessionRepository.ActiveSession? = null
    private val sessionRepository = SessionRepository(File(application.filesDir, "sessions"))

    // Per-ID tracking for rate and history
    private val idTimestamps = mutableMapOf<Int, ArrayDeque<Long>>()
    private val unknownIdFrames = mutableMapOf<Int, ArrayDeque<CanFrame>>()
    private val unknownIdLastSeen = mutableMapOf<Int, Long>()
    private val liveBuffer = ArrayDeque<DisplayFrame>(LIVE_BUFFER_SIZE + 10)
    private var liveSeq = 0L

    init {
        startFrameProcessor()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setActiveDbc(dbc: Dbc?, sidecar: SidecarData = SidecarData(), id: String? = null) {
        _activeDbc.value = dbc
        _activeSidecar.value = sidecar
        _activeDbcId.value = id
    }

    /** Returns the most recent raw frame data seen for [canId], or null. */
    fun lastFrameData(canId: Int): ByteArray? =
        _knownMessages.value[canId]?.lastFrame?.data
            ?: _unknownIds.value.find { it.id == canId }?.lastFrame?.data

    fun markTrigger() {
        _triggerTimestamp.value = System.currentTimeMillis()
    }

    fun clearTrigger() {
        _triggerTimestamp.value = null
    }

    fun setShowKnown(show: Boolean) { _showKnownInLive.value = show }
    fun setShowUnknown(show: Boolean) { _showUnknownInLive.value = show }
    fun toggleFreeze() { _isFrozen.value = !_isFrozen.value }

    fun setActiveVehicle(id: String?) { _activeVehicleId.value = id }

    fun startRecording(vehicleId: String) {
        if (_isRecording.value) return
        val dbcId = _activeDbcId.value ?: "none"
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val meta = SessionMeta(id = id, vehicleId = vehicleId, dbcId = dbcId, startTime = now)
        viewModelScope.launch(Dispatchers.IO) {
            activeSession = sessionRepository.createSession(meta)
            _currentSessionId.value = id
            _isRecording.value = true
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        val session = activeSession
        activeSession = null
        viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            session?.close(now)
            _currentSessionId.value = null
        }
    }

    fun markSignalVerification(
        signalName: String,
        status: VerificationStatus,
        notes: String,
        sidecarRepo: SidecarRepository,
    ) {
        val vehicleId = _activeVehicleId.value ?: ""
        val sessionId = _currentSessionId.value ?: ""
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        viewModelScope.launch(Dispatchers.IO) {
            sidecarRepo.addVerification(signalName, status, vehicleId, sessionId, now, notes)
            val updated = sidecarRepo.load()
            _activeSidecar.value = updated
        }
    }

    @SuppressLint("MissingPermission")
    fun readDeviceFirmwareVersion() {
        val char = otaVersionChar ?: return
        gatt?.readCharacteristic(char)
    }

    fun resetOtaState() { _otaState.value = OtaState.Idle }

    fun startOta(firmware: ByteArray) {
        val ctrl = otaCtrlChar ?: run {
            _otaState.value = OtaState.Error("OTA service not present — flash via USB first")
            return
        }
        val data = otaDataChar ?: run {
            _otaState.value = OtaState.Error("OTA data characteristic not found")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Drain any stale acks / status
                while (otaWriteAck.tryReceive().isSuccess) {}
                while (otaStatusChannel.tryReceive().isSuccess) {}

                // Send START
                val startCmd = "START:${firmware.size}".toByteArray()
                if (!gattWrite(ctrl, startCmd)) {
                    _otaState.value = OtaState.Error("Failed to send START")
                    return@launch
                }
                val ready = withTimeout(10_000) { otaStatusChannel.receive() }
                if (ready != "READY") {
                    _otaState.value = OtaState.Error("Unexpected response: $ready")
                    return@launch
                }

                // Stream chunks
                val chunkSize = 512
                var offset = 0
                while (offset < firmware.size) {
                    val end = minOf(offset + chunkSize, firmware.size)
                    if (!gattWrite(data, firmware.copyOfRange(offset, end))) {
                        _otaState.value = OtaState.Error("Write failed at offset $offset")
                        return@launch
                    }
                    offset = end
                    _otaState.value = OtaState.Uploading(offset, firmware.size)
                    // Drain any intermediate progress notifications
                    while (otaStatusChannel.tryReceive().isSuccess) {}
                }

                // Commit
                _otaState.value = OtaState.Verifying
                if (!gattWrite(ctrl, "COMMIT".toByteArray())) {
                    _otaState.value = OtaState.Error("Failed to send COMMIT")
                    return@launch
                }
                val result = withTimeout(30_000) { otaStatusChannel.receive() }
                if (result == "OK") {
                    _otaState.value = OtaState.Complete
                } else {
                    _otaState.value = OtaState.Error("Device error: $result")
                }
            } catch (e: TimeoutCancellationException) {
                _otaState.value = OtaState.Error("Timeout — device did not respond")
            } catch (e: Exception) {
                _otaState.value = OtaState.Error(e.message ?: "Unknown error")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun gattWrite(char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        while (otaWriteAck.tryReceive().isSuccess) {} // drain stale
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        if (!queued) return false
        return withTimeout(5_000) { otaWriteAck.receive() }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        deviceMap.clear()
        deviceRssiMap.clear()
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        adapter.bluetoothLeScanner.startScan(scanCallback)
        viewModelScope.launch {
            delay(10_000)
            if (_connectionState.value is ConnectionState.Scanning) stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /** Stop scanning and clear the device list so the picker dialog closes. */
    fun dismissScanDialog() {
        stopScan()
        deviceMap.clear()
        deviceRssiMap.clear()
        _scannedDevices.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        stopScan()
        gatt?.close()
        _connectionState.value = ConnectionState.Connecting(device.name ?: device.address)
        gatt = device.connectGatt(getApplication(), false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun onCleared() {
        super.onCleared()
        @Suppress("MissingPermission")
        gatt?.close()
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    private fun startFrameProcessor() {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                val batch = mutableListOf<CanFrame>()
                while (true) batch.add(rawFrameChannel.tryReceive().getOrNull() ?: break)
                if (batch.isNotEmpty()) processBatch(batch)
            }
        }
    }

    private fun processBatch(frames: List<CanFrame>) {
        val dbc = _activeDbc.value
        val sidecar = _activeSidecar.value
        val now = System.currentTimeMillis()

        val newKnown = _knownMessages.value.toMutableMap()
        val trigger = _triggerTimestamp.value

        for (frame in frames) {
            val timestamps = idTimestamps.getOrPut(frame.id) { ArrayDeque() }
            timestamps.addLast(frame.timestampMs)
            while (timestamps.size > RATE_WINDOW_FRAMES) timestamps.removeFirst()
            val rateHz = computeRate(timestamps, now)
            if (_isRecording.value) activeSession?.appendFrame(frame)

            val message = dbc?.messageForCanId(frame.id)
            if (message != null) {
                val decoded = message.signals.associate { sig ->
                    sig.name to SignalDecoder.decode(sig, frame.data)
                }
                newKnown[frame.id] = MessageState(message, frame, decoded, rateHz)
                val displayFrame = DisplayFrame(frame, message, decoded, liveSeq++)
                liveBuffer.addLast(displayFrame)
            } else {
                if (sidecar.blacklist.contains("0x%03X".format(frame.id))) continue
                val idFrames = unknownIdFrames.getOrPut(frame.id) { ArrayDeque() }
                idFrames.addLast(frame)
                while (idFrames.size > UNKNOWN_HISTORY_SIZE) idFrames.removeFirst()
                unknownIdLastSeen[frame.id] = now

                val displayFrame = DisplayFrame(frame, null, emptyMap(), liveSeq++)
                liveBuffer.addLast(displayFrame)
            }
        }

        // Rebuild unknown state from all persistent entries, aging out stale IDs.
        // This keeps low-frequency signals on screen between batch windows.
        val existingUnknownMap = _unknownIds.value.associateBy { it.id }
        val freshUnknown = mutableListOf<UnknownIdState>()
        val staleIds = mutableListOf<Int>()
        for ((id, idFrames) in unknownIdFrames) {
            val lastSeen = unknownIdLastSeen[id] ?: 0L
            if (now - lastSeen > UNKNOWN_STALE_MS) { staleIds.add(id); continue }
            val lastFrame = idFrames.lastOrNull() ?: continue
            val existing = existingUnknownMap[id]
            val inWindow = trigger != null &&
                kotlin.math.abs(lastFrame.timestampMs - trigger) <= TRIGGER_WINDOW_MS
            freshUnknown.add(UnknownIdState(
                id = id,
                isExtended = lastFrame.isExtended,
                lastFrame = lastFrame,
                recentFrames = idFrames.toList(),
                updateRateHz = computeRate(idTimestamps[id] ?: ArrayDeque(), now),
                triggeredInWindow = inWindow || (existing?.triggeredInWindow == true),
            ))
        }
        staleIds.forEach { id -> unknownIdFrames.remove(id); unknownIdLastSeen.remove(id) }

        while (liveBuffer.size > LIVE_BUFFER_SIZE) liveBuffer.removeFirst()

        if (!_isFrozen.value) {
            viewModelScope.launch(Dispatchers.Main) {
                _knownMessages.value = newKnown
                _unknownIds.value = freshUnknown.sortedByDescending { it.updateRateHz }
                _liveFrames.value = liveBuffer.toList()
                updateConnectionFrameRate()
            }
        }
    }

    private fun updateConnectionFrameRate() {
        val state = _connectionState.value
        if (state is ConnectionState.Connected) {
            val now = System.currentTimeMillis()
            val allTs = idTimestamps.values.flatten()
            val recent = allTs.count { now - it < 1000 }.toFloat()
            _connectionState.value = state.copy(frameRateHz = recent)
        }
    }

    private fun computeRate(timestamps: ArrayDeque<Long>, now: Long): Float {
        val window = timestamps.count { now - it < 1000 }
        return window.toFloat()
    }

    // ── BLE callbacks ─────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            deviceMap[device.address] = device
            deviceRssiMap[device.address] = result.rssi
            @Suppress("MissingPermission")
            _scannedDevices.value = deviceMap.values.map { d ->
                ScannedDevice(d, d.name ?: "(unnamed)", deviceRssiMap[d.address] ?: 0)
            }.sortedByDescending { it.rssi }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    val name = gatt.device.name ?: gatt.device.address
                    _connectionState.value = ConnectionState.Connected(name, 0, 0f)
                    gatt.discoverServices()
                    scheduleRssiRead()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // NUS — enable TX notifications
            val nusSvc = gatt.getService(UART_SERVICE_UUID)
            val tx = nusSvc?.getCharacteristic(UART_TX_UUID)
            if (tx != null && gatt.setCharacteristicNotification(tx, true)) {
                val cccd = tx.getDescriptor(CCCD_UUID)
                @Suppress("DEPRECATION")
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                cccd?.let { gatt.writeDescriptor(it) }
            }
            // OTA service — discover characteristics (optional, only present in OTA firmware)
            val otaSvc = gatt.getService(OTA_SERVICE_UUID)
            otaCtrlChar   = otaSvc?.getCharacteristic(OTA_CTRL_UUID)
            otaDataChar   = otaSvc?.getCharacteristic(OTA_DATA_UUID)
            otaStatusChar = otaSvc?.getCharacteristic(OTA_STATUS_UUID)
            otaVersionChar = otaSvc?.getCharacteristic(OTA_VERSION_UUID)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // After the NUS CCCD is written, chain the OTA status CCCD if available
            if (descriptor.characteristic.uuid == UART_TX_UUID) {
                val statusChar = otaStatusChar ?: return
                if (!gatt.setCharacteristicNotification(statusChar, true)) return
                val cccd = statusChar.getDescriptor(CCCD_UUID) ?: return
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == OTA_CTRL_UUID || characteristic.uuid == OTA_DATA_UUID) {
                otaWriteAck.trySend(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == OTA_VERSION_UUID &&
                status == BluetoothGatt.GATT_SUCCESS) {
                _deviceFirmwareVersion.value = characteristic.value
                    ?.toString(Charsets.UTF_8)?.trim()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid == OTA_VERSION_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                _deviceFirmwareVersion.value = value.toString(Charsets.UTF_8).trim()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val state = _connectionState.value
            if (state is ConnectionState.Connected) {
                _connectionState.value = state.copy(rssi = rssi)
            }
            scheduleRssiRead()
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
            when (uuid) {
                UART_TX_UUID -> {
                    val payload = value?.toString(Charsets.UTF_8).orEmpty().trim()
                    if (payload.isEmpty()) return
                    val now = System.currentTimeMillis()
                    payload.lines().forEach { line ->
                        parseFrameLine(line, now)?.let { rawFrameChannel.trySend(it) }
                    }
                }
                OTA_STATUS_UUID -> {
                    val msg = value?.toString(Charsets.UTF_8)?.trim() ?: return
                    otaStatusChannel.trySend(msg)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleRssiRead() {
        viewModelScope.launch {
            delay(RSSI_INTERVAL_MS)
            if (_connectionState.value is ConnectionState.Connected) {
                gatt?.readRemoteRssi()
            }
        }
    }

    // ── Frame parsing ─────────────────────────────────────────────────────────

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
            CanFrame(timestampMs, id, isExtended, data)
        }.getOrNull()
    }

    companion object {
        private val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_UUID: UUID     = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID: UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val OTA_SERVICE_UUID: UUID = UUID.fromString("6E410001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_CTRL_UUID: UUID    = UUID.fromString("6E410002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_DATA_UUID: UUID    = UUID.fromString("6E410003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_STATUS_UUID: UUID  = UUID.fromString("6E410004-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_VERSION_UUID: UUID = UUID.fromString("6E410005-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val FLUSH_INTERVAL_MS = 100L
        private const val RSSI_INTERVAL_MS = 2000L
        private const val LIVE_BUFFER_SIZE = 200
        private const val RATE_WINDOW_FRAMES = 60
        private const val UNKNOWN_HISTORY_SIZE = 20
        private const val UNKNOWN_STALE_MS = 10_000L
        private const val TRIGGER_WINDOW_MS = 2000L
    }
}
