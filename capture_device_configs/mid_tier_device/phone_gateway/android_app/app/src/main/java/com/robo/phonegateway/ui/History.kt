package com.robo.phonegateway.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import com.robo.phonegateway.registry.TrackCache
import com.robo.phonegateway.registry.Units
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robo.phonegateway.RouteMap
import com.robo.phonegateway.registry.DriveSummary
import com.robo.phonegateway.registry.RegistryException
import com.robo.phonegateway.registry.RouteTrack
import com.robo.phonegateway.registry.SessionItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SUMMARY_WAIT_MS = 10 * 60_000L
private const val SUMMARY_POLL_MS = 20_000L
private const val HISTORY_PAGE = 30

/** The list's last page, so a session screen can show its title without another request. */
private object SessionCache {
    val byId = mutableMapOf<Long, SessionItem>()
}

/** The user's sessions, newest first, as cards with a route thumbnail. */
@Composable
fun HistoryScreen(app: App, onOpen: (Long) -> Unit) {
    val signedIn by app.auth.signedIn.collectAsStateWithLifecycle()
    if (!signedIn) {
        Text("Sign in (Drive tab) to see your sessions.", Modifier.padding(20.dp))
        return
    }
    val units by Units.state(LocalContext.current).collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun load(offset: Int) {
        loading = true
        try {
            val page = app.registry.sessions(HISTORY_PAGE, offset)
            page.forEach { SessionCache.byId[it.sessionId] = it }
            items = if (offset == 0) page else items + page
            more = page.size == HISTORY_PAGE
            error = null
        } catch (e: RegistryException) {
            error = e.message
        }
        loading = false
    }
    LaunchedEffect(reload) { load(0) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sessions", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { reload++ }, enabled = !loading) { Text("Refresh") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && items.isEmpty() && error == null) Text("No sessions yet.")
        }
        items(items, key = { it.sessionId }) { item -> SessionCard(app, item, units) { onOpen(item.sessionId) } }
        item {
            if (loading) {
                Text("loading…", style = MaterialTheme.typography.bodySmall)
            } else if (more && items.isNotEmpty()) {
                TextButton(onClick = { scope.launch { load(items.size) } }) { Text("Load more") }
            }
        }
    }
}

@Composable
private fun SessionCard(app: App, item: SessionItem, units: Units, onClick: () -> Unit) {
    val context = LocalContext.current
    var track by remember(item.sessionId) { mutableStateOf<RouteTrack?>(null) }
    LaunchedEffect(item.sessionId) {
        // only settled sessions with a summary have a route worth drawing (and caching)
        if (item.hasSummary) track = runCatching { TrackCache.get(context, app.registry, item.sessionId, settled = true) }.getOrNull()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(72.dp)) {
                track?.takeIf { !it.isEmpty }?.let { RouteThumbnail(it, units, Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(item.subtitle(units), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One session: its route (colour by any role, tap for that moment's values) and its summary. */
@Composable
fun SessionScreen(app: App, sessionId: Long) {
    val item = SessionCache.byId[sessionId]
    val context = LocalContext.current
    val units by Units.state(context).collectAsStateWithLifecycle()
    var track by remember(sessionId) { mutableStateOf<RouteTrack?>(null) }
    var trackNote by remember(sessionId) { mutableStateOf<String?>("loading route…") }
    var colorBy by remember { mutableStateOf(RouteTrack.ColorBy.SPEED) }
    var selected by remember(sessionId) { mutableStateOf<RouteTrack.Point?>(null) }
    var details by remember { mutableStateOf(false) }
    val summary = rememberSummary(app, sessionId)
    LaunchedEffect(sessionId) {
        trackNote = try {
            track = TrackCache.get(context, app.registry, sessionId, settled = item?.hasSummary == true)
            if (track!!.isEmpty) "no route for this session" else null
        } catch (e: RegistryException) {
            e.message
        }
    }

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(item?.title() ?: "Session $sessionId", style = MaterialTheme.typography.titleLarge)
        val t = track
        if (t != null && !t.isEmpty) {
            val geoJson = remember(t, colorBy, units) { t.geoJson(colorBy, units) }
            RouteMap(t, geoJson, selected, onTap = { selected = it }, modifier = Modifier.fillMaxWidth().height(340.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteTrack.ColorBy.entries.filter { t.has(it) }.forEach { by ->
                    FilterChip(selected = colorBy == by, onClick = { colorBy = by }, label = { Text(by.label) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                colorBy.legend(units, t).forEach { DotLabel(Color(android.graphics.Color.parseColor(it.color)), it.label) }
                if (colorBy == RouteTrack.ColorBy.SPEED) Text(units.speedUnit, style = MaterialTheme.typography.labelSmall)
            }
            Moment(selected, units)
        }
        trackNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = summary.refresh) { Text("Refresh") }
        }
        val s = summary.summary
        if (s == null) {
            Text(summary.note ?: "loading…", style = MaterialTheme.typography.bodySmall)
        } else {
            TileGrid(s.tiles(units))
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "All details") }
            if (details) s.lines(units).forEach { (label, value) -> StatusLine(label, value) }
        }
        StatusLine("Session", sessionId.toString())
        item?.let { StatusLine("State", it.state + (it.completenessPct?.let { p -> ", $p% arrived" } ?: "")) }
    }
}

/** The tapped second of the drive, or how to get one. */
@Composable
private fun Moment(p: RouteTrack.Point?, units: Units) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p == null) {
                Text("Tap the route to see that moment.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text("${DriveSummary.duration(p.tS)} into the drive", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(
                listOfNotNull(
                    p.kph?.let { units.speed(it) },
                    p.rpm?.let { "${it.toInt()} rpm" },
                    p.throttlePct?.let { "throttle ${it.toInt()}%" },
                    RouteTrack.ColorBy.GEAR.band(p, units)?.let { "gear ${it.label}" },
                    p.brakeOn?.let { if (it) "braking" else "brake off" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** A session's summary as it loads: the summary, a note while there is none, and a refresh. */
class SummaryState(val summary: DriveSummary?, val note: String?, val refresh: () -> Unit)

/**
 * Load a session's drive summary. drive-summary writes it a couple of minutes after
 * the session settles, so this polls until it appears (or SUMMARY_WAIT_MS passes).
 */
@Composable
fun rememberSummary(app: App, sessionId: Long): SummaryState {
    var summary by remember(sessionId) { mutableStateOf<DriveSummary?>(null) }
    var note by remember(sessionId) { mutableStateOf<String?>(null) }
    var attempt by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(sessionId, attempt) {
        val started = System.currentTimeMillis()
        while (summary == null && System.currentTimeMillis() - started < SUMMARY_WAIT_MS) {
            try {
                summary = app.registry.summary(sessionId)
                note = if (summary == null) "summary in a few minutes, once the drive has settled" else null
            } catch (e: RegistryException) {
                note = e.message
            }
            if (summary == null) delay(SUMMARY_POLL_MS)
        }
        if (summary == null) note = "no summary for this session"
    }
    return SummaryState(summary, note) { summary = null; attempt++ }
}

/** A session's drive summary: tiles, then every line. */
@Composable
fun SummaryView(app: App, sessionId: Long, title: String) {
    val state = rememberSummary(app, sessionId)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = state.refresh) { Text("Refresh") }
    }
    val s = state.summary
    if (s == null) {
        Text(state.note ?: "loading…", style = MaterialTheme.typography.bodySmall)
    } else {
        s.lines().forEach { (label, value) -> StatusLine(label, value) }
    }
}
