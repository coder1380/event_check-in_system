package com.gatherin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.data.Registration
import com.gatherin.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TicketsScreen(vm: MainViewModel) {
    val registrations by vm.registrations.collectAsStateWithLifecycle()
    val qrToken       by vm.qrToken.collectAsStateWithLifecycle()
    val loading       by vm.loading.collectAsStateWithLifecycle()

    // Show QR bottom sheet when token is available
    if (qrToken != null) {
        QrPassBottomSheet(
            token     = qrToken!!.token ?: "",
            expiresAt = qrToken!!.expiresAt,
            onDismiss = { vm.clearQrToken() }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title   = "My Tickets",
                eyebrow = "YOUR PLACE IS SAVED",
                onSignOut = { vm.signOut() }
            )
        }
    ) { padding ->
        if (loading && registrations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        if (registrations.isEmpty()) {
            EmptyState(Modifier.padding(padding), "No registrations yet. Browse upcoming events!")
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start  = 16.dp, end = 16.dp,
                top    = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(registrations) { reg ->
                TicketCard(
                    registration = reg,
                    onShowQr     = { vm.fetchQrToken(reg.id) },
                    onCancel     = { vm.cancelRegistration(reg.id) }
                )
            }
        }
    }
}

@Composable
private fun TicketCard(registration: Registration, onShowQr: () -> Unit, onCancel: () -> Unit) {
    var showCancelDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val parsed     = remember(registration.eventDate) {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                .parse(registration.eventDate)
        }.getOrNull()
    }
    val dateStr = parsed?.let { dateFormat.format(it) } ?: "—"
    val checkedIn = registration.checkedInAt != null

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(registration.eventName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(dateStr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            if (checkedIn) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text     = "✅ Checked in",
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                OutlinedButton(
                    onClick  = onShowQr,
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📱 Show QR Pass", color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick  = { showCancelDialog = true },
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel registration", color = OrangeWarning)
                }
            }
        }
    }

    // ── Cancel confirmation dialog ────────────────────────────────────────────
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title   = { Text("Cancel registration?") },
            text    = { Text("Your spot at \"${registration.eventName}\" will be freed up for others. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onCancel()
                }) { Text("Yes, cancel it", color = OrangeWarning) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Keep my ticket") }
            }
        )
    }
}
