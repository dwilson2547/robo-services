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
import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.data.model.DbcSignal
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.CanBusViewModel

private val CELL_W = 36.dp
private val LABEL_W = 56.dp

// Distinct tint colors for signal overlays (cycled)
private val SIGNAL_TINTS = listOf(
    Color(0x4400BFFF), // blue
    Color(0x44FF8C00), // orange
    Color(0x4432CD32), // green
    Color(0x44FF69B4), // pink
    Color(0x44DA70D6), // orchid
    Color(0x4487CEEB), // sky
)

@Composable
fun FrameInspectorScreen(
    vm: CanBusViewModel,
    canId: Int,
    onDefineSignal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val unknowns by vm.unknownIds.collectAsState()
    val known by vm.knownMessages.collectAsState()

    val unknownState = unknowns.find { it.id == canId }
    val knownState = known[canId]

    val frames: List<CanFrame> = unknownState?.recentFrames
        ?: knownState?.recentFrames?.takeIf { it.isNotEmpty() }
        ?: knownState?.lastFrame?.let { listOf(it) }
        ?: emptyList()

    val isExtended = unknownState?.isExtended ?: false
    val idStr = if (isExtended) "0x%08X".format(canId) else "0x%03X".format(canId)

    // Signal bit ranges for overlay (only for known messages)
    val signals = knownState?.message?.signals ?: emptyList()

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Inspector  $idStr",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (knownState != null) ColorActive else ColorUnknown,
                )
                if (knownState != null) {
                    Text(knownState.message.name,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onDefineSignal) {
                Text(if (knownState != null) "Edit signals" else "Define signal")
            }
        }

        // Signal legend for known messages
        if (signals.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                signals.take(SIGNAL_TINTS.size).forEachIndexed { idx, sig ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(SIGNAL_TINTS[idx].copy(alpha = 0.8f)),
                        )
                        Text(sig.name, fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (frames.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("No frames for this ID.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

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
                FrameRow(frame = frame, prev = prev, dlc = dlc, signals = signals)
            }
        }
    }
}

/** Returns a list of bit positions [0..dlc*8) occupied by [signal]. */
private fun signalBits(signal: DbcSignal, dlc: Int): Set<Int> {
    val bits = mutableSetOf<Int>()
    val maxBit = dlc * 8
    when (signal.byteOrder) {
        ByteOrder.INTEL -> {
            for (i in 0 until signal.length) {
                val bit = signal.startBit + i
                if (bit < maxBit) bits.add(bit)
            }
        }
        ByteOrder.MOTOROLA -> {
            var byteIdx = signal.startBit / 8
            var bitIdx = signal.startBit % 8
            repeat(signal.length) {
                val bit = byteIdx * 8 + bitIdx
                if (bit < maxBit) bits.add(bit)
                if (bitIdx == 0) { byteIdx++; bitIdx = 7 } else bitIdx--
            }
        }
    }
    return bits
}

@Composable
private fun FrameRow(
    frame: CanFrame,
    prev: CanFrame?,
    dlc: Int,
    signals: List<DbcSignal> = emptyList(),
) {
    // Pre-compute which signal (by index) owns each byte, for overlay tinting
    val byteSignalIdx = IntArray(dlc) { -1 }
    signals.take(SIGNAL_TINTS.size).forEachIndexed { sigIdx, sig ->
        signalBits(sig, dlc).forEach { bit -> byteSignalIdx[bit / 8] = sigIdx }
    }

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

            val signalTint = byteSignalIdx.getOrElse(b) { -1 }
                .takeIf { it >= 0 }
                ?.let { SIGNAL_TINTS[it] }

            val bg = when {
                changed -> ColorActive.copy(alpha = 0.35f)
                signalTint != null -> signalTint
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
