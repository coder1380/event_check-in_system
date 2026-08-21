package com.gatherin.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gatherin.MainViewModel
import com.gatherin.ui.screens.*

private sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Events    : Screen("events",    "Events",    Icons.Default.DateRange)
    object Tickets   : Screen("tickets",   "My Tickets", Icons.Default.ConfirmationNumber)
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Scanner   : Screen("scanner",   "Scanner",   Icons.Default.QrCodeScanner)
}

@Composable
fun GatherinApp(vm: MainViewModel = viewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    val snack by vm.snackMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar messages from ViewModel
    LaunchedEffect(snack) {
        snack?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearSnack()
        }
    }

    if (user == null) {
        AuthScreen(vm = vm)
        return
    }

    val isOrganizer = user!!.role == "organizer"
    val navItems = if (isOrganizer) {
        listOf(Screen.Events, Screen.Dashboard, Screen.Scanner)
    } else {
        listOf(Screen.Events, Screen.Tickets)
    }
    val startRoute = if (isOrganizer) Screen.Dashboard.route else Screen.Events.route

    val navController = rememberNavController()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                navItems.forEach { screen ->
                    NavigationBarItem(
                        icon  = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = startRoute,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Events.route)    { EventsScreen(
                vm = vm,
                onCreateEvent = { navController.navigate("event_form/new") },
                onEditEvent   = { navController.navigate("event_form/$it") }
            ) }
            composable(Screen.Tickets.route)   { TicketsScreen(vm = vm) }
            composable(Screen.Dashboard.route) { DashboardScreen(vm = vm) }
            composable(Screen.Scanner.route)   { ScannerScreen(vm = vm) }
            composable("event_form/{eventId}") { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId")
                val editEvent = if (eventId == null || eventId == "new") null
                                else vm.events.value.find { it.id == eventId }
                EventFormScreen(
                    vm = vm,
                    editEvent = editEvent,
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}
