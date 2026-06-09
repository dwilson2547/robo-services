package com.robo.phonecompanion.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.ui.theme.ColorActive

private val CELL_SIZE = 36.dp
private val HEADER_WIDTH = 28.dp

/**
 * Visual bit grid for signal definition. Columns are arranged MSB→LSB (bit 7→0
 * within each byte). Rows are bytes B0–B(dlc-1).
 *
 * DBC bit number for cell (row, col): row*8 + (7-col)
 *
 * Tapping a cell sets that bit as the new startBit (MSB for Motorola, LSB for Intel).
 * The highlighted range is computed from [startBit] + [length] + [byteOrder].
 */
@Composable
fun BitGrid(
    dlc: Int = 8,
    startBit: Int,
    length: Int,
    byteOrder: ByteOrder,
    onBitTap: (dbcBit: Int) -> Unit,
    liveFrameData: ByteArray? = null,
    modifier: Modifier = Modifier,
) {
    val selectedBits = remember(startBit, length, byteOrder) {
        computeSelectedBits(startBit, length, byteOrder)
    }

    Column(modifier = modifier) {
        // Column header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(HEADER_WIDTH))
            for (col in 0 until 8) {
                Box(modifier = Modifier.size(CELL_SIZE), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${7 - col}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Data rows
        for (row in 0 until dlc) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Byte label
                Box(modifier = Modifier.width(HEADER_WIDTH), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = "B$row",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (col in 0 until 8) {
                    val dbcBit = row * 8 + (7 - col)
                    val isStart = dbcBit == startBit
                    val isSelected = dbcBit in selectedBits
                    val liveHigh = liveFrameData != null &&
                        row < liveFrameData.size &&
                        (liveFrameData[row].toInt() and (1 shl (7 - col))) != 0

                    BitCell(
                        dbcBit = dbcBit,
                        isStart = isStart,
                        isSelected = isSelected,
                        liveHigh = liveHigh,
                        onTap = { onBitTap(dbcBit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BitCell(
    dbcBit: Int,
    isStart: Boolean,
    isSelected: Boolean,
    liveHigh: Boolean,
    onTap: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant

    val bg = when {
        isStart -> ColorActive
        isSelected -> ColorActive.copy(alpha = 0.55f)
        liveHigh -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else -> Color.Transparent
    }

    val textColor = when {
        isStart || isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .size(CELL_SIZE)
            .border(0.5.dp, surface)
            .background(bg)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$dbcBit",
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}

fun computeSelectedBits(startBit: Int, length: Int, byteOrder: ByteOrder): Set<Int> {
    if (length <= 0 || startBit < 0) return emptySet()
    return when (byteOrder) {
        ByteOrder.INTEL -> (startBit until startBit + length).toSet()
        ByteOrder.MOTOROLA -> buildSet {
            var byteIdx = startBit / 8
            var bitIdx = startBit % 8
            repeat(length) {
                add(byteIdx * 8 + bitIdx)
                if (bitIdx == 0) { byteIdx++; bitIdx = 7 } else bitIdx--
            }
        }
    }
}
