package com.gatherin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatherin.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── Scan result sealed class ──────────────────────────────────────────────────
sealed class ScanResult {
    data class Success(val message: String) : ScanResult()
    data class Duplicate(val message: String) : ScanResult()
    data class Error(val message: String) : ScanResult()
}

class MainViewModel : ViewModel() {

    private val api get() = RetrofitClient.api

    // Live dashboard socket (web uses Socket.IO; mobile joins the same rooms).
    private var eventSocket: EventSocketManager? = null

    private val _socketConnected = MutableStateFlow(true)
    val socketConnected: StateFlow<Boolean> = _socketConnected.asStateFlow()

    // ── Auth state ────────────────────────────────────────────────────────────
    private val _user  = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    // ── Events ────────────────────────────────────────────────────────────────
    private val _events = MutableStateFlow<List<EventItem>>(emptyList())
    val events: StateFlow<List<EventItem>> = _events.asStateFlow()

    // ── Registrations ─────────────────────────────────────────────────────────
    private val _registrations = MutableStateFlow<List<Registration>>(emptyList())
    val registrations: StateFlow<List<Registration>> = _registrations.asStateFlow()

    // ── Dashboard ─────────────────────────────────────────────────────────────
    private val _dashboard = MutableStateFlow<DashboardData?>(null)
    val dashboard: StateFlow<DashboardData?> = _dashboard.asStateFlow()

    private val _selectedEventId = MutableStateFlow<String?>(null)
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    // ── QR Pass ───────────────────────────────────────────────────────────────
    private val _qrToken = MutableStateFlow<QrTokenResponse?>(null)
    val qrToken: StateFlow<QrTokenResponse?> = _qrToken.asStateFlow()

    // ── Scanner ───────────────────────────────────────────────────────────────
    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    private var lastToken: String? = null
    private var lastScanTime = 0L

