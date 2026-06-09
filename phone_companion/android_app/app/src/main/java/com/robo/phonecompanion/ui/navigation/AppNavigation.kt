package com.robo.phonecompanion.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robo.phonecompanion.ui.screen.FrameInspectorScreen
import com.robo.phonecompanion.ui.screen.LiveScreen
import com.robo.phonecompanion.ui.screen.LogBrowserScreen
import com.robo.phonecompanion.ui.screen.SettingsScreen
import com.robo.phonecompanion.ui.screen.SignalEditorScreen
import com.robo.phonecompanion.ui.screen.SignalsScreen
import com.robo.phonecompanion.ui.screen.UnknownsScreen
import com.robo.phonecompanion.ui.screen.settings.DbcListScreen
import com.robo.phonecompanion.ui.screen.settings.FirmwareUpdateScreen
import com.robo.phonecompanion.ui.screen.settings.GitConfigScreen
import com.robo.phonecompanion.ui.screen.settings.VehicleDetailScreen
import com.robo.phonecompanion.ui.screen.settings.VehicleEditScreen
import com.robo.phonecompanion.ui.screen.settings.VehicleListScreen
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.ConnectionState
import com.robo.phonecompanion.vm.SettingsViewModel

private sealed class Tab(val route: String, val label: String) {
    object Live : Tab("live", "Live")
    object Signals : Tab("signals", "Signals")
    object Unknowns : Tab("unknowns", "Unknowns")
}

private val tabs = listOf(Tab.Live, Tab.Signals, Tab.Unknowns)
private val tabRoutes = tabs.map { it.route }.toSet()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(canBusVm: CanBusViewModel, settingsVm: SettingsViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTabScreen = currentRoute in tabRoutes

    val connectionState by canBusVm.connectionState.collectAsState()
    val isFrozen by canBusVm.isFrozen.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val (label, color) = when (connectionState) {
                        is ConnectionState.Disconnected -> "Disconnected" to ColorUnknown
                        is ConnectionState.Scanning -> "Scanning…" to ColorActive
                        is ConnectionState.Connecting ->
                            "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName}" to ColorActive
                        is ConnectionState.Connected -> {
                            val s = connectionState as ConnectionState.Connected
                            "${s.deviceName}  ${s.rssi} dBm  ${"%.0f".format(s.frameRateHz)} fps" to ColorActive
                        }
                    }
                    Text(label, color = color, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                },
                navigationIcon = {
                    if (!onTabScreen) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (connectionState is ConnectionState.Disconnected) {
                        IconButton(onClick = { canBusVm.startScan() }) {
                            Icon(Icons.Default.Bluetooth, contentDescription = "Connect",
                                tint = Color.White)
                        }
                    }
                    IconButton(onClick = { canBusVm.toggleFreeze() }) {
                        Icon(
                            if (isFrozen) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isFrozen) "Resume" else "Freeze",
                            tint = if (isFrozen) ColorActive else Color.White,
                        )
                    }
                    if (onTabScreen) {
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            if (onTabScreen) {
                NavigationBar {
                    val current = backStack?.destination
                    tabs.forEach { tab ->
                        val icon = when (tab) {
                            Tab.Live -> Icons.Default.Cable
                            Tab.Signals -> Icons.Default.List
                            Tab.Unknowns -> Icons.Default.Search
                        }
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavHost(navController = navController, startDestination = Tab.Live.route) {
                composable(Tab.Live.route) {
                    LiveScreen(canBusVm, settingsVm)
                }
                composable(Tab.Signals.route) {
                    SignalsScreen(
                        vm = canBusVm,
                        settingsVm = settingsVm,
                        onEditSignal = { rawId, sigName ->
                            navController.navigate("editor/$rawId/$sigName")
                        },
                        onNewSignal = { rawId ->
                            navController.navigate("editor/$rawId/new")
                        },
                    )
                }
                composable(Tab.Unknowns.route) {
                    UnknownsScreen(
                        vm = canBusVm,
                        onDefineSignal = { canId ->
                            navController.navigate("editor/$canId/new")
                        },
                        onInspect = { canId ->
                            navController.navigate("inspector/$canId")
                        },
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        vm = settingsVm,
                        onNavigateGit = { navController.navigate("settings/git") },
                        onNavigateVehicles = { navController.navigate("settings/vehicles") },
                        onNavigateDbcs = { navController.navigate("settings/dbcs") },
                        onNavigateFirmware = { navController.navigate("settings/firmware") },
                    )
                }
                composable("settings/firmware") {
                    FirmwareUpdateScreen(vm = canBusVm)
                }
                composable("settings/git") { GitConfigScreen(settingsVm) }
                composable("settings/vehicles") {
                    VehicleListScreen(
                        vm = settingsVm,
                        onVehicleDetail = { id ->
                            navController.navigate("settings/vehicles/detail/$id")
                        },
                        onNewVehicle = { navController.navigate("settings/vehicles/new") },
                    )
                }
                composable(
                    "settings/vehicles/detail/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { back ->
                    val id = back.arguments?.getString("id") ?: return@composable
                    VehicleDetailScreen(
                        vm = settingsVm,
                        vehicleId = id,
                        onEditVehicle = { navController.navigate("settings/vehicles/edit/$id") },
                        onOpenSession = { sessionId -> navController.navigate("log/$sessionId") },
                    )
                }
                composable("settings/vehicles/new") {
                    VehicleEditScreen(
                        vm = settingsVm,
                        vehicleId = null,
                        onSaved = { navController.popBackStack() },
                    )
                }
                composable(
                    "settings/vehicles/edit/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { back ->
                    VehicleEditScreen(
                        vm = settingsVm,
                        vehicleId = back.arguments?.getString("id"),
                        onSaved = { navController.popBackStack() },
                    )
                }
                composable(
                    "log/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { back ->
                    val sessionId = back.arguments?.getString("sessionId") ?: return@composable
                    LogBrowserScreen(sessionId = sessionId)
                }
                composable("settings/dbcs") {
                    DbcListScreen(vm = settingsVm, canBusVm = canBusVm)
                }
                // editor/{rawId}/{signalName} — rawId is the decimal DBC raw ID, signalName is
                // the signal to edit or the literal "new" to create a new signal
                composable(
                    "editor/{rawId}/{signalName}",
                    arguments = listOf(
                        navArgument("rawId") { type = NavType.IntType },
                        navArgument("signalName") { type = NavType.StringType },
                    ),
                ) { back ->
                    val rawId = back.arguments?.getInt("rawId")
                    val sigName = back.arguments?.getString("signalName")
                        ?.takeIf { it != "new" }
                    SignalEditorScreen(
                        canBusVm = canBusVm,
                        settingsVm = settingsVm,
                        rawId = rawId,
                        signalName = sigName,
                        onSaved = { navController.popBackStack() },
                    )
                }
                composable(
                    "inspector/{canId}",
                    arguments = listOf(navArgument("canId") { type = NavType.IntType }),
                ) { back ->
                    val canId = back.arguments?.getInt("canId") ?: return@composable
                    FrameInspectorScreen(
                        vm = canBusVm,
                        canId = canId,
                        onDefineSignal = { navController.navigate("editor/$canId/new") },
                    )
                }
            }
        }
    }
}
