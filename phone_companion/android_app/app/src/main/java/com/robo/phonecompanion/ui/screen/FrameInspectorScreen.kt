package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.data.model.CanFrame
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.CanBusViewModel

private val CELL_W = 36.dp
private val LABEL_W = 56.dp

@Composable
fun FrameInspectorScreen(
    vm: CanBusViewModel,
    canId: Int,
    onDefineSignal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val unknowns by vm.unknownIds.collectAsState()
    val state = unknowns.find { it.id == canId }

    val idStr = if (state?.isExtended == true) "0x%08X".format(canId)
                else "0x%03X".format(canId)

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Inspector  $idStr",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = ColorUnknown,
            )
            Button(onClick = onDefineSignal) { Text("Define signal") }
        }

        if (state == null || state.recentFrames.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("No frames for this ID.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        val frames = state.recentFrames
        val dlc = frames.maxOf { it.data.size }.coerceAtMost(8)

        // Column header
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = LABEL_W, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (b in 0 until dlc) {
                Box(modifier = Modifier.size(CELL_W), contentAlignment = Alignment.Center) {
                    Text("B$b", fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(frames) { idx, frame ->
                val prev = if (idx > 0) frames[idx - 1] else null
                FrameRow(frame = frame, prev = prev, dlc = dlc)
            }
        }
    }
}

@Composable
private fun FrameRow(frame: CanFrame, prev: CanFrame?, dlc: Int) {
    val tsLabel = if (prev != null) {
        val delta = frame.timestampMs - prev.timestampMs
        "+${delta}ms"
    } else {
        "T+0"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(LABEL_W), contentAlignment = Alignment.CenterEnd) {
            Text(tsLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp))
        }
        for (b in 0 until dlc) {
            val byte = if (b < frame.data.size) frame.data[b].toInt() and 0xFF else null
            val prevByte = if (prev != null && b < prev.data.size) prev.data[b].toInt() and 0xFF else null
            val changed = byte != null && prevByte != null && byte != prevByte
            val isFirst = prev == null

            val bg = when {
                changed -> ColorActive.copy(alpha = 0.35f)
                isFirst -> Color.Transparent
                else -> Color.Transparent
            }
            val textColor = when {
                changed -> ColorActive
                byte != null -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            }
            val borderColor = if (changed) ColorActive.copy(alpha = 0.6f)
                              else MaterialTheme.colorScheme.surfaceVariant

            Box(
                modifier = Modifier
                    .size(CELL_W)
                    .border(0.5.dp, borderColor)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (byte != null) "%02X".format(byte) else "--",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
