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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LogPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionRepository = SessionRepository(File(application.filesDir, "sessions"))
    private val dbcRepository = DbcRepository(File(application.filesDir, "git_repo/dbcs"))

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _meta = MutableStateFlow<SessionMeta?>(null)
    val meta: StateFlow<SessionMeta?> = _meta.asStateFlow()

    private val _allFrames = MutableStateFlow<List<DisplayFrame>>(emptyList())

    private val _showKnown = MutableStateFlow(true)
    val showKnown: StateFlow<Boolean> = _showKnown.asStateFlow()

    private val _showUnknown = MutableStateFlow(true)
    val showUnknown: StateFlow<Boolean> = _showUnknown.asStateFlow()

    val visibleFrames: StateFlow<List<DisplayFrame>> =
        combine(_allFrames, _showKnown, _showUnknown) { frames, known, unknown ->
            frames.filter { if (it.message != null) known else unknown }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleShowKnown() { _showKnown.value = !_showKnown.value }
    fun toggleShowUnknown() { _showUnknown.value = !_showUnknown.value }

    fun loadSession(sessionId: String) {
        if (_meta.value?.id == sessionId) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _allFrames.value = emptyList()

            val meta = sessionRepository.loadMeta(sessionId) ?: run {
                _isLoading.value = false
                return@launch
            }
            _meta.value = meta

            val dbc = if (meta.dbcId != "none") runCatching { dbcRepository.load(meta.dbcId) }.getOrNull() else null
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
                    ?.associate { sig -> sig.name to SignalDecoder.decode(sig, data) }
                    ?: emptyMap()

                result.add(DisplayFrame(frame = frame, message = message, decodedSignals = signals, seq = seq++))
            }

            _allFrames.value = result
            _isLoading.value = false
        }
    }
}
