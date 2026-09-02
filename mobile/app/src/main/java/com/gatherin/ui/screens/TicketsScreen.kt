package com.gatherin.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.data.Registration
import com.gatherin.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TicketsScreen(vm: MainViewModel) {
    val registrations  by vm.registrations.collectAsStateWithLifecycle()
    val qrToken        by vm.qrToken.collectAsStateWithLifecycle()
    val qrActiveError  by vm.qrActiveError.collectAsStateWithLifecycle()
    val loading        by vm.loading.collectAsStateWithLifecycle()

    // ── Active QR Pass bottom-sheet ──────────────────────────────────────────
    if (qrToken != null) {
        QrPassBottomSheet(
            token     = qrToken!!.token ?: "",
            expiresAt = qrToken!!.expiresAt,
            refreshCount = qrToken!!.refreshCount,
            refreshesRemaining = qrToken!!.refreshesRemaining,
            onDismiss = { vm.dismissQrPanel() }
        )
    }

    // ── "QR still active" dialog ─────────────────────────────────────────────
    if (qrActiveError != null) {
        val error = qrActiveError!!
        ActiveQrDialog(
            expiresIn = error.expiresIn,
            onDismiss = { vm.clearQrActiveError() }
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

// ── "You have an active QR" dialog ──────────────────────────────────────────

@Composable
private fun ActiveQrDialog(expiresIn: Int, onDismiss: () -> Unit) {
    // Live countdown so the user can see it tick down
    var secondsLeft by remember { mutableIntStateOf(expiresIn) }
    LaunchedEffect(expiresIn) {
        var remaining = expiresIn
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000L)
            remaining--
            secondsLeft = remaining
        }
        onDismiss() // auto-close when expired
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "QR Pass Already Active",
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text  = "Your current QR pass is still valid.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // Animated countdown chip
                val progress = secondsLeft / expiresIn.toFloat()
                val chipColor = when {
                    secondsLeft <= 10 -> OrangeWarning
                    secondsLeft <= 20 -> Color(0xFFF59E0B)
                    else              -> BrandBlue
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = chipColor.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("⏱", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Expires in ${secondsLeft}s",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = chipColor
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color    = chipColor,
                    trackColor = chipColor.copy(alpha = 0.15f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text  = "You can generate a new pass once this one expires.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = BrandBlue, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ── Ticket card ──────────────────────────────────────────────────────────────

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
    val dateStr   = parsed?.let { dateFormat.format(it) } ?: "—"
    val checkedIn = registration.checkedInAt != null

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(registration.eventName, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(dateStr, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            if (checkedIn) {
                // Show checked-in badge AND still allow QR generation (for proof-of-attendance)
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
                Spacer(Modifier.height(8.dp))
                // Still allow showing QR even after check-in (proof of attendance)
                OutlinedButton(
                    onClick  = onShowQr,
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📱 Show QR Pass", color = MaterialTheme.colorScheme.onSurface)
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

    // ── Cancel confirmation dialog ──────────────────────────────────────────
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
