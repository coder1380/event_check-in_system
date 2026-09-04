package com.gatherin

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatherin.data.*
import com.gatherin.data.local.*
import com.gatherin.utils.AudioService
import com.gatherin.utils.NetworkObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ── Scan result sealed class ──────────────────────────────────────────────────
sealed class ScanResult {
    data class Success(val message: String) : ScanResult()
    data class Duplicate(val message: String) : ScanResult()
    data class Error(val message: String) : ScanResult()
    data class Queued(val message: String) : ScanResult()
}

class MainViewModel : ViewModel() {

    private val app = GatherinApplication.instance
    private val api get() = RetrofitClient.api
    private val db = ScannerDatabase.getDatabase(app)
    private val dao = db.scanDao()
    private val networkObserver = NetworkObserver(app)
    private val audioService = AudioService()

    val isOnline = networkObserver.isConnected.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    private val _isSoundEnabled = MutableStateFlow(
        app.getSharedPreferences("gatherin_settings", Context.MODE_PRIVATE)
            .getBoolean("sound_enabled", true)
    )
    val isSoundEnabled = _isSoundEnabled.asStateFlow()

    fun toggleSound() {
        val newVal = !_isSoundEnabled.value
        _isSoundEnabled.value = newVal
        app.getSharedPreferences("gatherin_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("sound_enabled", newVal).apply()
    }

    val pendingScansCount = MutableStateFlow(0)
    val syncedScans = dao.getSyncedScans().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

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

    /** Populated when the server says a valid pass is already active (409 TOKEN_ACTIVE). */
    private val _qrActiveError = MutableStateFlow<QrActiveError?>(null)
    val qrActiveError: StateFlow<QrActiveError?> = _qrActiveError.asStateFlow()

    /** Currently open registration ID (for auto-refresh). */
    private var _qrRegistrationId: String? = null
    /** Auto-refresh coroutine job — cancelled on dismiss. */
    private var _qrRefreshJob: Job? = null
    /** Session ID of the last closed QR panel. */
    private var _prevSessionId: String? = null
    /** Prevents double-fetching if the user taps the button multiple times rapidly. */
    private var _qrTokenFetching = false
    /** Max auto-refreshes per session. */
    private val MAX_REFRESHES = 3

    // ── Scanner ───────────────────────────────────────────────────────────────
    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    private var lastToken: String? = null
    private var lastScanTime = 0L

    // CameraX reports the same QR on every analysed frame while it stays in view;
    // this is the window during which we treat those repeats as one continuous scan.
    private val scanCooldownMs = 2000L

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

        // ── Update pending count ──────────────────────────────────────────────
        viewModelScope.launch {
            db.scanDao().getPendingScans().collect { scans ->
                pendingScansCount.value = scans.size
            }
        }

        // ── Auto-sync when online ─────────────────────────────────────────────
        viewModelScope.launch {
            isOnline.collect { online ->
                if (online) syncPendingScans()
            }
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
            _authError.value = "Connection error: ${e.localizedMessage ?: e.message ?: "Is the backend running?"}"
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
                _authError.value = "Connection error: ${e.localizedMessage ?: e.message ?: "Unable to connect to server."}"
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
        _qrToken.value       = null
        _qrActiveError.value = null
        _qrRefreshJob?.cancel()
        _qrRefreshJob        = null
        _prevSessionId       = null
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
        if (_qrTokenFetching) return@launch
        _qrTokenFetching = true

        // Pass any previously-closed session ID so the server atomically
        // invalidates it and issues a new token in one transaction.
        // This eliminates the race between a separate DELETE and the GET.
        val invalidate = _prevSessionId
        _prevSessionId = null

        _qrRegistrationId = registrationId
        try {
            val res = api.getQrToken(registrationId, invalidateSessionId = invalidate)
            when {
                res.isSuccessful -> {
                    _qrToken.value = res.body()
                    _qrActiveError.value = null
                    scheduleAutoRefresh(registrationId, res.body())
                }
                res.code() == 409 -> handle409(res)
                else -> _snackMessage.value = parseError(res) ?: "Failed to generate QR code."
            }
        } catch (_: Exception) {
            _snackMessage.value = "Network error. Could not fetch QR pass."
        } finally {
            _qrTokenFetching = false
        }
    }

    /**
     * Schedules automatic QR refresh ~5 s before the current token expires.
     * If [MAX_REFRESHES] have been used up the coroutine just lets the timer
     * expire and closes the panel — the user must open it again manually.
     */
    private fun scheduleAutoRefresh(registrationId: String, current: QrTokenResponse?) {
        _qrRefreshJob?.cancel()
        if (current == null) return

        val refreshesLeft = current.refreshesRemaining
        if (refreshesLeft <= 0) {
            // No more auto-refreshes — let the countdown run out and close the panel
            _qrRefreshJob = viewModelScope.launch {
                delay(65_000L) // slightly past the 60 s TTL
                dismissQrPanel()
            }
            return
        }

        // Fire ~5 s before the 60 s window closes
        _qrRefreshJob = viewModelScope.launch {
            delay(55_000L)
            // Still the same registration open?
            if (_qrRegistrationId != registrationId) return@launch
            val currentToken = _qrToken.value ?: return@launch
            val sessionId = currentToken.sessionId ?: return@launch
            val nextCount = currentToken.refreshCount + 1

            try {
                val res = api.refreshQrToken(registrationId, sessionId, nextCount)
                when {
                    res.isSuccessful -> {
                        _qrToken.value = res.body()
                        scheduleAutoRefresh(registrationId, res.body()) // recurse for next cycle
                    }
                    res.code() == 409 -> {
                        // SESSION_REFRESH_LIMIT or SESSION_MISMATCH — close panel gracefully
                        dismissQrPanel()
                    }
                    else -> dismissQrPanel()
                }
            } catch (_: Exception) {
                // Network error during auto-refresh — close rather than leave stale QR
                dismissQrPanel()
            }
        }
    }

    /**
     * Called when the user closes the QR panel OR when auto-refresh gives up.
     *
     * Invalidates the active token immediately (matching web behaviour) and also
     * saves the current session_id to [_prevSessionId] as a fallback for the NEXT
     * call to [fetchQrToken].
     */
    fun dismissQrPanel() {
        _qrRefreshJob?.cancel()
        _qrRefreshJob = null

        val sessionId = _qrToken.value?.sessionId
        val registrationId = _qrRegistrationId

        // Capture session for fallback atomic invalidation on next open
        _prevSessionId = sessionId

        // Invalidate immediately server-side
        if (sessionId != null && registrationId != null) {
            viewModelScope.launch {
                try {
                    api.invalidateQrToken(registrationId, QrInvalidateRequest(sessionId))
                } catch (_: Exception) { /* best effort */ }
            }
        }

        _qrToken.value       = null
        _qrActiveError.value = null
        _qrRegistrationId    = null
    }

    private fun handle409(res: retrofit2.Response<*>) {
        val body = try {
            res.errorBody()?.string()?.let { json ->
                com.google.gson.Gson().fromJson(json, QrActiveErrorBody::class.java)
            }
        } catch (_: Exception) { null }
        val detail = body?.error
        val expiresIn = detail?.expiresIn ?: 60
        val expiresAt = detail?.expiresAt ?: ""
        _qrActiveError.value = QrActiveError(expiresIn, expiresAt)
    }

    fun clearQrToken()       { _qrToken.value = null }
    fun clearQrActiveError() { _qrActiveError.value = null }

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
        res.errorBody()?.string()?.let { json ->
            val container = com.google.gson.Gson().fromJson(json, com.gatherin.data.ApiErrorContainer::class.java)
            container?.error?.message ?: com.google.gson.Gson().fromJson(json, com.gatherin.data.ApiError::class.java)?.message
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

            // 1. Debounce
            val sameToken      = trimmedToken == lastToken
            val withinCooldown = now - lastScanTime < scanCooldownMs
            val previousFailed = _scanResult.value is ScanResult.Error
            if (sameToken && withinCooldown && !previousFailed) return@launch

            lastToken = trimmedToken
            lastScanTime = now

            // 2. Check cache (prevents duplicate API hits if already known)
            val cached = dao.getSynced(trimmedToken)
            if (cached != null) {
                _scanResult.value = when (cached.status) {
                    "success" -> ScanResult.Success("✅ Checked in: ${cached.attendeeName ?: "Guest"}")
                    "duplicate" -> ScanResult.Duplicate("⛔ Already checked in")
                    "expired" -> ScanResult.Error("⚠️ Expired QR pass")
                    else -> ScanResult.Error("❌ Invalid QR code")
                }
                return@launch
            }

            // 3. Online -> API, Offline -> Queue
            if (isOnline.value) {
                try {
                    val clientScannedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date(now))

                    val res = api.processCheckin(CheckinRequest(trimmedToken, stationId, clientScannedAt))
                    val body = res.body()
                    val parsedMsg = parseError(res)

                    val status: String
                    val attendeeName: String? = body?.checkin?.attendeeName

                    _scanResult.value = when {
                        res.isSuccessful -> {
                            status = "success"
                            if (_isSoundEnabled.value) audioService.playSuccess()
                            ScanResult.Success("✅ Checked in: ${attendeeName ?: "Guest"}")
                        }
                        res.code() == 409 -> {
                            status = "duplicate"
                            if (_isSoundEnabled.value) audioService.playDuplicate()
                            ScanResult.Duplicate("⛔ ${parsedMsg ?: "Already checked in"}")
                        }
                        res.code() == 410 -> {
                            status = "expired"
                            if (_isSoundEnabled.value) audioService.playError()
                            ScanResult.Error("⚠️ ${parsedMsg ?: "Expired QR pass"}")
                        }
                        else -> {
                            status = "invalid"
                            if (_isSoundEnabled.value) audioService.playError()
                            ScanResult.Error("❌ ${parsedMsg ?: "Scan failed"}")
                        }
                    }
                    // Cache the result
                    dao.insertSynced(SyncedScanEntity(trimmedToken, attendeeName, status))

                } catch (e: Exception) {
                    // Fallback to queuing if network error happens mid-request
                    queueScan(trimmedToken, stationId, now)
                }
            } else {
                queueScan(trimmedToken, stationId, now)
            }
        }

