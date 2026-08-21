package com.gatherin.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val dashboard   by vm.dashboard.collectAsStateWithLifecycle()
    val selectedId  by vm.selectedEventId.collectAsStateWithLifecycle()
    val loading     by vm.loading.collectAsStateWithLifecycle()
    val aiAnswer    by vm.aiAnswer.collectAsStateWithLifecycle()
    val aiLoading   by vm.aiLoading.collectAsStateWithLifecycle()
    val csvContent  by vm.csvContent.collectAsStateWithLifecycle()
    val socketOk    by vm.socketConnected.collectAsStateWithLifecycle()

    var aiQuestion by remember { mutableStateOf("") }
    var search     by remember { mutableStateOf("") }

    val context = LocalContext.current

    // ── Share the exported CSV via the Android share sheet ────────────────────
    LaunchedEffect(csvContent) {
        csvContent?.let { csv ->
            runCatching {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "attendees-${selectedId ?: "event"}.csv")
                file.writeText(csv)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Export attendee CSV"))
            }
            vm.clearCsv()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title   = "Live Room Overview",
                eyebrow = "COMMAND CENTER",
                onSignOut = { vm.signOut() },
                actions = {
                    if (dashboard != null) {
                        IconButton(onClick = { selectedId?.let { vm.exportAttendeesCsv(it) } }) {
                            Icon(Icons.Default.Download, contentDescription = "Export CSV",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { selectedId?.let { vm.selectEventForDashboard(it) } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (loading && dashboard == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        if (dashboard == null) {
            EmptyState(
                Modifier.padding(padding),
                "Select an event from the Events tab to view live room stats."
            )
            return@Scaffold
        }

        val d = dashboard!!

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Live connection banner (web parity) ────────────────────────
            if (!socketOk) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = OrangeWarning.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠ Live updates paused — reconnecting to server…",
                        style = MaterialTheme.typography.labelMedium,
                        color = OrangeWarning,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // ── Stats Grid ─────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("CAPACITY",   d.capacity.toString(),    Color.White, Modifier.weight(1f))
                StatCard("REGISTERED", d.registeredCount.toString(), BrandBlue, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("CHECKED IN", d.checkedInCount.toString(), GreenSuccess, Modifier.weight(1f))
                StatCard("SPOTS LEFT", d.spotsRemaining.toString(), OrangeWarning, Modifier.weight(1f))
            }

            Spacer(Modifier.height(4.dp))

            // ── Guest list with search (web parity: "Search guests by name…") ──
            Card(
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Guest list",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value         = search,
                        onValueChange = { search = it },
                        placeholder   = { Text("Search guests by name…", color = SlateGray) },
                        leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon  = {
                            if (search.isNotEmpty()) {
                                IconButton(onClick = { search = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        singleLine    = true,
                        shape         = RoundedCornerShape(10.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor   = BrandBlue,
                            unfocusedBorderColor = BorderGray,
                            cursorColor          = BrandBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    val attendees = d.attendees.filter {
                        it.name.contains(search.trim(), ignoreCase = true)
                    }

                    if (attendees.isEmpty()) {
                        Text(
                            if (search.isBlank()) "No registrations yet."
                            else "No guests match \"$search\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        attendees.forEach { attendee ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Box(Modifier.padding(6.dp)) {
                                            Text(
                                                attendee.name.take(1).uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        attendee.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (attendee.checkedInAt != null) {
                                    Text(
                                        "✅ Checked in",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GreenSuccess
                                    )
                                } else {
                                    Text(
                                        "Not checked in",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateGray
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── AI Query Card ──────────────────────────────────────────────
            Card(
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🤖 Ask AI Room Intelligence",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value         = aiQuestion,
                        onValueChange = { aiQuestion = it },
                        placeholder   = { Text("e.g. When did check-ins peak?", color = SlateGray) },
                        shape         = RoundedCornerShape(10.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor   = BrandBlue,
                            unfocusedBorderColor = BorderGray,
                            cursorColor          = BrandBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            selectedId?.let { vm.askAi(it, aiQuestion) }
                        },
                        enabled  = !aiLoading && aiQuestion.isNotBlank() && selectedId != null,
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (aiLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Ask AI →", color = Color.White)
                        }
                    }

                    aiAnswer?.let { answer ->
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text     = answer,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, valueColor: Color, modifier: Modifier) {
    Card(
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier  = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = value,
                style = MaterialTheme.typography.headlineLarge,
                color = if (valueColor == Color.White) MaterialTheme.colorScheme.onSurface else valueColor
            )
        }
    }
}
