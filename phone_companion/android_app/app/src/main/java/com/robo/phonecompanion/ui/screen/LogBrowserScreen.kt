package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.DisplayFrame
import com.robo.phonecompanion.vm.LogPlayerViewModel

@Composable
fun LogBrowserScreen(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val vm: LogPlayerViewModel = viewModel()

    LaunchedEffect(sessionId) { vm.loadSession(sessionId) }

    val meta by vm.meta.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val frames by vm.visibleFrames.collectAsState()
    val showKnown by vm.showKnown.collectAsState()
    val showUnknown by vm.showUnknown.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        meta?.let { m ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        m.startTime.take(16).replace('T', ' '),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        buildString {
                            append("${m.frameCount} frames")
                            val dur = m.endTime?.let { end ->
                                runCatching {
                                    val s = java.time.LocalDateTime.parse(m.startTime)
                                    val e = java.time.LocalDateTime.parse(end)
                                    val secs = java.time.temporal.ChronoUnit.SECONDS.between(s, e)
                                    if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
                                }.getOrNull()
                            }
                            if (dur != null) append("  ·  $dur")
                            if (m.dbcId != "none") append("  ·  ${m.dbcId}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = showKnown,
                onClick = { vm.toggleShowKnown() },
                label = { Text("Known") },
            )
            FilterChip(
                selected = showUnknown,
                onClick = { vm.toggleShowUnknown() },
                label = { Text("Unknown") },
            )
        }

        HorizontalDivider()

        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            frames.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("No frames to display.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val baseTs = frames.first().frame.timestampMs
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(frames, key = { it.seq }) { df ->
                        LogFrameRow(df = df, baseTs = baseTs)
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFrameRow(df: DisplayFrame, baseTs: Long) {
    val isKnown = df.message != null
    val idColor = if (isKnown) ColorActive else ColorUnknown
    val elapsed = df.frame.timestampMs - baseTs
    val min = elapsed / 60_000
    val sec = (elapsed % 60_000) / 1000
    val ms = elapsed % 1000
    val tsLabel = "+%02d:%02d.%03d".format(min, sec, ms)

    val idStr = if (df.frame.isExtended) "0x%08X".format(df.frame.id)
                else "0x%03X".format(df.frame.id)

    val content = when {
        df.message != null && df.decodedSignals.isNotEmpty() ->
            df.decodedSignals.entries.joinToString("  ") { (k, v) ->
                val unit = df.message?.signals?.find { it.name == k }?.unit ?: ""
                "$k=${"%.2f".format(v)}$unit"
            }
        else -> df.frame.data.joinToString(" ") { "%02X".format(it) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            tsLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp),
        )
        Text(
            idStr,
            color = idColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp),
        )
        Text(
            content,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
