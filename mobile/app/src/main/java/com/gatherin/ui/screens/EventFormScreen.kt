package com.gatherin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.data.EventItem
import com.gatherin.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Create / edit an event — parity with the web's `/dashboard/events/new`
 * and `/dashboard/events/:id/edit` pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormScreen(
    vm: MainViewModel,
    editEvent: EventItem?,
    onDone: () -> Unit
) {
    val loading by vm.loading.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(editEvent?.name ?: "") }
    var capacity by remember { mutableStateOf(editEvent?.capacity?.toString() ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Date & time state (defaults to tomorrow for new events)
    val calendar = remember(editEvent) {
        Calendar.getInstance().apply {
            editEvent?.eventDate?.let { raw ->
                val parsed = parseIso(raw)
                if (parsed != null) time = parsed
            } ?: add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text  = if (editEvent == null) "NEW EVENT" else "EDIT EVENT",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandBlue
                        )
                        Text(
                            if (editEvent == null) "Create event" else "Edit event",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor         = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it; errorMessage = null },
                label         = { Text("Event name") },
                singleLine    = true,
                shape         = RoundedCornerShape(10.dp),
                modifier      = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value           = capacity,
                onValueChange   = { capacity = it.filter { c -> c.isDigit() }; errorMessage = null },
                label           = { Text("Capacity") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape           = RoundedCornerShape(10.dp),
                modifier        = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick  = { showDatePicker = true },
                shape    = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("\uD83D\uDDD3 ${dateFmt.format(calendar.time)}") }

            OutlinedButton(
                onClick  = { showTimePicker = true },
                shape    = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("\u23F0 ${timeFmt.format(calendar.time)}") }

            errorMessage?.let {
                Text(it, color = OrangeWarning, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val cap = capacity.toIntOrNull()
                    when {
                        name.isBlank()          -> errorMessage = "Please enter an event name."
                        cap == null || cap < 1  -> errorMessage = "Capacity must be a positive number."
                        else -> {
                            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }.format(calendar.time)
                            if (editEvent == null) vm.createEvent(name, cap, iso) { onDone() }
                            else vm.updateEvent(editEvent.id, name, cap, iso) { onDone() }
                        }
                    }
                },
                enabled  = !loading,
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (editEvent == null) "Create Event \u2192" else "Save Changes \u2192", color = Color.White)
                }
            }
        }
    }

    // ── Date picker dialog ────────────────────────────────────────────────────
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = calendar.timeInMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val picked = Calendar.getInstance().apply { timeInMillis = it }
                        calendar.set(Calendar.YEAR, picked.get(Calendar.YEAR))
                        calendar.set(Calendar.MONTH, picked.get(Calendar.MONTH))
                        calendar.set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }

    // ── Time picker dialog ────────────────────────────────────────────────────
    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour   = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour      = false
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    calendar.set(Calendar.HOUR_OF_DAY, state.hour)
                    calendar.set(Calendar.MINUTE, state.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }
}

private fun parseIso(raw: String): Date? {
    val fmts = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )
    for (f in fmts) {
        val d = runCatching {
            SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
        }.getOrNull()
        if (d != null) return d
    }
    return null
}
