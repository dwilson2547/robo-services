package com.robo.phonecompanion.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.robo.phonecompanion.vm.SettingsViewModel

@Composable
fun GitConfigScreen(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    var repoUrl by rememberSaveable { mutableStateOf(vm.credentialStore.repoUrl ?: "") }
    var token by rememberSaveable { mutableStateOf("") }
    var showRecloneConfirm by remember { mutableStateOf(false) }

    val gitOp by vm.gitOp.collectAsState()
    val working = gitOp is SettingsViewModel.GitOp.Working

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Git Repository", style = MaterialTheme.typography.titleMedium)
        Text(
            "Changes to DBCs and vehicle profiles are committed and pushed on demand. " +
                "The token is stored encrypted on-device and never leaves the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = repoUrl,
            onValueChange = { repoUrl = it },
            label = { Text("Repository URL") },
            placeholder = { Text("https://github.com/user/repo.git") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Personal Access Token") },
            placeholder = {
                Text(if (vm.credentialStore.token != null) "••••••••  (set — enter to replace)" else "ghp_…")
            },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        val canSave = repoUrl.isNotBlank() && (token.isNotBlank() || vm.credentialStore.token != null)
        val isCloned = vm.gitRepository.isInitialized

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val effectiveToken = token.ifBlank { vm.credentialStore.token ?: return@Button }
                    vm.cloneRepo(repoUrl.trim(), effectiveToken)
                    if (token.isNotBlank()) {
                        // Token was re-entered — clear the field but leave url
                        token = ""
                    }
                },
                enabled = canSave && !working,
                modifier = Modifier.weight(1f),
            ) {
                if (working) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text(if (isCloned) "Re-clone" else "Clone")
            }

            if (isCloned) {
                OutlinedButton(
                    onClick = { showRecloneConfirm = true },
                    enabled = !working,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reset") }
            }
        }

        HorizontalDivider()

        // Status feedback
        when (val op = gitOp) {
            is SettingsViewModel.GitOp.Success ->
                Text(op.detail, color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall)
            is SettingsViewModel.GitOp.Error ->
                Text(op.message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            else -> {}
        }

        if (isCloned) {
            Text(
                "Cloned to app private storage. Use Push on the main settings screen to push changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showRecloneConfirm) {
        AlertDialog(
            onDismissRequest = { showRecloneConfirm = false },
            title = { Text("Reset repository?") },
            text = { Text("This deletes the local clone. DBCs and vehicle profiles on the phone will be lost. Sessions are not affected.") },
            confirmButton = {
                Button(
                    onClick = {
                        showRecloneConfirm = false
                        val effectiveToken = token.ifBlank { vm.credentialStore.token ?: return@Button }
                        vm.cloneRepo(repoUrl.trim(), effectiveToken)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset and re-clone") }
            },
            dismissButton = {
                TextButton(onClick = { showRecloneConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
