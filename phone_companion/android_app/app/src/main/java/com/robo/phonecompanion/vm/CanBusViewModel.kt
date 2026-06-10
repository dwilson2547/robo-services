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
import com.robo.phonecompanion.data.obd2.Obd2PidTable
import com.robo.phonecompanion.data.repository.SessionRepository
import com.robo.phonecompanion.data.repository.SidecarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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
    val recentFrames: List<CanFrame> = emptyList(),
)

data class UnknownIdState(
    val id: Int,
    val isExtended: Boolean,
    val lastFrame: CanFrame,
    val recentFrames: List<CanFrame>,
    val updateRateHz: Float,
    val triggeredInWindow: Boolean,
)

data class CanStats(
    val framesProcessed: Long = 0,
    val decodeOutOfRangeEvents: Long = 0,
    val parseErrors: Long = 0,
    val bleNotificationsReceived: Long = 0,
)

data class SignalHealth(
    val isStuck: Boolean = false,
    val isPegged: Boolean = false,
)

data class OdbCrossRef(
    val signalKey: String,   // "msgName/sigName"
    val pid: Int,
    val pidName: String,
    val correlation: Float,  // Pearson r (positive = correlated, negative = inverse)
    val sampleCount: Int,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class CanBusViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("ble_prefs", Context.MODE_PRIVATE)

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

    private val _showDiagInLive = MutableStateFlow(true)
    val showDiagInLive: StateFlow<Boolean> = _showDiagInLive.asStateFlow()

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

    // Last known device (persisted across restarts)
    private val _lastKnownDevice = MutableStateFlow<Pair<String, String>?>(null)
    val lastKnownDevice: StateFlow<Pair<String, String>?> = _lastKnownDevice.asStateFlow()

    // Telemetry
    private val _canStats = MutableStateFlow(CanStats())
    val canStats: StateFlow<CanStats> = _canStats.asStateFlow()

    private val _signalHealth = MutableStateFlow<Map<String, SignalHealth>>(emptyMap())
    val signalHealth: StateFlow<Map<String, SignalHealth>> = _signalHealth.asStateFlow()

    // OBD-II cross-reference (keyed by "msgName/sigName")
    private val _obdCrossRefs = MutableStateFlow<Map<String, OdbCrossRef>>(emptyMap())
    val obdCrossRefs: StateFlow<Map<String, OdbCrossRef>> = _obdCrossRefs.asStateFlow()

    // Signal graph
    private val _pinnedSignalKeys = MutableStateFlow<List<String>>(emptyList())
    val pinnedSignalKeys: StateFlow<List<String>> = _pinnedSignalKeys.asStateFlow()

    private val _signalSeries = MutableStateFlow<Map<String, List<Pair<Long, Double>>>>(emptyMap())
    val signalSeries: StateFlow<Map<String, List<Pair<Long, Double>>>> = _signalSeries.asStateFlow()

    private val _thresholds = MutableStateFlow<Map<String, Double>>(emptyMap())
    val thresholds: StateFlow<Map<String, Double>> = _thresholds.asStateFlow()

    private val _thresholdAlerts = MutableSharedFlow<Pair<String, Double>>(extraBufferCapacity = 8)
    val thresholdAlerts: SharedFlow<Pair<String, Double>> = _thresholdAlerts.asSharedFlow()

    // OTA
    private val _otaState = MutableStateFlow<OtaState>(OtaState.Idle)
    val otaState: StateFlow<OtaState> = _otaState.asStateFlow()

    private val _deviceFirmwareVersion = MutableStateFlow<String?>(null)
    val deviceFirmwareVersion: StateFlow<String?> = _deviceFirmwareVersion.asStateFlow()

    // Negotiated ATT MTU (updated in onMtuChanged; default covers the 23-byte baseline)
    private var negotiatedMtu: Int = 23

    // TX mode control (NUS RX write characteristic)
    private val _txEnabled = MutableStateFlow(false)
    val txEnabled: StateFlow<Boolean> = _txEnabled.asStateFlow()

    // Baud rate (persisted; sent to firmware via NUS RX on change)
    private val _activeBaudRate = MutableStateFlow(
        prefs.getInt(PREF_BAUD_RATE, 500_000)
    )
    val activeBaudRate: StateFlow<Int> = _activeBaudRate.asStateFlow()

    private var nusCmdChar: BluetoothGattCharacteristic? = null

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

    @Volatile private var userInitiatedDisconnect = false
    private var reconnectAttempts = 0

    // Thread-safe telemetry accumulators (written from BLE/Default threads, read on Main)
    private val atomicNotifications = AtomicLong(0)
    private val atomicParseErrors = AtomicLong(0)

    @Volatile private var activeSession: SessionRepository.ActiveSession? = null
    private val sessionRepository = SessionRepository(File(application.filesDir, "sessions"))

    // Per-ID tracking for rate and history
    private val idTimestamps = mutableMapOf<Int, ArrayDeque<Long>>()
    private val unknownIdFrames = mutableMapOf<Int, ArrayDeque<CanFrame>>()
    private val knownIdFrames = mutableMapOf<Int, ArrayDeque<CanFrame>>()
    private val signalValueHistory = mutableMapOf<String, ArrayDeque<Double>>()
    private val unknownIdLastSeen = mutableMapOf<Int, Long>()
    private val liveBuffer = ArrayDeque<DisplayFrame>(LIVE_BUFFER_SIZE + 10)
    private var liveSeq = 0L

    // OBD cross-ref sample accumulators: key = (pid, "msgName/sigName")
    private val crossRefObdSamples    = mutableMapOf<Pair<Int, String>, ArrayDeque<Float>>()
    private val crossRefNativeSamples = mutableMapOf<Pair<Int, String>, ArrayDeque<Float>>()

    // Graph series data and threshold side tracking (frame-processor thread only)
    private val signalSeriesData = mutableMapOf<String, ArrayDeque<Pair<Long, Double>>>()
    private val thresholdSide    = mutableMapOf<String, Boolean>()

    init {
        val savedAddr = prefs.getString(PREF_LAST_ADDR, null)
        val savedName = prefs.getString(PREF_LAST_NAME, null)
        if (savedAddr != null && savedName != null) {
            _lastKnownDevice.value = savedAddr to savedName
        }
        startFrameProcessor()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setActiveDbc(dbc: Dbc?, sidecar: SidecarData = SidecarData(), id: String? = null) {
        _activeDbc.value = dbc
        _activeSidecar.value = sidecar
        _activeDbcId.value = id
        crossRefObdSamples.clear()
        crossRefNativeSamples.clear()
        _obdCrossRefs.value = emptyMap()
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
    fun setShowDiag(show: Boolean) { _showDiagInLive.value = show }
    fun toggleFreeze() { _isFrozen.value = !_isFrozen.value }

    fun pinSignal(key: String) {
        val current = _pinnedSignalKeys.value
        if (key !in current && current.size < MAX_PINNED_SIGNALS)
            _pinnedSignalKeys.value = current + key
    }

    fun unpinSignal(key: String) {
        _pinnedSignalKeys.value = _pinnedSignalKeys.value - key
        _signalSeries.value = _signalSeries.value - key
        // signalSeriesData / thresholdSide cleaned via retainAll in processBatch
    }

    fun setThreshold(key: String, value: Double) {
        _thresholds.value = _thresholds.value + (key to value)
    }

    fun clearThreshold(key: String) {
        _thresholds.value = _thresholds.value - key
    }

    @SuppressLint("MissingPermission")
    fun enableTx() {
        val char = nusCmdChar ?: return
        writeNusCommand(char, "TX_ENABLE")
        _txEnabled.value = true
    }

    @SuppressLint("MissingPermission")
    fun disableTx() {
        val char = nusCmdChar ?: return
        writeNusCommand(char, "TX_DISABLE")
        _txEnabled.value = false
    }

    @SuppressLint("MissingPermission")
    fun setBaudRate(baud: Int) {
        val char = nusCmdChar ?: return
        if (baud !in SUPPORTED_BAUD_RATES) return
        writeNusCommand(char, "BAUD:$baud")
        _activeBaudRate.value = baud
        prefs.edit().putInt(PREF_BAUD_RATE, baud).apply()
    }

    @SuppressLint("MissingPermission")
    private fun writeNusCommand(char: BluetoothGattCharacteristic, cmd: String) {
        val g = gatt ?: return
        val bytes = cmd.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    fun setActiveVehicle(id: String?) { _activeVehicleId.value = id }

    @SuppressLint("MissingPermission")
    fun reconnectToLastDevice() {
        val (address, _) = _lastKnownDevice.value ?: return
        if (_connectionState.value !is ConnectionState.Disconnected) return
        val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrNull() ?: return
        connectDevice(device)
    }

    fun startRecording(vehicleId: String, notes: String = "") {
        if (_isRecording.value) return
        val dbcId = _activeDbcId.value ?: "none"
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val meta = SessionMeta(id = id, vehicleId = vehicleId, dbcId = dbcId,
            startTime = now, notes = notes)
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

    fun deleteSignal(rawId: Int, signalName: String, settingsVm: SettingsViewModel) {
        val dbc = _activeDbc.value ?: return
        val dbcId = _activeDbcId.value ?: return
        val msg = dbc.messages[rawId] ?: return
        val updated = msg.copy(signals = msg.signals.filterNot { it.name == signalName })
        val newMessages = dbc.messages.toMutableMap()
        newMessages[rawId] = updated
        val updatedDbc = dbc.copy(messages = newMessages)
        settingsVm.dbcRepository.save(dbcId, updatedDbc)
        val sidecar = settingsVm.dbcRepository.sidecarFor(dbcId).load()
        setActiveDbc(updatedDbc, sidecar, dbcId)
    }

    fun deleteMessage(rawId: Int, settingsVm: SettingsViewModel) {
        val dbc = _activeDbc.value ?: return
        val dbcId = _activeDbcId.value ?: return
        val newMessages = dbc.messages.toMutableMap()
        newMessages.remove(rawId)
        val updatedDbc = dbc.copy(messages = newMessages)
        settingsVm.dbcRepository.save(dbcId, updatedDbc)
        val sidecar = settingsVm.dbcRepository.sidecarFor(dbcId).load()
        setActiveDbc(updatedDbc, sidecar, dbcId)
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

                // Stream chunks — cap at negotiated ATT_MTU minus 3 bytes overhead
                val chunkSize = (negotiatedMtu - 3).coerceIn(20, 512)
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
                // Firmware sends "VERIFYING" then "OK" (or "ERROR:…") — skip intermediates
                var result: String
                do { result = withTimeout(30_000) { otaStatusChannel.receive() } }
                while (result == "VERIFYING" || result.startsWith("PROGRESS:"))
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
        userInitiatedDisconnect = false
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Connecting(device.name ?: device.address)
        gatt = device.connectGatt(getApplication(), false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        nusCmdChar = null
        _txEnabled.value = false
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

        var batchFrameCount = 0L
        var batchOutOfRange = 0L

        val pinnedNow = _pinnedSignalKeys.value.toSet()
        signalSeriesData.keys.retainAll(pinnedNow)
        thresholdSide.keys.retainAll(pinnedNow)
        val crossings = mutableListOf<Pair<String, Double>>()

        for (frame in frames) {
            val timestamps = idTimestamps.getOrPut(frame.id) { ArrayDeque() }
            timestamps.addLast(frame.timestampMs)
            while (timestamps.size > RATE_WINDOW_FRAMES) timestamps.removeFirst()
            val rateHz = computeRate(timestamps, now)
            if (_isRecording.value) activeSession?.appendFrame(frame)
            batchFrameCount++

            val message = dbc?.messageForCanId(frame.id)
            if (message != null) {
                // Mux-aware decoding: find selector signal, restrict muxed signals to active slot
                val muxSelector = message.signals.find { it.muxIndicator == "M" }
                val activeMuxSlot = muxSelector?.let {
                    SignalDecoder.decodeOrNull(it, frame.data)?.toLong()?.toInt()
                }

                val decoded = message.signals.mapNotNull { sig ->
                    val indicator = sig.muxIndicator
                    if (indicator != null && indicator != "M") {
                        // This is a muxed signal — skip if slot doesn't match
                        val slot = indicator.removePrefix("m").toIntOrNull()
                        if (activeMuxSlot != null && slot != activeMuxSlot) return@mapNotNull null
                    }
                    val v = SignalDecoder.decodeOrNull(sig, frame.data)
                    if (v == null) { batchOutOfRange++; null } else sig.name to v
                }.toMap()

                val knownFrames = knownIdFrames.getOrPut(frame.id) { ArrayDeque() }
                knownFrames.addLast(frame)
                while (knownFrames.size > UNKNOWN_HISTORY_SIZE) knownFrames.removeFirst()
                newKnown[frame.id] = MessageState(message, frame, decoded, rateHz,
                    recentFrames = knownFrames.toList())
                for (sig in message.signals) {
                    val v = decoded[sig.name] ?: continue
                    val hist = signalValueHistory.getOrPut(sig.name) { ArrayDeque() }
                    hist.addLast(v)
                    while (hist.size > SIGNAL_HEALTH_WINDOW) hist.removeFirst()
                    val graphKey = "${message.name}/${sig.name}"
                    if (graphKey in pinnedNow) {
                        val q = signalSeriesData.getOrPut(graphKey) { ArrayDeque() }
                        q.addLast(frame.timestampMs to v)
                        while (q.size > GRAPH_BUFFER_SLOTS) q.removeFirst()
                        while (q.isNotEmpty() && frame.timestampMs - q.first().first > GRAPH_MAX_WINDOW_MS) q.removeFirst()
                    }
                    val threshold = _thresholds.value[graphKey]
                    if (threshold != null) {
                        val isAbove = v >= threshold
                        val wasAbove = thresholdSide[graphKey]
                        if (wasAbove != null && wasAbove != isAbove) crossings.add(graphKey to v)
                        thresholdSide[graphKey] = isAbove
                    }
                }
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

        // Compute signal health flags (stuck / pegged) from accumulated history
        val signalDefs = dbc?.messages?.values
            ?.flatMap { it.signals }
            ?.associateBy { it.name }
        val newHealth = mutableMapOf<String, SignalHealth>()
        for ((sigName, hist) in signalValueHistory) {
            if (hist.size < SIGNAL_HEALTH_WINDOW) continue
            val sig = signalDefs?.get(sigName)
            val isStuck = hist.all { it == hist.first() }
            val isPegged = sig != null && sig.min < sig.max &&
                (hist.all { it <= sig.min } || hist.all { it >= sig.max })
            if (isStuck || isPegged) newHealth[sigName] = SignalHealth(isStuck, isPegged)
        }

        // Snapshot telemetry counters for the stats update
        val notifCount = atomicNotifications.get()
        val parseErrCount = atomicParseErrors.get()
        val currentStats = _canStats.value
        val newStats = currentStats.copy(
            framesProcessed = currentStats.framesProcessed + batchFrameCount,
            decodeOutOfRangeEvents = currentStats.decodeOutOfRangeEvents + batchOutOfRange,
            parseErrors = parseErrCount,
            bleNotificationsReceived = notifCount,
        )

        // ── OBD-II cross-reference: accumulate samples + compute Pearson r ──────
        var crossRefsChanged = false
        for (frame in frames) {
            if (!isObd2Diagnostic(frame.id)) continue
            val d = frame.data
            if (d.size < 3) continue
            if ((d[1].toInt() and 0xFF) != 0x41) continue  // Mode 01 responses only
            val pid = d[2].toInt() and 0xFF
            val pidEntry = Obd2PidTable.lookup(pid) ?: continue
            val len = (d[0].toInt() and 0xFF).coerceAtLeast(2)
            val dataEnd = minOf(3 + len - 2, d.size)
            if (dataEnd <= 3) continue
            val valueBytes = d.copyOfRange(3, dataEnd)
            if (valueBytes.size < pidEntry.minBytes) continue
            val obdValue = runCatching { pidEntry.decode(valueBytes).toFloat() }.getOrNull() ?: continue

            for ((_, msgState) in newKnown) {
                val msgName = msgState.message.name
                for ((sigName, nativeValue) in msgState.decodedSignals) {
                    val key = pid to "$msgName/$sigName"
                    val obdQ = crossRefObdSamples.getOrPut(key) { ArrayDeque() }
                    val natQ = crossRefNativeSamples.getOrPut(key) { ArrayDeque() }
                    obdQ.addLast(obdValue)
                    natQ.addLast(nativeValue.toFloat())
                    if (obdQ.size > CROSS_REF_WINDOW) { obdQ.removeFirst(); natQ.removeFirst() }
                    crossRefsChanged = true
                }
            }
        }

        val updatedCrossRefs: Map<String, OdbCrossRef>? = if (crossRefsChanged) {
            val map = _obdCrossRefs.value.toMutableMap()
            for ((key, obdQ) in crossRefObdSamples) {
                val (pid, sigKey) = key
                val natQ = crossRefNativeSamples[key] ?: continue
                if (obdQ.size < CROSS_REF_MIN_SAMPLES) continue
                val r = pearson(obdQ.toList(), natQ.toList())
                if (kotlin.math.abs(r) >= CROSS_REF_THRESHOLD) {
                    val pidEntry = Obd2PidTable.lookup(pid) ?: continue
                    val existing = map[sigKey]
                    if (existing == null || kotlin.math.abs(r) > kotlin.math.abs(existing.correlation)) {
                        map[sigKey] = OdbCrossRef(sigKey, pid, pidEntry.name, r, obdQ.size)
                    }
                } else {
                    map.remove(sigKey)
                }
            }
            map
        } else null

        // Threshold alerts and cross-refs emit unconditionally (not subject to freeze)
        if (crossings.isNotEmpty()) {
            val c = crossings.toList()
            viewModelScope.launch(Dispatchers.Main) { c.forEach { _thresholdAlerts.tryEmit(it) } }
        }
        updatedCrossRefs?.let { refs ->
            viewModelScope.launch(Dispatchers.Main) { _obdCrossRefs.value = refs }
        }
        if (!_isFrozen.value) {
            val knownSnapshot = newKnown
            val unknownSnapshot = freshUnknown.sortedByDescending { it.updateRateHz }
            val liveSnapshot = liveBuffer.toList()
            val healthSnapshot = newHealth
            val graphSnapshot = if (pinnedNow.isNotEmpty())
                pinnedNow.associateWith { key -> signalSeriesData[key]?.toList() ?: emptyList() }
            else null
            viewModelScope.launch(Dispatchers.Main) {
                _knownMessages.value = knownSnapshot
                _unknownIds.value = unknownSnapshot
                _liveFrames.value = liveSnapshot
                _canStats.value = newStats
                _signalHealth.value = healthSnapshot
                if (graphSnapshot != null) _signalSeries.value = graphSnapshot
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

    private fun pearson(xs: List<Float>, ys: List<Float>): Float {
        val n = xs.size
        if (n < 3) return 0f
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var num = 0f; var sx = 0f; var sy = 0f
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            num += dx * dy; sx += dx * dx; sy += dy * dy
        }
        val denom = kotlin.math.sqrt(sx) * kotlin.math.sqrt(sy)
        return if (denom < 1e-6f) 0f else num / denom
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
                    reconnectAttempts = 0
                    prefs.edit()
                        .putString(PREF_LAST_ADDR, gatt.device.address)
                        .putString(PREF_LAST_NAME, name)
                        .apply()
                    _lastKnownDevice.value = gatt.device.address to name
                    // Request max ATT MTU before service discovery so OTA chunk size is known.
                    // If requestMtu() returns false the stack is busy; fall through to discover.
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                    scheduleRssiRead()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    nusCmdChar = null
                    _txEnabled.value = false
                    crossRefObdSamples.clear()
                    crossRefNativeSamples.clear()
                    if (!userInitiatedDisconnect) scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // NUS — enable TX notifications and capture the RX write characteristic
            val nusSvc = gatt.getService(UART_SERVICE_UUID)
            val tx = nusSvc?.getCharacteristic(UART_TX_UUID)
            if (tx != null && gatt.setCharacteristicNotification(tx, true)) {
                val cccd = tx.getDescriptor(CCCD_UUID)
                @Suppress("DEPRECATION")
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                cccd?.let { gatt.writeDescriptor(it) }
            }
            nusCmdChar = nusSvc?.getCharacteristic(UART_RX_UUID)
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
                    atomicNotifications.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val rawFrames = payload.lines().mapNotNull { line ->
                        parseFrameLine(line, now).also { if (it == null && line.isNotBlank()) atomicParseErrors.incrementAndGet() }
                    }
                    // Adjust intra-packet timestamps using firmware capture offsets if available
                    val maxFwTs = rawFrames.mapNotNull { it.firmwareTimestampMs }.maxOrNull()
                    rawFrames.forEach { frame ->
                        val adjusted = if (frame.firmwareTimestampMs != null && maxFwTs != null) {
                            val delta = ((maxFwTs - frame.firmwareTimestampMs) + 65536L) % 65536L
                            frame.copy(timestampMs = now - delta, firmwareTimestampMs = null)
                        } else frame
                        rawFrameChannel.trySend(adjusted)
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
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        val delayMs = if (reconnectAttempts == 0) 3_000L else 15_000L
        reconnectAttempts++
        viewModelScope.launch {
            delay(delayMs)
            if (_connectionState.value is ConnectionState.Disconnected && !userInitiatedDisconnect) {
                val (address, _) = _lastKnownDevice.value ?: return@launch
                val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrNull() ?: return@launch
                connectDevice(device)
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
            // Optional firmware capture timestamp at index 3+dlc (millis mod 65536)
            val fwTs = parts.getOrNull(3 + dlc)?.trim()?.toLongOrNull()
            CanFrame(timestampMs, id, isExtended, data, firmwareTimestampMs = fwTs)
        }.getOrNull()
    }

    companion object {
        private const val PREF_LAST_ADDR  = "last_ble_addr"
        private const val PREF_LAST_NAME  = "last_ble_name"
        private const val PREF_BAUD_RATE  = "can_baud_rate"
        val SUPPORTED_BAUD_RATES = listOf(125_000, 250_000, 500_000, 1_000_000)
        private const val MAX_RECONNECT_ATTEMPTS = 3

        private val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_UUID: UUID     = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_RX_UUID: UUID     = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
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
        private const val SIGNAL_HEALTH_WINDOW = 50
        private const val CROSS_REF_WINDOW = 30
        private const val CROSS_REF_MIN_SAMPLES = 10
        private const val CROSS_REF_THRESHOLD = 0.85f
        const val MAX_PINNED_SIGNALS = 4
        private const val GRAPH_MAX_WINDOW_MS = 60_000L
        private const val GRAPH_BUFFER_SLOTS = 6_000

        fun isObd2Diagnostic(id: Int): Boolean = id == 0x7DF || id in 0x7E0..0x7EF
    }
}
