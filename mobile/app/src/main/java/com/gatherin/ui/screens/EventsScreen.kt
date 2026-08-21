package com.gatherin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.data.EventItem
import com.gatherin.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EventsScreen(vm: MainViewModel) {
    val events  by vm.events.collectAsStateWithLifecycle()
    val user    by vm.user.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val isOrganizer = user?.role == "organizer"

    Scaffold(
        topBar = {
            AppTopBar(
                title    = "Upcoming Events",
                eyebrow  = "FIND YOUR NEXT ROOM",
                onSignOut = { vm.signOut() },
                actions = {
                    IconButton(onClick = { vm.fetchEvents() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = BrandBlue)
                    }
                }
            )
        }
    ) { padding ->
        if (loading && events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        if (events.isEmpty()) {
            EmptyState(Modifier.padding(padding), "No events available right now.")
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(events) { event ->
                EventCard(
                    event       = event,
                    isOrganizer = isOrganizer,
                    onRegister  = { vm.registerForEvent(event.id) },
                    onDashboard = { vm.selectEventForDashboard(event.id) }
                )
            }
        }
    }
}

@Composable
private fun EventCard(
    event: EventItem,
    isOrganizer: Boolean,
    onRegister: () -> Unit,
    onDashboard: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val parsed     = remember(event.eventDate) {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(event.eventDate) }.getOrNull()
            ?: runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).parse(event.eventDate) }.getOrNull()
    }
    val dateStr  = parsed?.let { dateFormat.format(it) } ?: "—"
    val timeStr  = parsed?.let { timeFormat.format(it) } ?: ""
    val soldOut  = event.spotsRemaining < 1

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text     = dateStr,
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text  = if (soldOut) "Sold out" else "${event.spotsRemaining} spots left",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (soldOut) OrangeWarning else GreenSuccess
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(event.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(timeStr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

            if (isOrganizer) {
                OutlinedButton(
                    onClick = onDashboard,
                    shape   = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Live Dashboard →", color = BrandBlue)
                }
            } else {
                Button(
                    onClick  = onRegister,
                    enabled  = !soldOut,
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = BrandBlue,
                        disabledContainerColor = SlateGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (soldOut) "Event Full" else "Register Now →", color = Color.White)
                }
            }
        }
    }
}
