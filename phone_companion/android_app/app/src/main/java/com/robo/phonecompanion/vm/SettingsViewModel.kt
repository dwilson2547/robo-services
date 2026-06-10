package com.robo.phonecompanion.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robo.phonecompanion.data.git.CredentialStore
import com.robo.phonecompanion.data.git.GitRepository
import com.robo.phonecompanion.data.model.Dbc
import com.robo.phonecompanion.data.model.SessionMeta
import com.robo.phonecompanion.data.model.SidecarData
import com.robo.phonecompanion.data.model.VehicleProfile
import com.robo.phonecompanion.data.repository.DbcRepository
import com.robo.phonecompanion.data.repository.SessionRepository
import com.robo.phonecompanion.data.repository.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val credentialStore = CredentialStore(application)

    private val repoDir = File(application.filesDir, "git_repo")
    val dbcRepository = DbcRepository(File(repoDir, "dbcs"))
    val vehicleRepository = VehicleRepository(File(repoDir, "vehicles"))
    val gitRepository = GitRepository(repoDir)
    val sessionRepository = SessionRepository(File(application.filesDir, "sessions"))

    // ── Vehicles ──────────────────────────────────────────────────────────────

    private val _vehicles = MutableStateFlow<List<VehicleProfile>>(emptyList())
    val vehicles: StateFlow<List<VehicleProfile>> = _vehicles.asStateFlow()

    private val _vehicleSessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val vehicleSessions: StateFlow<List<SessionMeta>> = _vehicleSessions.asStateFlow()

    fun loadSessionsForVehicle(vehicleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _vehicleSessions.value = sessionRepository.listAll()
                .filter { it.vehicleId == vehicleId }
        }
    }

    // ── DBCs ──────────────────────────────────────────────────────────────────

    private val _dbcIds = MutableStateFlow<List<String>>(emptyList())
    val dbcIds: StateFlow<List<String>> = _dbcIds.asStateFlow()

    // ── Git ───────────────────────────────────────────────────────────────────

    sealed class GitOp {
        object Idle : GitOp()
        object Working : GitOp()
        data class Success(val detail: String) : GitOp()
        data class Error(val message: String) : GitOp()
    }

    private val _gitOp = MutableStateFlow<GitOp>(GitOp.Idle)
    val gitOp: StateFlow<GitOp> = _gitOp.asStateFlow()

    private val _recentCommits = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val recentCommits: StateFlow<List<Pair<String, String>>> = _recentCommits.asStateFlow()

    private val _pendingStatus = MutableStateFlow<GitRepository.SyncStatus?>(null)
    val pendingStatus: StateFlow<GitRepository.SyncStatus?> = _pendingStatus.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _vehicles.value = vehicleRepository.loadAll()
            _dbcIds.value = dbcRepository.listIds()
            if (gitRepository.isInitialized) {
                runCatching { _recentCommits.value = gitRepository.log() }
                runCatching { _pendingStatus.value = gitRepository.status() }
            }
        }
    }

    // ── Vehicle CRUD ──────────────────────────────────────────────────────────

    fun saveVehicle(profile: VehicleProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            vehicleRepository.save(profile)
            _vehicles.value = vehicleRepository.loadAll()
        }
    }

    fun deleteVehicle(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            vehicleRepository.delete(id)
            _vehicles.value = vehicleRepository.loadAll()
        }
    }

    // ── DBC loading / creation ────────────────────────────────────────────────

    fun loadDbc(id: String): Pair<Dbc, SidecarData>? {
        val dbc = dbcRepository.load(id) ?: return null
        val sidecar = dbcRepository.sidecarFor(id).load()
        return dbc to sidecar
    }

    fun createDbc(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbcRepository.save(name.trim(), Dbc())
            _dbcIds.value = dbcRepository.listIds()
        }
    }

    data class StarterImportResult(val imported: List<String>, val skipped: List<String>)

    fun importStarterDbcs(onResult: (StarterImportResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val assets = getApplication<Application>().assets
            val files = assets.list("starter_dbcs")
                ?.filter { it.endsWith(".dbc") }
                ?: emptyList()
            val imported = mutableListOf<String>()
            val skipped  = mutableListOf<String>()
            for (filename in files) {
                val id = filename.removeSuffix(".dbc")
                if (dbcRepository.exists(id)) {
                    skipped.add(id)
                } else {
                    val content = assets.open("starter_dbcs/$filename")
                        .bufferedReader().readText()
                    dbcRepository.saveRaw(id, content)
                    imported.add(id)
                }
            }
            _dbcIds.value = dbcRepository.listIds()
            launch(Dispatchers.Main) { onResult(StarterImportResult(imported, skipped)) }
        }
    }

    // ── Git operations ────────────────────────────────────────────────────────

    fun cloneRepo(url: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _gitOp.value = GitOp.Working
            runCatching {
                credentialStore.repoUrl = url
                credentialStore.token = token
                gitRepository.clone(url, token)
                refresh()
                _gitOp.value = GitOp.Success("Repository cloned")
            }.onFailure {
                _gitOp.value = GitOp.Error(it.message ?: "Clone failed")
            }
        }
    }

    fun checkStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { _pendingStatus.value = gitRepository.status() }
        }
    }

    fun pull() {
        val token = credentialStore.token ?: run {
            _gitOp.value = GitOp.Error("No token configured")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _gitOp.value = GitOp.Working
            runCatching {
                gitRepository.pull(token)
                refresh()
                _gitOp.value = GitOp.Success("Pull complete")
            }.onFailure {
                _gitOp.value = GitOp.Error(it.message ?: "Pull failed")
            }
        }
    }

    fun sync(commitMessage: String) {
        val token = credentialStore.token ?: run {
            _gitOp.value = GitOp.Error("No token configured")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _gitOp.value = GitOp.Working
            runCatching {
                val status = gitRepository.status()
                val paths = (status.added + status.modified + status.deleted)
                    .filter { it.startsWith("dbcs/") || it.startsWith("vehicles/") }
                if (paths.isEmpty()) {
                    _gitOp.value = GitOp.Success("Nothing to sync")
                    return@launch
                }
                val hash = gitRepository.sync(paths, commitMessage, token)
                _recentCommits.value = gitRepository.log()
                _pendingStatus.value = gitRepository.status()
                _gitOp.value = GitOp.Success("Synced — $hash")
            }.onFailure {
                _gitOp.value = GitOp.Error(it.message ?: "Sync failed")
            }
        }
    }

    fun clearGitOp() { _gitOp.value = GitOp.Idle }
}
