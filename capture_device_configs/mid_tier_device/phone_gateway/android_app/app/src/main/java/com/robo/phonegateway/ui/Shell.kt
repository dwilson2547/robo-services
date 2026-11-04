package com.robo.phonegateway.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.robo.phonegateway.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robo.phonegateway.GatewayStatus
import com.robo.phonegateway.telemetry.LapAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// three Material icons as drawables (res/drawable/ic_tab_*): the extended icon set doubles a debug APK
private data class TabDef(val route: String, val label: String, val icon: Int)

private val TABS = listOf(
    TabDef("drive", "Drive", R.drawable.ic_tab_drive),
    TabDef("history", "History", R.drawable.ic_tab_history),
    TabDef("tracks", "Tracks", R.drawable.ic_tab_tracks),
)

private val TITLES = mapOf(
    "history/{id}" to "Session",
    "settings" to "Settings",
    "settings/diagnostics" to "Diagnostics",
    "tracks/find" to "Find tracks",
    "tracks/{id}" to "Track",
)

/** One status light: what it shows and what to say when tapped. */
private data class Light(val label: String, val color: Color, val explain: String)

private fun lights(s: GatewayStatus.Snapshot, now: Long): List<Light> {
    val dataAgeMs = if (s.lastDongleDataMs == 0L) Long.MAX_VALUE else now - s.lastDongleDataMs
    val dongle = when {
        s.dongleConnected && dataAgeMs <= 3_000 -> Light("Dongle", Status.Good, "Dongle connected, data flowing")
        s.dongleConnected -> Light("Dongle", Status.Warn, "Dongle connected, no data for ${dataAgeMs / 1000} s")
        else -> Light("Dongle", Status.Bad, "Dongle not connected (${s.dongle})")
    }
    val fixAge = now - s.lastFixMs
    val gps = when {
        !s.running -> Light("GPS", Status.Idle, "GPS runs while capturing")
        s.lastFixMs > 0 && fixAge <= LapAnchor.MAX_FIX_AGE_MS -> Light("GPS", Status.Good, "GPS fix is fresh")
        else -> Light("GPS", Status.Warn, if (s.lastFixMs == 0L) "Waiting for a GPS fix" else "No GPS fix for ${fixAge / 1000} s")
    }
    val broker = when {
        s.broker.startsWith("connected") -> Light("Broker", Status.Good, "Uploading: ${s.broker}")
        s.broker == "idle" || s.broker == "stopped" -> Light("Broker", Status.Idle, "Broker connects while capturing")
        s.broker.startsWith("connecting") || s.broker.startsWith("finishing") -> Light("Broker", Status.Warn, s.broker)
        else -> Light("Broker", Status.Bad, s.broker)
    }
    return listOf(dongle, gps, broker)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shell(app: App) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val onTab = TABS.any { it.route == route }
    val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    LaunchedEffect(app.message) {
        app.message?.let {
            app.message = null
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (onTab) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            lights(status, now).forEach { light ->
                                Row(Modifier.clickable { scope.launch { snackbar.showSnackbar(light.explain) } }) {
                                    DotLabel(light.color, light.label)
                                }
                            }
                        }
                    } else {
                        Text(TITLES[route] ?: "")
                    }
                },
                navigationIcon = {
                    if (!onTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onTab) {
                        IconButton(onClick = { nav.navigate("settings") }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(tab.icon), contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = "drive", modifier = Modifier.padding(padding)) {
            composable("drive") { DriveScreen(app, now) { nav.navigate("history/$it") } }
            composable("history") { HistoryScreen(app) { nav.navigate("history/$it") } }
            composable("history/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                SessionScreen(app, it.arguments!!.getLong("id"))
            }
            composable("tracks") {
                TracksScreen(app, onOpen = { nav.navigate("tracks/$it") }, onFind = { nav.navigate("tracks/find") })
            }
            composable("tracks/find") { FindTracksScreen(app) }
            composable("tracks/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                TrackScreen(it.arguments!!.getInt("id"))
            }
            composable("settings") { SettingsScreen(app) { nav.navigate("settings/diagnostics") } }
            composable("settings/diagnostics") { DiagnosticsScreen() }
        }
    }
}
