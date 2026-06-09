package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.UnknownIdState

@Composable
fun UnknownsScreen(
    vm: CanBusViewModel,
    onDefineSignal: (canId: Int) -> Unit = {},
    onInspect: (canId: Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val unknowns by vm.unknownIds.collectAsState()
    val trigger by vm.triggerTimestamp.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Trigger bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.markTrigger() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (trigger != null) ColorActive else MaterialTheme.colorScheme.primary
                ),
            ) {
                Text(if (trigger != null) "⬤  Triggered" else "⬤  Mark trigger")
            }
            if (trigger != null) {
                OutlinedButton(onClick = { vm.clearTrigger() }) { Text("Clear") }
            }
        }

        HorizontalDivider()

        if (unknowns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("No unknown IDs observed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (trigger != null) {
                    val triggered = unknowns.filter { it.triggeredInWindow }
                    if (triggered.isNotEmpty()) {
                        item {
                            Text(
                                "Active in trigger window",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = ColorActive,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(triggered, key = { "t_${it.id}" }) { state ->
                            UnknownIdRow(state, highlighted = true,
                                onDefine = { onDefineSignal(state.id) },
                                onInspect = { onInspect(state.id) })
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }

                item {
                    Text(
                        "All unknown IDs",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(unknowns, key = { it.id }) { state ->
                    UnknownIdRow(state, highlighted = false,
                        onDefine = { onDefineSignal(state.id) },
                        onInspect = { onInspect(state.id) })
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UnknownIdRow(
    state: UnknownIdState,
    highlighted: Boolean,
    onDefine: () -> Unit = {},
    onInspect: () -> Unit = {},
) {
    val idStr = if (state.isExtended) "0x%08X".format(state.id)
                else "0x%03X".format(state.id)
    val hex = state.lastFrame.data.joinToString(" ") { "%02X".format(it) }
    val hz = "%.0f".format(state.updateRateHz)
    val barFraction = (state.updateRateHz / 100f).coerceIn(0f, 1f)
    val accentColor = if (highlighted) ColorActive else ColorUnknown

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(idStr, color = accentColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold)
                ActivityBar(fraction = barFraction, color = accentColor)
                Text("$hz Hz", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(hex, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
        OutlinedButton(onClick = onDefine, modifier = Modifier.padding(start = 8.dp)) {
            Text("Define", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ActivityBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(48.dp)
            .height(6.dp)
            .background(color.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(color, shape = MaterialTheme.shapes.small),
        )
    }
}
