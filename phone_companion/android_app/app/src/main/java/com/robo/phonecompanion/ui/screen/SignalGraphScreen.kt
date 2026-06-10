package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.vm.CanBusViewModel
import kotlinx.coroutines.launch

private val GraphColors = listOf(
    Color(0xFF2196F3),
    Color(0xFFF44336),
    Color(0xFF4CAF50),
    Color(0xFFFF9800),
)
private val ThresholdColor = Color(0xFFFFB300)

private val WindowOptions = listOf(5, 15, 30, 60)

@Composable
fun SignalGraphScreen(vm: CanBusViewModel) {
    val pinnedKeys by vm.pinnedSignalKeys.collectAsState()
    val series by vm.signalSeries.collectAsState()
    val thresholds by vm.thresholds.collectAsState()
    val known by vm.knownMessages.collectAsState()
    val dbc by vm.activeDbc.collectAsState()

    var windowSeconds by remember { mutableStateOf(30) }
    var overlayMode by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.thresholdAlerts.collect { (key, value) ->
            val sigName = key.substringAfterLast("/")
            scope.launch {
                snackbarHostState.showSnackbar("⚠ $sigName crossed threshold: ${"%.2f".format(value)}")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (pinnedKeys.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No signals pinned.\nTap the chart icon on any signal in the Signals tab.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Controls row
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Window:", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WindowOptions.forEach { w ->
                            FilterChip(
                                selected = windowSeconds == w,
                                onClick = { windowSeconds = w },
                                label = { Text("${w}s", fontSize = 11.sp) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (pinnedKeys.size > 1) {
                            FilterChip(
                                selected = overlayMode,
                                onClick = { overlayMode = !overlayMode },
                                label = { Text("Overlay", fontSize = 11.sp) },
                            )
                        }
                    }
                }

                if (overlayMode && pinnedKeys.size > 1) {
                    item {
                        OverlayChartCard(
                            entries = pinnedKeys.mapIndexed { i, key ->
                                val pts = series[key] ?: emptyList()
                                Triple(key, pts, GraphColors[i % GraphColors.size])
                            },
                            windowMs = windowSeconds * 1000L,
                        )
                    }
                } else {
                    items(pinnedKeys) { key ->
                        val idx = pinnedKeys.indexOf(key)
                        val color = GraphColors[idx % GraphColors.size]
                        val msgName = key.substringBefore("/")
                        val sigName = key.substringAfterLast("/")
                        val sig = dbc?.messages?.values
                            ?.find { it.name == msgName }
                            ?.signals?.find { it.name == sigName }
                        val unit = sig?.unit ?: ""
                        val currentValue = known.values
                            .find { it.message.name == msgName }
                            ?.decodedSignals?.get(sigName)
                        val points = series[key] ?: emptyList()
                        val threshold = thresholds[key]

                        SignalChartCard(
                            signalKey = key,
                            sigName = sigName,
                            msgName = msgName,
                            unit = unit,
                            points = points,
                            windowMs = windowSeconds * 1000L,
                            currentValue = currentValue,
                            threshold = threshold,
                            color = color,
                            onUnpin = { vm.unpinSignal(key) },
                            onSetThreshold = { v -> vm.setThreshold(key, v) },
                            onClearThreshold = { vm.clearThreshold(key) },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SignalChartCard(
    signalKey: String,
    sigName: String,
    msgName: String,
    unit: String,
    points: List<Pair<Long, Double>>,
    windowMs: Long,
    currentValue: Double?,
    threshold: Double?,
    color: Color,
    onUnpin: () -> Unit,
    onSetThreshold: (Double) -> Unit,
    onClearThreshold: () -> Unit,
) {
    var showThresholdDialog by remember(signalKey) { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val tEnd = if (points.isNotEmpty()) maxOf(points.last().first, now) else now
    val tStart = tEnd - windowMs
    val visible = remember(points, tStart) { points.filter { it.first >= tStart } }

    val yMin = visible.minOfOrNull { it.second } ?: 0.0
    val yMax = visible.maxOfOrNull { it.second } ?: 1.0

    if (showThresholdDialog) {
        ThresholdDialog(
            currentThreshold = threshold,
            unit = unit,
            onSet = { v -> onSetThreshold(v); showThresholdDialog = false },
            onClear = { onClearThreshold(); showThresholdDialog = false },
            onDismiss = { showThresholdDialog = false },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(sigName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                    Text(msgName, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = { showThresholdDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "Set threshold",
                        tint = if (threshold != null) ThresholdColor
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onUnpin, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Unpin",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }

            // Chart area
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Y-axis labels
                Column(
                    modifier = Modifier.width(52.dp).height(120.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(formatAxisVal(yMax, unit), fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace)
                    Text(formatAxisVal(yMin, unit), fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(4.dp))
                Canvas(modifier = Modifier.weight(1f).height(120.dp)) {
                    val yRange = (yMax - yMin).coerceAtLeast(1e-9)
                    // Grid lines at 25 / 50 / 75 %
                    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                        val yPx = size.height * frac
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.2f),
                            start = Offset(0f, yPx), end = Offset(size.width, yPx),
                            strokeWidth = 0.5f,
                        )
                    }

                    // Threshold line
                    if (threshold != null) {
                        val yPx = (size.height * (1 - (threshold - yMin) / yRange)).toFloat()
                            .coerceIn(0f, size.height)
                        drawLine(
                            color = ThresholdColor,
                            start = Offset(0f, yPx), end = Offset(size.width, yPx),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f)),
                        )
                    }

                    // Signal line
                    drawLineChart(visible, tStart, tEnd, yMin, yMax, color)
                }
            }

            // Time axis labels
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 60.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("-${windowMs / 1000}s", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
                Text("now", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
            }

            // Readout strip
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val cur = currentValue?.let { "${"%.2f".format(it)} $unit".trim() } ?: "—"
                val mn  = "${"%.2f".format(yMin)} $unit".trim()
                val mx  = "${"%.2f".format(yMax)} $unit".trim()
                Text("Now: $cur", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color)
                Text("Min: $mn", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Max: $mx", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OverlayChartCard(
    entries: List<Triple<String, List<Pair<Long, Double>>, Color>>,
    windowMs: Long,
) {
    val now = System.currentTimeMillis()
    val tEnd = entries.mapNotNull { (_, pts, _) -> pts.lastOrNull()?.first }.maxOrNull()
        ?.let { maxOf(it, now) } ?: now
    val tStart = tEnd - windowMs

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                // Grid
                for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                    val yPx = size.height * frac
                    drawLine(Color.Gray.copy(alpha = 0.2f),
                        Offset(0f, yPx), Offset(size.width, yPx), strokeWidth = 0.5f)
                }
                // One normalized trace per entry
                for ((_, pts, color) in entries) {
                    val visible = pts.filter { it.first >= tStart }
                    if (visible.size < 2) continue
                    val yMin = visible.minOf { it.second }
                    val yMax = visible.maxOf { it.second }
                    drawLineChart(visible, tStart, tEnd, yMin, yMax, color, strokeWidth = 2f)
                }
            }
            // Time labels
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                val windowSeconds = (windowMs / 1000).toInt()
                Text("-${windowSeconds}s", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
                Text("now", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
            }
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                entries.forEach { (key, _, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier.size(10.dp)
                                .border(2.dp, color, MaterialTheme.shapes.extraSmall),
                        )
                        Text(key.substringAfterLast("/"), fontSize = 11.sp, color = color,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThresholdDialog(
    currentThreshold: Double?,
    unit: String,
    onSet: (Double) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentThreshold?.let { "%.4g".format(it) } ?: "") }
    val parsed = text.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Threshold${if (unit.isNotEmpty()) " ($unit)" else ""}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Value") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) {
                Text("Set")
            }
        },
        dismissButton = {
            Row {
                if (currentThreshold != null) {
                    TextButton(onClick = onClear) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun DrawScope.drawLineChart(
    points: List<Pair<Long, Double>>,
    tStart: Long,
    tEnd: Long,
    yMin: Double,
    yMax: Double,
    color: Color,
    strokeWidth: Float = 2f,
) {
    if (points.size < 2) return
    val tRange = (tEnd - tStart).coerceAtLeast(1L).toFloat()
    val yRange = (yMax - yMin).coerceAtLeast(1e-9)
    val path = Path()
    var first = true
    for ((ts, v) in points) {
        val x = ((ts - tStart).toFloat() / tRange) * size.width
        val y = (size.height * (1.0 - (v - yMin) / yRange)).toFloat().coerceIn(0f, size.height)
        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
    }
    drawPath(path, color = color,
        style = Stroke(width = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

private fun formatAxisVal(v: Double, unit: String): String {
    val s = if (kotlin.math.abs(v) >= 1000) "%.0f".format(v)
            else if (kotlin.math.abs(v) >= 10) "%.1f".format(v)
            else "%.2f".format(v)
    return if (unit.isNotEmpty()) "$s $unit" else s
}
