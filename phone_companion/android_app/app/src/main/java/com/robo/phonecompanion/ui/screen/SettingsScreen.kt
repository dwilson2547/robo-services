package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.vm.SettingsViewModel

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onNavigateGit: () -> Unit,
    onNavigateVehicles: () -> Unit,
    onNavigateDbcs: () -> Unit,
    onNavigateFirmware: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gitOp by vm.gitOp.collectAsState()
    val pending by vm.pendingStatus.collectAsState()
    val recentCommits by vm.recentCommits.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSyncDialog by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("") }

    LaunchedEffect(gitOp) {
        when (val op = gitOp) {
            is SettingsViewModel.GitOp.Success -> {
                snackbar.showSnackbar(op.detail)
                vm.clearGitOp()
            }
            is SettingsViewModel.GitOp.Error -> {
                snackbar.showSnackbar("Error: ${op.message}")
                vm.clearGitOp()
            }
            else -> {}
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SettingsTile(
            icon = Icons.Default.AccountTree,
            title = "Git Repository",
            subtitle = if (vm.credentialStore.isConfigured) vm.credentialStore.repoUrl ?: ""
                       else "Not configured",
            onClick = onNavigateGit,
        )
        HorizontalDivider()
        SettingsTile(
            icon = Icons.Default.DirectionsCar,
            title = "Vehicles",
            subtitle = "Manage vehicle profiles",
            onClick = onNavigateVehicles,
        )
        HorizontalDivider()
        SettingsTile(
            icon = Icons.Default.Storage,
            title = "DBC Files",
            subtitle = "Select active DBC",
            onClick = onNavigateDbcs,
        )
        HorizontalDivider()
        SettingsTile(
            icon = Icons.Default.SystemUpdate,
            title = "Firmware Update",
            subtitle = "Update dongle firmware over BLE",
            onClick = onNavigateFirmware,
        )
        HorizontalDivider()

        // Pull tile
        val isWorking = gitOp is SettingsViewModel.GitOp.Working
        SettingsTile(
            icon = Icons.Default.Download,
            title = "Pull from Git",
            subtitle = when {
                isWorking -> "Working…"
                vm.gitRepository.isInitialized -> "Fetch latest from remote"
                else -> "Configure git first"
            },
            onClick = {
                if (vm.credentialStore.isConfigured && vm.gitRepository.isInitialized && !isWorking) {
                    vm.pull()
                }
            },
            trailing = {
                if (isWorking) {
                    CircularProgressIndicator(strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp))
                }
            },
        )
        HorizontalDivider()

        // Push tile — shows pending change count if repo is initialised
        val changeCount = pending?.let { it.added.size + it.modified.size + it.deleted.size }
        SettingsTile(
            icon = Icons.Default.Upload,
            title = "Push to Git",
            subtitle = when {
                isWorking -> "Working…"
                changeCount != null && changeCount > 0 -> "$changeCount file(s) pending"
                changeCount == 0 -> "Up to date"
                else -> "Configure git first"
            },
            onClick = {
                if (vm.credentialStore.isConfigured && vm.gitRepository.isInitialized) {
                    vm.checkStatus()
                    commitMessage = ""
                    showSyncDialog = true
                }
            },
            trailing = {
                if (isWorking) {
                    CircularProgressIndicator(strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp))
                }
            },
        )
        HorizontalDivider()

        // Recent commits
        if (recentCommits.isNotEmpty()) {
            Text(
                "Recent commits",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            recentCommits.forEach { (hash, subject) ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(hash, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp))
                    Text(subject, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        SnackbarHost(hostState = snackbar)
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("Push to Git") },
            text = {
                Column {
                    pending?.let { s ->
                        if (s.hasChanges) {
                            Text("Changes:", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(bottom = 4.dp))
                            (s.added.map { "+ $it" } + s.modified.map { "~ $it" } +
                                s.deleted.map { "- $it" }).forEach {
                                Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        } else {
                            Text("No pending changes.")
                        }
                    }
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit message") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSyncDialog = false
                        vm.sync(commitMessage.ifBlank { "Update DBC data" })
                    },
                    enabled = pending?.hasChanges == true,
                ) { Text("Push") }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
