package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.SignalHealth

private val ColorHealthWarning = Color(0xFFFFB300)

@Composable
fun SignalHealthScreen(vm: CanBusViewModel) {
    val health by vm.signalHealth.collectAsState()
    val dbc by vm.activeDbc.collectAsState()

    // Collect only signals with at least one active flag
    val flagged = health.filter { (_, h) -> h.isStuck || h.isPegged }

    // Build reverse lookup: signalName → message name + CAN ID string
    data class SigMeta(val messageName: String, val canIdStr: String)
    val sigMeta: Map<String, SigMeta> = dbc?.messages?.values
        ?.flatMap { msg ->
            val idStr = if (msg.isExtended) "0x%08X".format(msg.canId)
                        else "0x%03X".format(msg.canId)
            msg.signals.map { it.name to SigMeta(msg.name, idStr) }
        }
        ?.toMap()
        ?: emptyMap()

    if (flagged.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No signals with active health flags.\nFlags appear after a signal is observed for ~5 seconds.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = ColorHealthWarning.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Warning, null,
                    tint = ColorHealthWarning, modifier = Modifier.size(18.dp))
                Text(
                    "${flagged.size} signal${if (flagged.size > 1) "s" else ""} with health warnings",
                    style = MaterialTheme.typography.labelMedium,
                    color = ColorHealthWarning,
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(flagged.entries.sortedBy { it.key }, key = { it.key }) { (sigName, h) ->
                val meta = sigMeta[sigName]
                HealthFlagRow(
                    signalName = sigName,
                    messageName = meta?.messageName ?: "—",
                    canIdStr = meta?.canIdStr ?: "—",
                    health = h,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun HealthFlagRow(
    signalName: String,
    messageName: String,
    canIdStr: String,
    health: SignalHealth,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(signalName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "$messageName  $canIdStr",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (health.isStuck) {
                FlagChip("STUCK")
            }
            if (health.isPegged) {
                FlagChip("PEGGED")
            }
        }
    }
}

@Composable
private fun FlagChip(label: String) {
    Surface(
        color = ColorHealthWarning.copy(alpha = 0.18f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = ColorHealthWarning,
        )
    }
}
