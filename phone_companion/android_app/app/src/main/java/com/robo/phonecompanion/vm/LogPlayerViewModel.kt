package com.robo.phonecompanion.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robo.phonecompanion.data.decoder.SignalDecoder
import com.robo.phonecompanion.data.model.CanFrame
import com.robo.phonecompanion.data.model.SessionMeta
import com.robo.phonecompanion.data.repository.DbcRepository
import com.robo.phonecompanion.data.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class SignalDiff(
    val signalName: String,
    val messageName: String,
    val valueAtA: Double?,
    val valueAtB: Double?,
) {
    val delta: Double? get() =
        if (valueAtA != null && valueAtB != null) valueAtB - valueAtA else null
}

data class SparklineSeries(
    val key: String,        // "$msgName/$sigName"
    val signalName: String,
    val messageName: String,
    val points: FloatArray, // normalized [0,1] Y values at evenly-spaced time slots
)

class LogPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionRepository = SessionRepository(File(application.filesDir, "sessions"))
    private val dbcRepository = DbcRepository(File(application.filesDir, "git_repo/dbcs"))

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _meta = MutableStateFlow<SessionMeta?>(null)
    val meta: StateFlow<SessionMeta?> = _meta.asStateFlow()

    private val _allFrames = MutableStateFlow<List<DisplayFrame>>(emptyList())
    val allFrames: StateFlow<List<DisplayFrame>> = _allFrames.asStateFlow()

    private val _showKnown = MutableStateFlow(true)
    val showKnown: StateFlow<Boolean> = _showKnown.asStateFlow()

    private val _showUnknown = MutableStateFlow(true)
    val showUnknown: StateFlow<Boolean> = _showUnknown.asStateFlow()

    // Phase 6.1 — Timeline range scrubber (normalised [0, 1])
    private val _rangeStart = MutableStateFlow(0f)
    val rangeStart: StateFlow<Float> = _rangeStart.asStateFlow()

    private val _rangeEnd = MutableStateFlow(1f)
    val rangeEnd: StateFlow<Float> = _rangeEnd.asStateFlow()

    // Phase 6.2 — Sparklines (one per decoded signal, 200-slot time series)
    private val _sparklines = MutableStateFlow<List<SparklineSeries>>(emptyList())
    val sparklines: StateFlow<List<SparklineSeries>> = _sparklines.asStateFlow()

    // Phase 6.3 — Reference event anchor
    private val _referenceTs = MutableStateFlow<Long?>(null)
    val referenceTs: StateFlow<Long?> = _referenceTs.asStateFlow()

    // Phase 6.4 — Stimulus-response diff
    private val _bookmarkA = MutableStateFlow<Long?>(null)
    val bookmarkA: StateFlow<Long?> = _bookmarkA.asStateFlow()

    private val _bookmarkB = MutableStateFlow<Long?>(null)
    val bookmarkB: StateFlow<Long?> = _bookmarkB.asStateFlow()

    private val _diffResult = MutableStateFlow<List<SignalDiff>>(emptyList())
    val diffResult: StateFlow<List<SignalDiff>> = _diffResult.asStateFlow()

    // Phase 6.5 — Multi-signal correlation: up to 4 selected signal keys
    private val _selectedSignalKeys = MutableStateFlow<List<String>>(emptyList())
    val selectedSignalKeys: StateFlow<List<String>> = _selectedSignalKeys.asStateFlow()

    // Phase 6.6 — Playback
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var playbackJob: Job? = null

    // visibleFrames: range filter → known/unknown filter
    val visibleFrames: StateFlow<List<DisplayFrame>> =
        combine(
            combine(_allFrames, _rangeStart, _rangeEnd) { frames, start, end ->
                if (frames.isEmpty()) emptyList()
                else {
                    val minTs = frames.first().frame.timestampMs
                    val maxTs = frames.last().frame.timestampMs
                    val dur = (maxTs - minTs).coerceAtLeast(1L)
                    val tStart = minTs + (dur * start).toLong()
                    val tEnd = minTs + (dur * end).toLong()
                    frames.filter { df -> df.frame.timestampMs in tStart..tEnd }
                }
            },
            _showKnown,
            _showUnknown,
        ) { rangedFrames, known, unknown ->
            rangedFrames.filter { if (it.message != null) known else unknown }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Public API ────────────────────────────────────────────────────────────

    fun toggleShowKnown() { _showKnown.value = !_showKnown.value }
    fun toggleShowUnknown() { _showUnknown.value = !_showUnknown.value }

    fun setRange(start: Float, end: Float) {
        _rangeStart.value = start.coerceIn(0f, 1f)
        _rangeEnd.value = end.coerceIn(0f, 1f)
    }

    fun setReference(ts: Long) { _referenceTs.value = ts }
    fun clearReference() { _referenceTs.value = null }

    fun setBookmarkA(ts: Long) { _bookmarkA.value = ts; recomputeDiff() }
    fun setBookmarkB(ts: Long) { _bookmarkB.value = ts; recomputeDiff() }
    fun clearBookmarkA() { _bookmarkA.value = null; _diffResult.value = emptyList() }
    fun clearBookmarkB() { _bookmarkB.value = null; _diffResult.value = emptyList() }
    fun clearDiff() { _bookmarkA.value = null; _bookmarkB.value = null; _diffResult.value = emptyList() }

    fun toggleSignalSelection(key: String) {
        val current = _selectedSignalKeys.value
        _selectedSignalKeys.value = if (current.contains(key)) current - key
        else if (current.size < MAX_CORRELATION_SIGNALS) current + key
        else current
    }

    fun clearSignalSelection() { _selectedSignalKeys.value = emptyList() }

    fun startPlayback() {
        val frames = _allFrames.value
        if (frames.size < 2) return
        val sessionMinTs = frames.first().frame.timestampMs
        val sessionDur = (frames.last().frame.timestampMs - sessionMinTs).coerceAtLeast(1L).toFloat()

        stopPlayback()
        // Begin cursor at current rangeEnd; if at the end, restart from rangeStart
        var cursorMs = if (_rangeEnd.value >= 0.999f) {
            sessionMinTs + (sessionDur * _rangeStart.value).toLong()
        } else {
            sessionMinTs + (sessionDur * _rangeEnd.value).toLong()
        }

        _isPlaying.value = true
        playbackJob = viewModelScope.launch {
            var lastWallMs = System.currentTimeMillis()
            while (isActive) {
                delay(16)
                val now = System.currentTimeMillis()
                val wallElapsed = now - lastWallMs
                lastWallMs = now
                cursorMs += (wallElapsed * _playbackSpeed.value).toLong()
                val newPos = ((cursorMs - sessionMinTs) / sessionDur).coerceIn(0f, 1f)
                _rangeEnd.value = newPos
                if (newPos >= 1f) {
                    _isPlaying.value = false
                    break
                }
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
    }

    fun setPlaybackSpeed(speed: Float) { _playbackSpeed.value = speed }

    fun loadSession(sessionId: String) {
        if (_meta.value?.id == sessionId) return
        stopPlayback()
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _allFrames.value = emptyList()
            _sparklines.value = emptyList()
            _selectedSignalKeys.value = emptyList()
            _rangeStart.value = 0f
            _rangeEnd.value = 1f
            _referenceTs.value = null
            _bookmarkA.value = null
            _bookmarkB.value = null
            _diffResult.value = emptyList()

            val meta = sessionRepository.loadMeta(sessionId) ?: run {
                _isLoading.value = false
                return@launch
            }
            _meta.value = meta

            val dbc = if (meta.dbcId != "none")
                runCatching { dbcRepository.load(meta.dbcId) }.getOrNull()
            else null
            val framesFile = sessionRepository.framesFile(sessionId)
            if (!framesFile.exists()) { _isLoading.value = false; return@launch }

            val result = mutableListOf<DisplayFrame>()
            var seq = 0L

            framesFile.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size < 5) return@forEachLine
                val ts = parts[0].toLongOrNull() ?: return@forEachLine
                val id = parts[1].removePrefix("0x").toIntOrNull(16) ?: return@forEachLine
                val ext = parts[2] == "1"
                val dlc = parts[3].toIntOrNull() ?: return@forEachLine
                val hex = parts[4].trimEnd()
                val data = if (hex.length >= dlc * 2) {
                    ByteArray(dlc) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                } else ByteArray(dlc)

                val frame = CanFrame(timestampMs = ts, id = id, isExtended = ext, data = data)
                val message = dbc?.messageForCanId(id)
                val signals: Map<String, Double> = message?.signals
                    ?.mapNotNull { sig -> SignalDecoder.decodeOrNull(sig, data)?.let { sig.name to it } }
                    ?.toMap()
                    ?: emptyMap()

                result.add(DisplayFrame(frame = frame, message = message, decodedSignals = signals, seq = seq++))
            }

            _allFrames.value = result
            _sparklines.value = computeSparklines(result)
            _isLoading.value = false
        }
    }

    // ── Diff helpers ──────────────────────────────────────────────────────────

    private fun recomputeDiff() {
        viewModelScope.launch(Dispatchers.Default) {
            val tsA = _bookmarkA.value ?: return@launch
            val tsB = _bookmarkB.value ?: return@launch
            _diffResult.value = computeDiff(_allFrames.value, tsA, tsB)
        }
    }

    private fun buildSnapshot(frames: List<DisplayFrame>, targetTs: Long): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        frames.filter { it.frame.timestampMs in (targetTs - SNAPSHOT_WINDOW_MS)..targetTs }
            .forEach { df ->
                val msgName = df.message?.name ?: return@forEach
                df.decodedSignals.forEach { (sigName, value) ->
                    result["$msgName/$sigName"] = value
                }
            }
        return result
    }

    private fun computeDiff(frames: List<DisplayFrame>, tsA: Long, tsB: Long): List<SignalDiff> {
        val (earlier, later) = if (tsA <= tsB) tsA to tsB else tsB to tsA
        val snapshotA = buildSnapshot(frames, earlier)
        val snapshotB = buildSnapshot(frames, later)
        val allKeys = (snapshotA.keys + snapshotB.keys).toSet()
        return allKeys.map { key ->
            val parts = key.split("/", limit = 2)
            SignalDiff(
                signalName = parts.getOrElse(1) { key },
                messageName = parts.getOrElse(0) { "?" },
                valueAtA = snapshotA[key],
                valueAtB = snapshotB[key],
            )
        }.sortedByDescending { diff ->
            diff.delta?.let { kotlin.math.abs(it) } ?: 0.0
        }
    }

    // ── Sparkline computation ─────────────────────────────────────────────────

    private fun computeSparklines(frames: List<DisplayFrame>): List<SparklineSeries> {
        if (frames.size < 2) return emptyList()
        val minTs = frames.first().frame.timestampMs
        val maxTs = frames.last().frame.timestampMs
        val dur = maxTs - minTs
        if (dur <= 0L) return emptyList()

        // Collect time-ordered (ts, value) pairs per signal key
        val rawMap = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
        frames.forEach { df ->
            val msgName = df.message?.name ?: return@forEach
            df.decodedSignals.forEach { (sigName, value) ->
                rawMap.getOrPut("$msgName/$sigName") { mutableListOf() }
                    .add(df.frame.timestampMs to value)
            }
        }

        return rawMap.map { (key, values) ->
            val sampled = FloatArray(SPARKLINE_SLOTS)
            var vi = 0
            for (slot in 0 until SPARKLINE_SLOTS) {
                val targetTs = minTs + dur * slot / (SPARKLINE_SLOTS - 1)
                while (vi + 1 < values.size && values[vi + 1].first <= targetTs) vi++
                sampled[slot] = values.getOrNull(vi)?.second?.toFloat() ?: 0f
            }
            // Normalize to [0, 1]
            val sMin = sampled.min()
            val sMax = sampled.max()
            val sRange = sMax - sMin
            if (sRange > 1e-6f) {
                for (i in sampled.indices) sampled[i] = (sampled[i] - sMin) / sRange
            } else {
                sampled.fill(0.5f)
            }
            val parts = key.split("/", limit = 2)
            SparklineSeries(
                key = key,
                signalName = parts.getOrElse(1) { key },
                messageName = parts.getOrElse(0) { "?" },
                points = sampled,
            )
        }.sortedWith(compareBy({ it.messageName }, { it.signalName }))
    }

    companion object {
        const val SNAPSHOT_WINDOW_MS = 2_000L
        const val MAX_CORRELATION_SIGNALS = 4
        private const val SPARKLINE_SLOTS = 200
    }
}
