package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robo.phonecompanion.data.model.SessionMeta
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.DisplayFrame
import com.robo.phonecompanion.vm.LogPlayerViewModel
import com.robo.phonecompanion.vm.SignalDiff
import com.robo.phonecompanion.vm.SparklineSeries

// ── Color palette ─────────────────────────────────────────────────────────────

private val ColorRef = Color(0xFFFFB300)
private val ColorBookmarkA = Color(0xFF1565C0)
private val ColorBookmarkB = Color(0xFF2E7D32)
private val PlotColors = listOf(
    Color(0xFF2196F3), // blue
    Color(0xFFF44336), // red
    Color(0xFF4CAF50), // green
    Color(0xFFFF9800), // orange
)

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
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
    val allFrames by vm.allFrames.collectAsState()
    val showKnown by vm.showKnown.collectAsState()
    val showUnknown by vm.showUnknown.collectAsState()
    val rangeStart by vm.rangeStart.collectAsState()
    val rangeEnd by vm.rangeEnd.collectAsState()
    val referenceTs by vm.referenceTs.collectAsState()
    val bookmarkA by vm.bookmarkA.collectAsState()
    val bookmarkB by vm.bookmarkB.collectAsState()
    val diffResult by vm.diffResult.collectAsState()
    val sparklines by vm.sparklines.collectAsState()
    val selectedSignalKeys by vm.selectedSignalKeys.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val playbackSpeed by vm.playbackSpeed.collectAsState()

    var showDiffDialog by remember { mutableStateOf(false) }
    var showSignalPicker by remember { mutableStateOf(false) }
    var showPlotDialog by remember { mutableStateOf(false) }

    val baseTs = allFrames.firstOrNull()?.frame?.timestampMs ?: 0L
    val sessionDur = (allFrames.lastOrNull()?.frame?.timestampMs ?: 0L) - baseTs

    if (showDiffDialog) {
        DiffDialog(
            diffResult = diffResult,
            bookmarkA = bookmarkA,
            bookmarkB = bookmarkB,
            baseTs = baseTs,
            referenceTs = referenceTs,
            onDismiss = { showDiffDialog = false },
            onClear = { vm.clearDiff(); showDiffDialog = false },
        )
    }

    if (showSignalPicker) {
        SignalPickerDialog(
            sparklines = sparklines,
            selected = selectedSignalKeys,
            onToggle = { vm.toggleSignalSelection(it) },
            onDismiss = { showSignalPicker = false },
            onConfirm = { showSignalPicker = false; showPlotDialog = true },
        )
    }

    if (showPlotDialog) {
        CorrelationPlotDialog(
            allFrames = allFrames,
            selectedKeys = selectedSignalKeys,
            sparklines = sparklines,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            sessionMinTs = baseTs,
            sessionDur = sessionDur,
            onDismiss = { showPlotDialog = false },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        meta?.let { m -> SessionHeader(m) }

        // Filter chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = showKnown, onClick = { vm.toggleShowKnown() }, label = { Text("Known") })
            FilterChip(selected = showUnknown, onClick = { vm.toggleShowUnknown() }, label = { Text("Unknown") })
            Spacer(modifier = Modifier.weight(1f))
            // Correlation plot trigger
            if (sparklines.isNotEmpty()) {
                TextButton(onClick = { showSignalPicker = true }) {
                    val n = selectedSignalKeys.size
                    Text(if (n > 0) "Plot ($n)" else "Plot")
                }
            }
            // Diff trigger
            if (bookmarkA != null && bookmarkB != null) {
                val changed = diffResult.count { val d = it.delta; d != null && kotlin.math.abs(d) > 1e-10 }
                TextButton(onClick = { showDiffDialog = true }) {
                    Text("Diff ($changed)")
                }
            }
        }

        // Bookmark / reference status chips
        if (referenceTs != null || bookmarkA != null || bookmarkB != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                referenceTs?.let { ref ->
                    BookmarkChip("★ T=0", formatTs(ref, null, baseTs), ColorRef) { vm.clearReference() }
                }
                bookmarkA?.let { aTs ->
                    BookmarkChip("A", formatTs(aTs, referenceTs, baseTs), ColorBookmarkA) { vm.clearBookmarkA() }
                }
                bookmarkB?.let { bTs ->
                    BookmarkChip("B", formatTs(bTs, referenceTs, baseTs), ColorBookmarkB) { vm.clearBookmarkB() }
                }
            }
        }

        HorizontalDivider()

        // Frame list
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                frames.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        if (allFrames.isEmpty()) "No frames to display."
                        else "No frames in the selected window.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(frames, key = { it.seq }) { df ->
                        LogFrameRow(
                            df = df,
                            baseTs = baseTs,
                            referenceTs = referenceTs,
                            onSetReference = { vm.setReference(df.frame.timestampMs) },
                            onMarkA = { vm.setBookmarkA(df.frame.timestampMs) },
                            onMarkB = { vm.setBookmarkB(df.frame.timestampMs) },
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }

        // Sparklines + scrubber + playback — only when loaded
        if (!isLoading && allFrames.isNotEmpty()) {
            HorizontalDivider()

            // Phase 6.2 — Sparkline row
            if (sparklines.isNotEmpty()) {
                SparklineRow(
                    sparklines = sparklines,
                    selectedKeys = selectedSignalKeys,
                    onToggle = { vm.toggleSignalSelection(it) },
                )
            }

            // Phase 6.6 — Playback controls + Phase 6.1 — Range scrubber
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (isPlaying) vm.stopPlayback() else vm.startPlayback() },
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    TimelineSlider(
                        sessionDur = sessionDur,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                        onRangeChange = { s, e -> vm.stopPlayback(); vm.setRange(s, e) },
                    )
                }
                SpeedButton(speed = playbackSpeed, onSpeedChange = { vm.setPlaybackSpeed(it) })
            }
        }
    }
}