    private suspend fun queueScan(token: String, stationId: String, timestamp: Long) {
        val isoTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

        dao.insertPending(PendingScanEntity(token, stationId, isoTime))
        if (_isSoundEnabled.value) audioService.playQueued()
        _scanResult.value = ScanResult.Queued("📥 Offline — scan queued")
    }

    private fun syncPendingScans() = viewModelScope.launch {
        val pending = dao.getPendingScansList()
        if (pending.isEmpty()) return@launch

        // Web matches 1 scan per batch request
        for (scan in pending) {
            try {
                val res = api.syncBatch(SyncBatchRequest(
                    stationId = scan.stationId,
                    scans = listOf(CheckinRequest(scan.token, scan.stationId, scan.clientScannedAt))
                ))
                if (res.isSuccessful) {
                    val result = res.body()?.results?.firstOrNull()
                    if (result != null) {
                        dao.insertSynced(SyncedScanEntity(
                            token = scan.token,
                            attendeeName = result.checkin?.attendeeName,
                            status = when (result.status) {
                                "accepted" -> "success"
                                "rejected_duplicate" -> "duplicate"
                                "rejected_expired" -> "expired"
                                else -> "invalid"
                            }
                        ))
                    }
                    dao.deletePending(scan)
                }
            } catch (_: Exception) {
                break // Stop batch if connection drops again
            }
        }
    }

    fun clearHistory() = viewModelScope.launch {
        dao.clearHistory()
    }

    fun clearScanResult() { _scanResult.value = null }
    fun clearSnack()      { _snackMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        audioService.release()
    }
}