    // ── AI Query ──────────────────────────────────────────────────────────────
    private val _aiAnswer = MutableStateFlow<String?>(null)
    val aiAnswer: StateFlow<String?> = _aiAnswer.asStateFlow()

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    // ── General loading ───────────────────────────────────────────────────────
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    init {
        // ── Restore persisted session (mirrors web localStorage behaviour) ────
        val savedUser = SessionManager.savedUser()
        val savedToken = SessionManager.accessToken
        if (savedUser != null && savedToken != null) {
            _user.value = savedUser
            _token.value = savedToken
            loadInitialData(savedUser)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auth
    // ─────────────────────────────────────────────────────────────────────────

    fun login(email: String, password: String) = viewModelScope.launch {
        _authLoading.value = true
        _authError.value = null
        try {
            val res = api.login(LoginRequest(email, password))
            val body = res.body()
            if (res.isSuccessful && body?.user != null && body.accessToken != null) {
                SessionManager.saveSession(body.user, body.accessToken, body.refreshToken)
                _token.value = body.accessToken
                _user.value  = body.user
                loadInitialData(body.user)
            } else {
                _authError.value = body?.error?.message ?: "Authentication failed"
            }
        } catch (e: Exception) {
            _authError.value = "Unable to connect to server. Is the backend running?"
        } finally {
            _authLoading.value = false
        }
    }

    fun register(email: String, password: String, fullName: String, role: String) =
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            try {
                val res = api.register(RegisterRequest(email, password, fullName, role))
                val body = res.body()
                if (res.isSuccessful && body?.user != null && body.accessToken != null) {
                    SessionManager.saveSession(body.user, body.accessToken, body.refreshToken)
                    _token.value = body.accessToken
                    _user.value  = body.user
                    loadInitialData(body.user)
                } else {
                    _authError.value = body?.error?.message ?: "Registration failed"
                }
            } catch (e: Exception) {
                _authError.value = "Unable to connect to server."
            } finally {
                _authLoading.value = false
            }
        }

    fun signOut() {
        // Revoke the refresh token server-side (best effort), then clear locally.
        val refresh = SessionManager.refreshToken
        viewModelScope.launch {
            try { if (refresh != null) api.logout(RefreshRequest(refresh)) } catch (_: Exception) { }
        }
        eventSocket?.disconnect()
        eventSocket = null
        SessionManager.clear()
        _user.value          = null
        _token.value         = null
        _events.value        = emptyList()
        _registrations.value = emptyList()
        _dashboard.value     = null
        _scanResult.value    = null
        _aiAnswer.value      = null
        _selectedEventId.value = null
    }

    private fun loadInitialData(user: User) {
        fetchEvents()
        if (user.role == "attendee") fetchRegistrations()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    fun fetchEvents() = viewModelScope.launch {
        _loading.value = true
        try {
            val res = api.getEvents()
            if (res.isSuccessful) _events.value = res.body()?.events ?: emptyList()
        } catch (_: Exception) { }
        finally { _loading.value = false }
    }

    fun registerForEvent(eventId: String) = viewModelScope.launch {
        try {
            val res = api.registerForEvent(eventId)
            if (res.isSuccessful) {
                _snackMessage.value = "🎉 You're on the guest list!"
                fetchEvents()
                fetchRegistrations()
            } else {
                _snackMessage.value = res.body()?.error?.message ?: "Registration failed"
            }
        } catch (_: Exception) {
            _snackMessage.value = "Network error. Please try again."
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Registrations
    // ─────────────────────────────────────────────────────────────────────────

    fun fetchRegistrations() = viewModelScope.launch {
        _loading.value = true
        try {
            val res = api.getRegistrations()
            if (res.isSuccessful) _registrations.value = res.body()?.registrations ?: emptyList()
        } catch (_: Exception) { }
        finally { _loading.value = false }
    }

    fun fetchQrToken(registrationId: String) = viewModelScope.launch {
        try {
            val res = api.getQrToken(registrationId)
            if (res.isSuccessful) _qrToken.value = res.body()
        } catch (_: Exception) {
            _snackMessage.value = "Failed to generate QR code."
        }
    }

    fun clearQrToken() { _qrToken.value = null }

    // ─────────────────────────────────────────────────────────────────────────
    // Dashboard
    // ─────────────────────────────────────────────────────────────────────────

    fun selectEventForDashboard(eventId: String) {
        _selectedEventId.value = eventId
        fetchDashboard(eventId)
        connectToEventSocket(eventId)
    }

    /**
     * Live updates via Socket.IO — same feed as the web dashboard:
     * `checkin:new` and `event:stats_update` broadcasts for this event room.
     */
    private fun connectToEventSocket(eventId: String) {
        eventSocket?.disconnect()
        eventSocket = EventSocketManager(
            eventId = eventId,
            onCheckin = { registrationId, checkedInAt, checkedInCount, spotsRemaining ->
                val current = _dashboard.value ?: return@EventSocketManager
                val newCheckedInCount = checkedInCount ?: (current.checkedInCount + 1)
                _dashboard.value = current.copy(
                    checkedInCount = newCheckedInCount,
                    spotsRemaining = spotsRemaining ?: (current.capacity - newCheckedInCount),
                    attendees = current.attendees.map {
                        if (it.registrationId == registrationId) it.copy(checkedInAt = checkedInAt) else it
                    }
                )
            },
            onStats = { registeredCount, spotsRemaining ->
                val current = _dashboard.value ?: return@EventSocketManager
                _dashboard.value = current.copy(
                    registeredCount = registeredCount ?: current.registeredCount,
                    spotsRemaining = spotsRemaining ?: current.spotsRemaining
                )
            },
            onConnectionChange = { connected -> _socketConnected.value = connected }
        ).also { it.connect() }
    }

    fun fetchDashboard(eventId: String) = viewModelScope.launch {
        try {
            val res = api.getDashboard(eventId)
            if (res.isSuccessful) _dashboard.value = res.body()
        } catch (_: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event create / edit (organizer)
    // ─────────────────────────────────────────────────────────────────────────

    fun createEvent(name: String, capacity: Int, isoDate: String, onDone: () -> Unit) =
        viewModelScope.launch {
            _loading.value = true
            try {
                val res = api.createEvent(EventRequest(name.trim(), isoDate, capacity))
                if (res.isSuccessful) {
                    _snackMessage.value = "🎉 Event created!"
                    fetchEvents()
                    onDone()
                } else {
                    _snackMessage.value = parseError(res) ?: "Could not create event."
                }
            } catch (_: Exception) {
                _snackMessage.value = "Network error. Please try again."
            } finally { _loading.value = false }
        }

    fun updateEvent(eventId: String, name: String, capacity: Int, isoDate: String, onDone: () -> Unit) =
        viewModelScope.launch {
            _loading.value = true
            try {
                val res = api.updateEvent(eventId, EventRequest(name.trim(), isoDate, capacity))
                if (res.isSuccessful) {
                    _snackMessage.value = "✏️ Event updated!"
                    fetchEvents()
                    if (_selectedEventId.value == eventId) fetchDashboard(eventId)
                    onDone()
                } else {
                    _snackMessage.value = parseError(res) ?: "Could not update event."
                }
            } catch (_: Exception) {
                _snackMessage.value = "Network error. Please try again."
            } finally { _loading.value = false }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Registration cancellation (attendee)
    // ─────────────────────────────────────────────────────────────────────────

    fun cancelRegistration(registrationId: String) = viewModelScope.launch {
        try {
            val res = api.cancelRegistration(registrationId)
            if (res.isSuccessful) {
                _snackMessage.value = "Registration cancelled."
                fetchRegistrations()
                fetchEvents()
            } else {
                _snackMessage.value = parseError(res) ?: "Could not cancel registration."
            }
        } catch (_: Exception) {
            _snackMessage.value = "Network error. Please try again."
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV attendee export (organizer)
    // ─────────────────────────────────────────────────────────────────────────

    private val _csvContent = MutableStateFlow<String?>(null)
    val csvContent: StateFlow<String?> = _csvContent.asStateFlow()

    fun exportAttendeesCsv(eventId: String) = viewModelScope.launch {
        try {
            val res = api.exportAttendeesCsv(eventId)
            if (res.isSuccessful) {
                _csvContent.value = res.body()?.string()
            } else {
                _snackMessage.value = "Export failed."
            }
        } catch (_: Exception) {
            _snackMessage.value = "Network error during export."
        }
    }

    fun clearCsv() { _csvContent.value = null }

    private fun parseError(res: retrofit2.Response<*>): String? = try {
        res.errorBody()?.string()?.let {
            com.google.gson.Gson().fromJson(it, ApiError::class.java)?.message
        }
    } catch (_: Exception) { null }

    // ─────────────────────────────────────────────────────────────────────────
    // AI Query
    // ─────────────────────────────────────────────────────────────────────────

    fun askAi(eventId: String, question: String) = viewModelScope.launch {
        _aiLoading.value = true
        try {
            val res = api.aiQuery(eventId, AiQueryRequest(question))
            _aiAnswer.value = res.body()?.answer ?: "No analytics returned."
        } catch (_: Exception) {
            _aiAnswer.value = "Unable to generate AI summary."
        } finally {
            _aiLoading.value = false
        }
    }

    fun clearAiAnswer() { _aiAnswer.value = null }

    // ─────────────────────────────────────────────────────────────────────────
    // Scanner
    // ─────────────────────────────────────────────────────────────────────────

    fun processCheckin(token: String, stationId: String = "station-app-1") =
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val trimmedToken = token.trim()

            if (trimmedToken == lastToken && now - lastScanTime < 2000) {
                return@launch
            }

            lastToken = trimmedToken
            lastScanTime = now

            try {
                val res = api.processCheckin(CheckinRequest(trimmedToken, stationId))
                val body = res.body()

                _scanResult.value = when {
                    res.isSuccessful -> {
                        ScanResult.Success(
                            "✅ Checked in: ${body?.checkin?.attendeeName ?: "Guest"}"
                        )
                    }
                    res.code() == 409 -> {
                        val errorJson = res.errorBody()?.string()
                        val apiError = try {
                            com.google.gson.Gson().fromJson(errorJson, ApiError::class.java)
                        } catch (_: Exception) {
                            null
                        }
                        ScanResult.Duplicate(
                            "⚠ ${apiError?.message ?: "Already checked in"}"
                        )
                    }
                    else -> {
                        val errorJson = res.errorBody()?.string()
                        val apiError = try {
                            com.google.gson.Gson().fromJson(errorJson, ApiError::class.java)
                        } catch (_: Exception) {
                            null
                        }
                        ScanResult.Error(
                            "❌ ${apiError?.message ?: "Scan failed"}"
                        )
                    }
                }
            } catch (e: Exception) {
                _scanResult.value = ScanResult.Error("❌ Network error during check-in")
            }
        }

    fun clearScanResult() { _scanResult.value = null }
    fun clearSnack()      { _snackMessage.value = null }
}
