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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robo.phonegateway.TrackMap
import com.robo.phonegateway.Where
import com.robo.phonegateway.registry.AppTrack
import com.robo.phonegateway.registry.RegistryException
import com.robo.phonegateway.registry.TrackSearch
import com.robo.phonegateway.registry.Units
import kotlinx.coroutines.launch

/** Tracks as last listed, so a track screen opens without another request. */
object TrackList {
    var tracks: List<AppTrack> = emptyList()
}

private fun away(km: Double?, units: Units) = km?.let { units.distance(it) + " away" }

/** Every track, nearest first when the phone knows where it is. */
@Composable
fun TracksScreen(app: App, onOpen: (Int) -> Unit, onFind: () -> Unit) {
    val signedIn by app.auth.signedIn.collectAsStateWithLifecycle()
    if (!signedIn) {
        Text("Sign in (Drive tab) to see tracks.", Modifier.padding(20.dp))
        return
    }
    val context = LocalContext.current
    val units by Units.state(context).collectAsStateWithLifecycle()
    var tracks by remember { mutableStateOf(TrackList.tracks) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        try {
            tracks = app.registry.tracks(Where.now(context, timeoutMs = 5_000))
            TrackList.tracks = tracks
            error = null
        } catch (e: RegistryException) {
            error = e.message
        }
        loading = false
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tracks", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { reload++ }, enabled = !loading) { Text("Refresh") }
            }
            Button(onClick = onFind, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Find tracks nearby or by ZIP") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading && tracks.isEmpty()) Text("loading…", style = MaterialTheme.typography.bodySmall)
            if (!loading && tracks.isEmpty() && error == null) {
                Text("No tracks yet. Find one to add it.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(tracks, key = { it.id }) { t ->
            TrackCard(t.name, listOfNotNull(away(t.distanceKm, units), if (t.addedByMe) "added by you" else null,
                if (t.hasStartLine) "start line set" else "no start line").joinToString(" · "), t.outline,
                onClick = { onOpen(t.id) })
        }
    }
}

@Composable
private fun TrackCard(
    name: String, subtitle: String, outline: List<List<Pair<Double, Double>>>,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(64.dp)) {
                OutlineThumbnail(outline, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

/** Search OSM near the phone or a ZIP code, and add what's found. */
@Composable
fun FindTracksScreen(app: App) {
    val context = LocalContext.current
    val units by Units.state(context).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var byZip by remember { mutableStateOf(false) }
    var zip by remember { mutableStateOf("") }
    var radiusMi by remember { mutableIntStateOf(25) }
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<TrackSearch?>(null) }
    var added by remember { mutableStateOf(setOf<Int>()) }

    fun search() {
        scope.launch {
            searching = true
            result = null
            added = emptySet()
            note = if (byZip) "searching around $zip…" else "finding your location…"
            try {
                val near = if (byZip) null else Where.now(context)
                if (!byZip && near == null) {
                    note = "couldn't get a location fix; try by ZIP"
                } else {
                    if (!byZip) note = "searching OpenStreetMap…"
                    result = app.registry.discoverTracks(near, if (byZip) zip else null, (radiusMi * 1609.344).toInt())
                    note = result?.let { r ->
                        if (r.candidates.isEmpty()) "no tracks in OpenStreetMap within $radiusMi mi of ${r.originLabel}"
                        else "${r.candidates.size} found around ${r.originLabel}"
                    }
                }
            } catch (e: RegistryException) {
                note = e.message
            }
            searching = false
        }
    }

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byZip, onClick = { byZip = false }, label = { Text("Near me") })
            FilterChip(selected = byZip, onClick = { byZip = true }, label = { Text("By ZIP") })
        }
        if (byZip) {
            OutlinedTextField(
                zip, { zip = it.filter(Char::isDigit).take(5) }, label = { Text("US ZIP code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Within", style = MaterialTheme.typography.bodyMedium)
            listOf(25, 50, 100).forEach { mi ->
                val label = if (units == Units.IMPERIAL) "$mi mi" else "${(mi * 1.609344).toInt()} km"
                FilterChip(selected = radiusMi == mi, onClick = { radiusMi = mi }, label = { Text(label) })
            }
        }
        Button(
            onClick = ::search,
            enabled = !searching && (!byZip || zip.length == 5),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (searching) "Searching…" else "Search") }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        result?.let { r ->
            r.candidates.forEach { c ->
                val isAdded = c.alreadyImported || c.index in added
                TrackCard(
                    c.name, away(c.distanceKm, units) ?: "", c.outline,
                    trailing = {
                        if (isAdded) {
                            Text("Added", color = Status.Good, style = MaterialTheme.typography.labelLarge)
                        } else {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        val new = app.registry.addTracks(r.token, listOf(c.index))
                                        added = added + c.index
                                        app.message = if (new.isEmpty()) "${c.name} is already a track" else "added ${c.name}"
                                    } catch (e: RegistryException) {
                                        app.message = "could not add ${c.name}: ${e.message}"
                                    }
                                }
                            }) { Text("Add") }
                        }
                    },
                )
            }
        }
    }
}

/** One track: its outline on the map, and what's known about it. */
@Composable
fun TrackScreen(trackId: Int) {
    val t = TrackList.tracks.firstOrNull { it.id == trackId }
    val units by Units.state(LocalContext.current).collectAsStateWithLifecycle()
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (t == null) {
            Text("Track $trackId isn't in the list; go back and refresh.")
            return@Column
        }
        Text(t.name, style = MaterialTheme.typography.titleLarge)
        t.geometry?.let { TrackMap(it, t.outline, t.startLine, Modifier.fillMaxWidth().height(360.dp)) }
        away(t.distanceKm, units)?.let { StatusLine("Distance", it) }
        StatusLine("Start line", if (t.hasStartLine) "set" else "not set; Mark start/finish in a race sets one")
        StatusLine("Source", "OpenStreetMap" + if (t.addedByMe) ", added by you" else "")
    }
}