// ── Session header ────────────────────────────────────────────────────────────

@Composable
private fun SessionHeader(m: SessionMeta) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(m.startTime.take(16).replace('T', ' '), style = MaterialTheme.typography.labelMedium)
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
            if (m.notes.isNotBlank()) {
                Text(m.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Timeline scrubber ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineSlider(
    sessionDur: Long,
    rangeStart: Float,
    rangeEnd: Float,
    onRangeChange: (Float, Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00:00", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rangeStart > 0.01f || rangeEnd < 0.99f) {
                Text(
                    "${formatDur((sessionDur * rangeStart).toLong())} – ${formatDur((sessionDur * rangeEnd).toLong())}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(formatDur(sessionDur), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RangeSlider(
            value = rangeStart..rangeEnd,
            onValueChange = { r -> onRangeChange(r.start, r.endInclusive) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Playback speed button ─────────────────────────────────────────────────────

@Composable
private fun SpeedButton(speed: Float, onSpeedChange: (Float) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { showMenu = true }, modifier = Modifier.width(52.dp)) {
            Text("${speed.formatSpeed()}×", fontSize = 12.sp)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { s ->
                DropdownMenuItem(
                    text = { Text("${s.formatSpeed()}×") },
                    onClick = { onSpeedChange(s); showMenu = false },
                )
            }
        }
    }
}

private fun Float.formatSpeed(): String = if (this == kotlin.math.floor(this)) toInt().toString() else toString()

// ── Sparkline row ─────────────────────────────────────────────────────────────

@Composable
private fun SparklineRow(
    sparklines: List<SparklineSeries>,
    selectedKeys: List<String>,
    onToggle: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(sparklines) { index, series ->
            val color = PlotColors[index % PlotColors.size]
            val isSelected = series.key in selectedKeys
            SparklineCard(series = series, color = color, isSelected = isSelected, onTap = { onToggle(series.key) })
        }
    }
}

@Composable
private fun SparklineCard(
    series: SparklineSeries,
    color: Color,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val bgColor = if (isSelected) color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isSelected) color else Color.Transparent

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier
            .width(110.dp)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap),
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                series.signalName,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = if (isSelected) color else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                series.messageName,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
                if (series.points.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val path = Path()
                series.points.forEachIndexed { i, y ->
                    val x = w * i / (series.points.size - 1)
                    val yPos = h * (1f - y)
                    if (i == 0) path.moveTo(x, yPos) else path.lineTo(x, yPos)
                }
                drawPath(path, color = color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ── Signal picker dialog ──────────────────────────────────────────────────────

@Composable
private fun SignalPickerDialog(
    sparklines: List<SparklineSeries>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select signals to plot (max ${LogPlayerViewModel.MAX_CORRELATION_SIGNALS})") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(sparklines) { index, series ->
                    val color = PlotColors[index % PlotColors.size]
                    val isSelected = series.key in selected
                    val canSelect = isSelected || selected.size < LogPlayerViewModel.MAX_CORRELATION_SIGNALS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canSelect) { onToggle(series.key) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = if (canSelect) { _ -> onToggle(series.key) } else null,
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color, RoundedCornerShape(50)),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(series.signalName, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Text(series.messageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selected.isNotEmpty()) { Text("Plot") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Correlation plot dialog ───────────────────────────────────────────────────

@Composable
private fun CorrelationPlotDialog(
    allFrames: List<DisplayFrame>,
    selectedKeys: List<String>,
    sparklines: List<SparklineSeries>,
    rangeStart: Float,
    rangeEnd: Float,
    sessionMinTs: Long,
    sessionDur: Long,
    onDismiss: () -> Unit,
) {
    val tStart = sessionMinTs + (sessionDur * rangeStart).toLong()
    val tEnd = sessionMinTs + (sessionDur * rangeEnd).toLong()

    val seriesData: Map<String, List<Pair<Long, Double>>> = remember(allFrames, selectedKeys, tStart, tEnd) {
        val result = selectedKeys.associateWith { mutableListOf<Pair<Long, Double>>() }
        allFrames.forEach { df ->
            val msgName = df.message?.name ?: return@forEach
            df.decodedSignals.forEach { (sigName, value) ->
                val key = "$msgName/$sigName"
                if (key in selectedKeys && df.frame.timestampMs in tStart..tEnd) {
                    (result[key] as MutableList).add(df.frame.timestampMs to value)
                }
            }
        }
        result
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.82f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column {
                // Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                    Text("Correlation plot", style = MaterialTheme.typography.titleMedium)
                }

                // Signal legend
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(selectedKeys) { i, key ->
                        val color = PlotColors[i % PlotColors.size]
                        val label = sparklines.find { it.key == key }?.signalName ?: key.substringAfterLast("/")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                            Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color)
                        }
                    }
                }

                HorizontalDivider()

                // Y-axis value labels + plot canvas
                Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                    // Y-axis labels column
                    Column(
                        modifier = Modifier.width(48.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        selectedKeys.forEachIndexed { i, key ->
                            val color = PlotColors[i % PlotColors.size]
                            val points = seriesData[key] ?: emptyList()
                            if (points.isNotEmpty()) {
                                val max = points.maxOf { it.second }
                                val min = points.minOf { it.second }
                                Column {
                                    Text("%.3g".format(max), fontSize = 8.sp, color = color)
                                    Text("%.3g".format(min), fontSize = 8.sp, color = color)
                                }
                            }
                        }
                    }

                    // Plot canvas
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    ) {
                        val w = size.width
                        val h = size.height
                        val tRange = (tEnd - tStart).coerceAtLeast(1L).toFloat()

                        // Grid lines
                        for (g in 0..4) {
                            val y = h * g / 4
                            drawLine(
                                color = Color.Gray.copy(alpha = 0.25f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 0.5.dp.toPx(),
                            )
                        }

                        selectedKeys.forEachIndexed { i, key ->
                            val color = PlotColors[i % PlotColors.size]
                            val points = seriesData[key] ?: return@forEachIndexed
                            if (points.size < 2) return@forEachIndexed

                            val minV = points.minOf { it.second }.toFloat()
                            val maxV = points.maxOf { it.second }.toFloat()
                            val vRange = (maxV - minV).coerceAtLeast(1e-6f)

                            val path = Path()
                            points.forEachIndexed { j, (ts, v) ->
                                val x = w * (ts - tStart) / tRange
                                val y = h * (1f - (v.toFloat() - minV) / vRange)
                                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }

                // Time axis labels
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatDur((sessionDur * rangeStart).toLong()), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDur((sessionDur * rangeEnd).toLong()), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Bookmark chips ────────────────────────────────────────────────────────────

@Composable
private fun BookmarkChip(label: String, sublabel: String, color: Color, onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.15f)) {
        Row(
            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace)
            Text(sublabel, fontSize = 10.sp, color = color.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace)
            Icon(Icons.Default.Close, contentDescription = "Clear", tint = color, modifier = Modifier.size(12.dp))
        }
    }
}

// ── Frame row ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogFrameRow(
    df: DisplayFrame,
    baseTs: Long,
    referenceTs: Long?,
    onSetReference: () -> Unit,
    onMarkA: () -> Unit,
    onMarkB: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val ts = df.frame.timestampMs
    val isKnown = df.message != null
    val idStr = if (df.frame.isExtended) "0x%08X".format(df.frame.id) else "0x%03X".format(df.frame.id)

    val content = when {
        df.message != null && df.decodedSignals.isNotEmpty() ->
            df.decodedSignals.entries.joinToString("  ") { (k, v) ->
                val unit = df.message.signals.find { it.name == k }?.unit ?: ""
                "$k=${"%.2f".format(v)}$unit"
            }
        else -> df.frame.data.joinToString(" ") { "%02X".format(it) }
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTs(ts, referenceTs, baseTs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(84.dp),
            )
            Text(
                idStr,
                color = if (isKnown) ColorActive else ColorUnknown,
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

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Set as T=0 reference") },
                onClick = { onSetReference(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Mark as A") },
                onClick = { onMarkA(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Mark as B") },
                onClick = { onMarkB(); showMenu = false },
            )
        }
    }
}

// ── Diff dialog ───────────────────────────────────────────────────────────────

@Composable
private fun DiffDialog(
    diffResult: List<SignalDiff>,
    bookmarkA: Long?,
    bookmarkB: Long?,
    baseTs: Long,
    referenceTs: Long?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val tsA = bookmarkA?.let { formatTs(it, referenceTs, baseTs) } ?: "?"
    val tsB = bookmarkB?.let { formatTs(it, referenceTs, baseTs) } ?: "?"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signal diff  $tsA → $tsB") },
        text = {
            if (diffResult.isEmpty()) {
                Text(
                    "No decoded signals in the 2-second windows before each bookmark. " +
                        "Ensure a DBC is active and both bookmarks are within decoded sections.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(diffResult) { diff ->
                        DiffRow(diff)
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Clear A/B") } },
    )
}

@Composable
private fun DiffRow(diff: SignalDiff) {
    val delta = diff.delta
    val changed = delta != null && kotlin.math.abs(delta) > 1e-10
    val deltaStr = when {
        delta == null -> if (diff.valueAtA != null) "disappeared" else "appeared"
        delta > 1e-10 -> "↑${"%.4g".format(delta)}"
        delta < -1e-10 -> "↓${"%.4g".format(kotlin.math.abs(delta))}"
        else -> "unchanged"
    }
    val deltaColor = if (changed) ColorActive else MaterialTheme.colorScheme.onSurfaceVariant
    val aStr = diff.valueAtA?.let { "%.4g".format(it) } ?: "—"
    val bStr = diff.valueAtB?.let { "%.4g".format(it) } ?: "—"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(diff.signalName, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(diff.messageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$aStr → $bStr", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(deltaStr, fontSize = 11.sp, color = deltaColor, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Format helpers ────────────────────────────────────────────────────────────

private fun formatTs(ts: Long, referenceTs: Long?, baseTs: Long): String {
    if (referenceTs != null) {
        val rel = ts - referenceTs
        val sign = if (rel >= 0) "T+" else "T-"
        val abs = kotlin.math.abs(rel)
        return "$sign${"%.1f".format(abs / 1000.0)}s"
    }
    val elapsed = ts - baseTs
    val min = elapsed / 60_000
    val sec = (elapsed % 60_000) / 1000
    val ms = elapsed % 1000
    return "+%02d:%02d.%03d".format(min, sec, ms)
}

private fun formatDur(ms: Long): String {
    if (ms <= 0L) return "0s"
    val m = ms / 60_000
    val s = (ms % 60_000) / 1000
    val tenths = (ms % 1000) / 100
    return if (m > 0) "%d:%02d.%d".format(m, s, tenths) else "%d.%ds".format(s, tenths)
}
