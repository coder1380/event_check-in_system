package com.gatherin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.ui.theme.*

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val dashboard   by vm.dashboard.collectAsStateWithLifecycle()
    val selectedId  by vm.selectedEventId.collectAsStateWithLifecycle()
    val loading     by vm.loading.collectAsStateWithLifecycle()
    val aiAnswer    by vm.aiAnswer.collectAsStateWithLifecycle()
    val aiLoading   by vm.aiLoading.collectAsStateWithLifecycle()

    var aiQuestion by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            AppTopBar(
                title   = "Live Room Overview",
                eyebrow = "COMMAND CENTER",
                onSignOut = { vm.signOut() }
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
