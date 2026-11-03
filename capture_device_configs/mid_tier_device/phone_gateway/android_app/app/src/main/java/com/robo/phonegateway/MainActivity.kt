package com.robo.phonegateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.robo.phonegateway.registry.RouteTrack
import com.robo.phonegateway.registry.SessionItem
import androidx.compose.foundation.layout.height
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.CanFilter
import com.robo.phonegateway.ble.DongleClient
import com.robo.phonegateway.registry.DbcChoice
import com.robo.phonegateway.registry.DriveSummary
import com.robo.phonegateway.registry.RegistryClient
import com.robo.phonegateway.registry.RegistryException
import com.robo.phonegateway.registry.Vehicle
import com.robo.phonegateway.registry.VehicleStore
import com.robo.phonegateway.session.LastSession
import com.robo.phonegateway.telemetry.LapAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the Last drive card keeps asking for a summary, and how often. */
private const val SUMMARY_WAIT_MS = 10 * 60_000L
private const val SUMMARY_POLL_MS = 20_000L
private const val HISTORY_PAGE = 30

class MainActivity : ComponentActivity() {

    private lateinit var auth: Auth
    private lateinit var vehicles: VehicleStore
    private lateinit var registry: RegistryClient
    /** Reads and writes the dongle filter (shared prefs with the service's client). */
    private val dongleSettings by lazy { DongleClient(this, { _, _ -> }, {}) }
    private var message by mutableStateOf<String?>(null)
    /** Bumped when the filter changes outside the text field (vehicle picked). */
    private var filterVersion by mutableIntStateOf(0)

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: run {
            message = "sign-in cancelled"
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            message = runCatching { auth.completeSignIn(data) }
                .fold({ null }, { "sign-in failed: ${it.message}" })
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) GatewayService.link(this)
        else message = "the gateway needs Bluetooth, location and notifications"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Auth.get(this)
        vehicles = VehicleStore.get(this)
        registry = RegistryClient(auth)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // connect to the dongle while the screen is up, so its state shows before capture
        val missing = neededPermissions()
        if (missing.isEmpty()) GatewayService.link(this) else permissions.launch(missing.toTypedArray())
    }

    override fun onStop() {
        // stops the service too unless capturing, which frees the dongle for other apps
        if (!isChangingConfigurations) GatewayService.unlink(this)
        super.onStop()
    }

    @Composable
    private fun Screen() {
        val signedIn by auth.signedIn.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Capture") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, enabled = signedIn, text = { Text("History") })
            }
            if (tab == 1 && signedIn) HistoryTab() else CaptureTab()
        }
    }

    @Composable
    private fun CaptureTab() {
        val signedIn by auth.signedIn.collectAsStateWithLifecycle()
        val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
        val fixFresh = status.lastFixMs > 0 && now - status.lastFixMs <= LapAnchor.MAX_FIX_AGE_MS

        Column(
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Phone Gateway", style = MaterialTheme.typography.headlineSmall)
            Text("Device ${GatewayService.deviceId(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)

            DongleIndicator(status, now)
            if (signedIn) VehicleSection(status)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signedIn) {
                    OutlinedButton(onClick = { lifecycleScope.launch { auth.signOut() } }) { Text("Sign out") }
                } else {
                    Button(onClick = { startSignIn() }) { Text("Sign in") }
                }
                if (status.running) {
                    Button(onClick = { GatewayService.stopCapture(this@MainActivity) }) { Text("Stop capture") }
                } else {
                    Button(onClick = { startCapture() }, enabled = signedIn) { Text("Start capture") }
                }
            }

            if (status.running) {
                Button(
                    onClick = { GatewayService.markStart(this@MainActivity) },
                    enabled = fixFresh,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mark start/finish here") }
                Text(
                    when {
                        !fixFresh -> "waiting for a GPS fix"
                        status.lastAnchor != null -> "Last anchor: ${status.lastAnchor}"
                        else -> "Lap timing starts once a start/finish is marked"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (signedIn && !status.running) LastDriveCard(status)

            HorizontalDivider()
            if (status.sessionId != 0L) {
                StatusLine("Session", "${status.sessionId}" + if (status.running) "" else " (ended)")
            }
            StatusLine("Broker", status.broker)
            StatusLine("Frames logged", status.framesLogged.toString())
            StatusLine("Frames acked", status.framesAcked.toString())
            StatusLine("Frames not sent", status.framesNotSent.toString())
            StatusLine("IMU seconds dropped", status.imuIncomplete.toString())
            StatusLine("IMU points filled", status.imuMissingPoints.toString())
            StatusLine("IMU gaps", "dongle ${status.imuDongleGaps}, clock ${status.imuClockJumps}")
            StatusLine("IMU last second", "${status.lastImuSamples} samples, max gap ${status.lastImuMaxGapMs} ms")
            StatusLine("Late samples dropped", status.lateSamples.toString())
            StatusLine("Unparsed dongle lines", status.badLines.toString())
            StatusLine("Cut-off notifications", status.truncatedNotifications.toString())
            StatusLine(
                "BLE link",
                (if (status.bleMtu == 0) "MTU -" else "MTU ${status.bleMtu}") +
                    when (status.blePhy) { 1 -> ", 1M PHY"; 2 -> ", 2M PHY"; 3 -> ", coded PHY"; else -> "" },
            )
            status.dongleStats?.let { StatusLine("Dongle 5 s", it) }
            (message ?: status.lastError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            DongleSettings(status)
        }
    }

    /** The last finished capture's drive summary. */
    @Composable
    private fun LastDriveCard(status: GatewayStatus.Snapshot) {
        val sessionId = status.sessionId.takeIf { it != 0L } ?: LastSession.get(this)
        if (sessionId == 0L) return
        HorizontalDivider()
        SummaryView(sessionId, "Last drive")
    }

    /**
     * A session's drive summary. drive-summary writes it a couple of minutes after the
     * session settles, so this polls until it appears (or SUMMARY_WAIT_MS passes).
     */
    @Composable
    private fun SummaryView(sessionId: Long, title: String) {
        var summary by remember(sessionId) { mutableStateOf<DriveSummary?>(null) }
        var note by remember(sessionId) { mutableStateOf<String?>(null) }
        var attempt by remember(sessionId) { mutableIntStateOf(0) }
        LaunchedEffect(sessionId, attempt) {
            val started = System.currentTimeMillis()
            while (summary == null && System.currentTimeMillis() - started < SUMMARY_WAIT_MS) {
                try {
                    summary = registry.summary(sessionId)
                    note = if (summary == null) "summary in a few minutes, once the drive has settled" else null
                } catch (e: RegistryException) {
                    note = e.message
                }
                if (summary == null) delay(SUMMARY_POLL_MS)
            }
            if (summary == null) note = "no summary for this session"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { summary = null; attempt++ }) { Text("Refresh") }
        }
        val s = summary
        if (s == null) {
            Text(note ?: "loading…", style = MaterialTheme.typography.bodySmall)
        } else {
            s.lines().forEach { (label, value) -> StatusLine(label, value) }
        }
    }

    /** The session's route on a map, coloured by speed, with a legend. */
    @Composable
    private fun RouteSection(sessionId: Long) {
        var track by remember(sessionId) { mutableStateOf<RouteTrack?>(null) }
        var note by remember(sessionId) { mutableStateOf<String?>("loading route…") }
        LaunchedEffect(sessionId) {
            note = try {
                track = registry.track(sessionId)
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(12.dp).background(Color(android.graphics.Color.parseColor(band.color)), CircleShape))
                        Text(band.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("mph", style = MaterialTheme.typography.bodySmall)
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    /** The user's sessions, newest first; tapping one opens its summary. */
    @Composable
    private fun HistoryTab() {
        var items by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var more by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var open by remember { mutableStateOf<SessionItem?>(null) }
        var reload by remember { mutableIntStateOf(0) }

        suspend fun load(offset: Int) {
            loading = true
            try {
                val page = registry.sessions(HISTORY_PAGE, offset)
                items = if (offset == 0) page else items + page
                more = page.size == HISTORY_PAGE
                error = null
            } catch (e: RegistryException) {
                error = e.message
            }
            loading = false
        }
        LaunchedEffect(reload) { load(0) }

        val selected = open
        if (selected != null) {
            BackHandler { open = null }
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { open = null }) { Text("← All sessions") }
                Text(selected.title(), style = MaterialTheme.typography.titleLarge)
                RouteSection(selected.sessionId)
                SummaryView(selected.sessionId, "Summary")
                StatusLine("Session", selected.sessionId.toString())
                StatusLine("State", selected.state + (selected.completenessPct?.let { ", $it% arrived" } ?: ""))
            }
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
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
                Column(
                    Modifier.fillMaxWidth().clickable { open = item }.padding(vertical = 8.dp),
                ) {
                    Text(item.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(item.subtitle(), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            item {
                if (loading) {
                    Text("loading…", style = MaterialTheme.typography.bodySmall)
                } else if (more && items.isNotEmpty()) {
                    TextButton(onClick = { lifecycleScope.launch { load(items.size) } }) { Text("Load more") }
                }
            }
        }
    }

    @Composable
    private fun VehicleSection(status: GatewayStatus.Snapshot) {
        val list by vehicles.vehicles.collectAsStateWithLifecycle()
        val selectedId by vehicles.selectedId.collectAsStateWithLifecycle()
        var menuOpen by remember { mutableStateOf(false) }
        var adding by remember { mutableStateOf(false) }
        val selected = list.firstOrNull { it.id == selectedId }

        LaunchedEffect(Unit) { refreshVehicles() }
        // the dongle usually lives in one car: preselect the car it was last used in
        LaunchedEffect(status.dongleConnected) {
            if (status.dongleConnected && !status.running) {
                val car = dongleSettings.rememberedAddress?.let { vehicles.vehicleForDongle(it) }
                if (car != null && car.id != vehicles.selectedId.value) selectVehicle(car)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vehicle:", style = MaterialTheme.typography.bodyLarge)
            Box {
                OutlinedButton(onClick = { menuOpen = true }, enabled = !status.running) {
                    Text(selected?.label() ?: "choose…")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    list.sortedBy { it.name }.forEach { v ->
                        DropdownMenuItem(text = { Text(v.label()) }, onClick = {
                            menuOpen = false
                            selectVehicle(v)
                        })
                    }
                    DropdownMenuItem(text = { Text("Add vehicle…") }, onClick = {
                        menuOpen = false
                        adding = true
                    })
                    DropdownMenuItem(text = { Text("Refresh list") }, onClick = {
                        menuOpen = false
                        refreshVehicles()
                    })
                }
            }
        }
        if (adding) AddVehicleDialog(onDismiss = { adding = false })
    }

    @Composable
    private fun AddVehicleDialog(onDismiss: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var year by remember { mutableStateOf("") }
        var make by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        var dbcs by remember { mutableStateOf<List<DbcChoice>>(emptyList()) }
        var dbc by remember { mutableStateOf<DbcChoice?>(null) }
        var dbcMenu by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { dbcs = runCatching { registry.dbcs() }.getOrDefault(emptyList()) }
        val yearOk = year.isBlank() || year.toIntOrNull()?.let { it in 1900..2100 } == true
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add vehicle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true, isError = !yearOk)
                    OutlinedTextField(make, { make = it }, label = { Text("Make") }, singleLine = true)
                    OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true)
                    Box {
                        OutlinedButton(onClick = { dbcMenu = true }, enabled = dbcs.isNotEmpty()) {
                            Text("DBC: " + (dbc?.name ?: if (dbcs.isEmpty()) "none available" else "default"))
                        }
                        DropdownMenu(expanded = dbcMenu, onDismissRequest = { dbcMenu = false }) {
                            DropdownMenuItem(text = { Text("default") }, onClick = { dbc = null; dbcMenu = false })
                            dbcs.forEach { d ->
                                DropdownMenuItem(text = { Text(d.name) }, onClick = { dbc = d; dbcMenu = false })
                            }
                        }
                    }
                    Text("Starts with the current CAN filter; Apply filter later updates it.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && yearOk, onClick = {
                    val draft = Vehicle(
                        id = 0, name = name.trim(), year = year.trim().toIntOrNull(),
                        make = make.trim().ifEmpty { null }, model = model.trim().ifEmpty { null },
                        canFilter = dongleSettings.filterIds,
                        dbcId = dbc?.id,
                    )
                    onDismiss()
                    lifecycleScope.launch {
                        try {
                            val created = registry.create(draft)
                            vehicles.upsert(created)
                            selectVehicle(created)
                            message = "added ${created.name}"
                        } catch (e: RegistryException) {
                            message = "could not add vehicle: ${e.message}"
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    /** Pick a vehicle: its filter becomes the dongle's, at once if connected. */
    private fun selectVehicle(v: Vehicle) {
        vehicles.select(v.id)
        dongleSettings.filterIds = v.canFilter
        filterVersion++
        GatewayService.applyFilter(this)
    }

    private fun refreshVehicles() {
        lifecycleScope.launch {
            try {
                vehicles.replaceAll(registry.vehicles())
            } catch (e: RegistryException) {
                // the cached list still works offline; say so only if there is none
                if (vehicles.vehicles.value.isEmpty()) message = "vehicles: ${e.message}"
            }
        }
    }

    private fun saveVehicle(v: Vehicle) {
        vehicles.upsert(v)
        lifecycleScope.launch {
            try {
                vehicles.upsert(registry.update(v))
            } catch (e: RegistryException) {
                message = "filter applied, but not saved to ${v.name}: ${e.message}"
            }
        }
    }

    @Composable
    private fun DongleIndicator(status: GatewayStatus.Snapshot, now: Long) {
        val dataAgeMs = if (status.lastDongleDataMs == 0L) Long.MAX_VALUE else now - status.lastDongleDataMs
        val (color, label) = when {
            status.dongleConnected && dataAgeMs <= 3_000 -> Color(0xFF2E7D32) to "Dongle connected"
            status.dongleConnected -> Color(0xFFF9A825) to "Dongle connected, no data for ${dataAgeMs / 1000} s"
            else -> Color(0xFFC62828) to "Dongle not connected (${status.dongle})"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun DongleSettings(status: GatewayStatus.Snapshot) {
        val settings = dongleSettings
        var filterText by remember(filterVersion) { mutableStateOf(CanFilter.format(settings.filterIds)) }
        var saved by remember(filterVersion) { mutableStateOf(settings.filterIds) }
        var confirmForget by remember { mutableStateOf(false) }
        val parsed = CanFilter.parse(filterText)

        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            label = { Text("CAN ids to forward (hex; empty = all)") },
            isError = parsed == null,
            supportingText = {
                Text(
                    when {
                        parsed == null -> "hex CAN ids, at most ${CanFilter.MAX_IDS}"
                        parsed.isEmpty() -> "dongle forwards every id"
                        else -> "dongle forwards ${parsed.size} ids (firmware 1.6.0+)"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val ids = parsed ?: return@OutlinedButton
                    settings.filterIds = ids
                    saved = ids
                    GatewayService.applyFilter(this@MainActivity) // takes effect now if connected
                    message = if (ids.isEmpty()) "filter cleared" else "filter applied: ${ids.size} ids"
                    vehicles.selected?.let { v -> saveVehicle(v.copy(canFilter = ids)) } // the filter is the vehicle's
                },
                enabled = parsed != null && parsed != saved,
            ) { Text("Apply filter") }
            OutlinedButton(
                onClick = {
                    if (confirmForget) {
                        GatewayService.forgetDongle(this@MainActivity)
                        message = "dongle forgotten; the next one seen is used"
                        confirmForget = false
                    } else {
                        confirmForget = true
                    }
                },
                enabled = !status.running,
            ) { Text(if (confirmForget) "Tap again to forget" else "Forget dongle") }
        }
    }

    @Composable
    private fun StatusLine(label: String, value: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label:", style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }

    private fun startSignIn() {
        message = null
        lifecycleScope.launch {
            runCatching { auth.signInIntent() }
                .onSuccess { signIn.launch(it) }
                .onFailure { message = "can't reach authentik: ${it.message}" }
        }
    }

    private fun startCapture() {
        message = null
        if (neededPermissions().isEmpty()) GatewayService.startCapture(this)
        else permissions.launch(neededPermissions().toTypedArray())
    }

    private fun neededPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
}
