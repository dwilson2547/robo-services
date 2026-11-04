package com.robo.phonegateway.ui

import androidx.compose.foundation.clickable
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

/** The user's sessions, newest first. */
@Composable
fun HistoryScreen(app: App, onOpen: (Long) -> Unit) {
    val signedIn by app.auth.signedIn.collectAsStateWithLifecycle()
    if (!signedIn) {
        Text("Sign in (Drive tab) to see your sessions.", Modifier.padding(20.dp))
        return
    }
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sessions", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { reload++ }, enabled = !loading) { Text("Refresh") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && items.isEmpty() && error == null) Text("No sessions yet.")
        }
        items(items, key = { it.sessionId }) { item ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(item.sessionId) }.padding(vertical = 8.dp)) {
                Text(item.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item {
            if (loading) {
                Text("loading…", style = MaterialTheme.typography.bodySmall)
            } else if (more && items.isNotEmpty()) {
                TextButton(onClick = { scope.launch { load(items.size) } }) { Text("Load more") }
            }
        }
    }
}

/** One session: its route and its summary. */
@Composable
fun SessionScreen(app: App, sessionId: Long) {
    val item = SessionCache.byId[sessionId]
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(item?.title() ?: "Session $sessionId", style = MaterialTheme.typography.titleLarge)
        RouteSection(app, sessionId)
        SummaryView(app, sessionId, "Summary")
        StatusLine("Session", sessionId.toString())
        item?.let { StatusLine("State", it.state + (it.completenessPct?.let { p -> ", $p% arrived" } ?: "")) }
    }
}

/** A session's route on a map, coloured by speed, with a legend. */
@Composable
fun RouteSection(app: App, sessionId: Long) {
    var track by remember(sessionId) { mutableStateOf<RouteTrack?>(null) }
    var note by remember(sessionId) { mutableStateOf<String?>("loading route…") }
    LaunchedEffect(sessionId) {
        note = try {
            track = app.registry.track(sessionId)
            if (track!!.isEmpty) "no route for this session" else null
        } catch (e: RegistryException) {
            e.message
        }
    }
    val t = track
    if (t != null && !t.isEmpty) {
        RouteMap(t, Modifier.fillMaxWidth().height(320.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteTrack.Band.entries.forEach { band ->
                DotLabel(Color(android.graphics.Color.parseColor(band.color)), band.label)
            }
            Text("mph", style = MaterialTheme.typography.labelSmall)
        }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
