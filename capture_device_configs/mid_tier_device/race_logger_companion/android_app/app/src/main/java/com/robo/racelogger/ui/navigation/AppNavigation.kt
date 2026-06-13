package com.robo.racelogger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.robo.racelogger.ui.screen.ConfigScreen
import com.robo.racelogger.ui.screen.ScanScreen
import com.robo.racelogger.ui.screen.StatusScreen
import com.robo.racelogger.ui.screen.WifiConfigScreen
import com.robo.racelogger.vm.BleState
import com.robo.racelogger.vm.RaceLoggerViewModel

private const val ROUTE_SCAN        = "scan"
private const val ROUTE_STATUS      = "status"
private const val ROUTE_CONFIG      = "config"
private const val ROUTE_WIFI_CONFIG = "wifi_config"

@Composable
fun AppNavigation(vm: RaceLoggerViewModel) {
    val navController = rememberNavController()
    val bleState by vm.bleState.collectAsState()
    val currentRoute by navController.currentBackStackEntryAsState()

    // Navigate to status when BLE connects, and back to scan when it drops
    LaunchedEffect(bleState) {
        val route = currentRoute?.destination?.route
        when {
            bleState == BleState.CONNECTED && route == ROUTE_SCAN -> {
                navController.navigate(ROUTE_STATUS) {
                    popUpTo(ROUTE_SCAN) { inclusive = true }
                }
            }
            bleState == BleState.DISCONNECTED && route != ROUTE_SCAN -> {
                navController.navigate(ROUTE_SCAN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_SCAN) {
        composable(ROUTE_SCAN) {
            ScanScreen(vm = vm)
        }
        composable(ROUTE_STATUS) {
            StatusScreen(
                vm = vm,
                onConfigClick = { navController.navigate(ROUTE_CONFIG) },
                onWifiClick   = { navController.navigate(ROUTE_WIFI_CONFIG) },
            )
        }
        composable(ROUTE_CONFIG) {
            ConfigScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_WIFI_CONFIG) {
            WifiConfigScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
