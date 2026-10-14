package com.mapmyroad.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mapmyroad.ui.home.HomeRoute
import com.mapmyroad.ui.live.LiveTripRoute
import com.mapmyroad.ui.map.MapScreen
import com.mapmyroad.ui.settings.AboutScreen
import com.mapmyroad.ui.settings.BatteryGuidanceScreen
import com.mapmyroad.ui.settings.PrivacyRoute
import com.mapmyroad.ui.settings.SettingsRoute
import com.mapmyroad.ui.summary.TripSummaryRoute
import com.mapmyroad.ui.trips.TripsRoute
import kotlinx.coroutines.flow.Flow

/**
 * Main shell: bottom navigation with the four top-level tabs, plus full-screen routes ([AppRoutes]) that hide
 * the bottom bar. The outer Scaffold applies no system insets; every screen handles its own top inset.
 */
@Composable
fun MapMyRoadNavHost(
    finishedTrips: Flow<String>,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isBottomBarVisible =
        currentDestination == null || TopLevelDestination.entries.any { currentDestination.isOnTab(it) }

    // A finished trip opens its summary, replacing the live trip screen if it is showing (PRD flow 7.2).
    LaunchedEffect(navController, finishedTrips) {
        finishedTrips.collect { tripId ->
            val isOnLiveTrip = navController.currentDestination?.route == AppRoutes.LIVE_TRIP
            navController.navigate(AppRoutes.tripSummary(tripId)) {
                if (isOnLiveTrip) {
                    popUpTo(AppRoutes.LIVE_TRIP) { inclusive = true }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isBottomBarVisible) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination.isOnTab(destination),
                            onClick = { navController.navigateToTab(destination) },
                            // The visible label already names the tab, so the icon is decorative for TalkBack.
                            icon = { Icon(imageVector = destination.icon(), contentDescription = null) },
                            label = { Text(text = stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.start.route,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeRoute(
                    onOpenLiveTrip = { navController.openLiveTrip() },
                    onOpenTripSummary = { tripId -> navController.navigate(AppRoutes.tripSummary(tripId)) },
                    onOpenBatteryGuidance = { navController.navigate(AppRoutes.BATTERY_GUIDANCE) },
                )
            }
            composable(TopLevelDestination.MAP.route) {
                MapScreen(
                    modifier = Modifier.statusBarsPadding(),
                    onOpenTripSummary = { tripId -> navController.navigate(AppRoutes.tripSummary(tripId)) },
                )
            }
            composable(TopLevelDestination.TRIPS.route) {
                TripsRoute(onOpenTrip = { tripId -> navController.navigate(AppRoutes.tripSummary(tripId)) })
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsRoute(
                    onOpenPrivacy = { navController.navigate(AppRoutes.PRIVACY) },
                    onOpenBatteryGuidance = { navController.navigate(AppRoutes.BATTERY_GUIDANCE) },
                    onOpenAbout = { navController.navigate(AppRoutes.ABOUT) },
                )
            }
            composable(AppRoutes.LIVE_TRIP) {
                // Back from the live trip returns to the tabs; the trip keeps recording in the service.
                LiveTripRoute(onClose = { navController.popBackStack(AppRoutes.LIVE_TRIP, inclusive = true) })
            }
            composable(
                route = AppRoutes.TRIP_SUMMARY,
                arguments = listOf(navArgument(AppRoutes.TRIP_ID_ARG) { type = NavType.StringType }),
            ) {
                TripSummaryRoute(onBack = { navController.navigateUp() })
            }
            composable(AppRoutes.PRIVACY) {
                PrivacyRoute(onBack = { navController.navigateUp() })
            }
            composable(AppRoutes.BATTERY_GUIDANCE) {
                BatteryGuidanceScreen(onBack = { navController.navigateUp() })
            }
            composable(AppRoutes.ABOUT) {
                AboutScreen(onBack = { navController.navigateUp() })
            }
        }
    }
}

private fun TopLevelDestination.icon(): ImageVector =
    when (this) {
        TopLevelDestination.HOME -> Icons.Filled.Home
        TopLevelDestination.MAP -> Icons.Filled.Place
        TopLevelDestination.TRIPS -> Icons.AutoMirrored.Filled.List
        TopLevelDestination.SETTINGS -> Icons.Filled.Settings
    }

private fun NavDestination?.isOnTab(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

private fun NavHostController.navigateToTab(destination: TopLevelDestination) {
    navigate(destination.route) {
        // Keep one copy of each tab on the back stack and restore its state when it is reselected.
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.openLiveTrip() {
    navigate(AppRoutes.LIVE_TRIP) {
        launchSingleTop = true
    }
}
