package com.gatherin.data

import com.google.gson.annotations.SerializedName

// ─── Domain Models ────────────────────────────────────────────────────────────

data class User(
    val id: String,
    val email: String,
    @SerializedName("full_name") val fullName: String,
    val role: String  // "organizer" | "attendee"
)

data class EventItem(
    val id: String,
    val name: String,
    @SerializedName("event_date") val eventDate: String,
    val capacity: Int,
    @SerializedName("registered_count") val registeredCount: Int,
    @SerializedName("spots_remaining") val spotsRemaining: Int
)

data class Registration(
    val id: String,
    @SerializedName("event_id") val eventId: String,
    @SerializedName("event_name") val eventName: String,
    @SerializedName("event_date") val eventDate: String,
    val status: String,
    @SerializedName("checked_in_at") val checkedInAt: String?
)

data class DashboardAttendee(
    @SerializedName("registration_id") val registrationId: String,
    val name: String,
    @SerializedName("checked_in_at") val checkedInAt: String?
)

data class DashboardData(
    @SerializedName("event_id") val eventId: String,
    val capacity: Int,
    @SerializedName("registered_count") val registeredCount: Int,
    @SerializedName("checked_in_count") val checkedInCount: Int,
    @SerializedName("spots_remaining") val spotsRemaining: Int,
    val attendees: List<DashboardAttendee> = emptyList()
)

// ─── Request Bodies ───────────────────────────────────────────────────────────

data class LoginRequest(val email: String, val password: String)

data class RegisterRequest(
    val email: String,
    val password: String,
    @SerializedName("full_name") val fullName: String,
    val role: String
)

data class CheckinRequest(
    val token: String,
    @SerializedName("station_id") val stationId: String
)

data class AiQueryRequest(val question: String)

// ── Event create / update (organizer) ────────────────────────────────────────
data class EventRequest(
    val name: String,
    @SerializedName("event_date") val eventDate: String, // ISO-8601 e.g. 2026-09-01T18:00:00.000Z
    val capacity: Int
)

data class EventResponse(val event: EventItem?)

// ── Refresh token rotation / logout ──────────────────────────────────────────
data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)

data class RefreshResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?
)

// ─── Response Wrappers ────────────────────────────────────────────────────────

data class AuthResponse(
    val user: User?,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    val error: ApiError?
)

data class EventsResponse(val events: List<EventItem>?)

data class RegistrationsResponse(val registrations: List<Registration>?)

data class QrTokenResponse(
    val token: String?,
    @SerializedName("expires_at")         val expiresAt: String?,
    @SerializedName("session_id")          val sessionId: String?,
    @SerializedName("refresh_count")       val refreshCount: Int = 0,
    @SerializedName("refreshes_remaining") val refreshesRemaining: Int = 0
)

data class QrInvalidateRequest(
    @SerializedName("session_id") val sessionId: String
)

/** Returned by the server (409 TOKEN_ACTIVE) when a valid QR is still live. */
data class QrActiveError(
    val expiresIn: Int,          // seconds remaining
    val expiresAt: String        // ISO-8601
)

data class QrActiveErrorBody(
    val error: QrActiveErrorDetail?
)

data class QrActiveErrorDetail(
    val code: String?,
    val message: String?,
    @SerializedName("expires_in")  val expiresIn: Int?,
    @SerializedName("expires_at")  val expiresAt: String?
)

data class CheckinResponse(
    val checkin: CheckinDetail?,
    val error: ApiError?
)

data class CheckinDetail(
    @SerializedName("attendee_name") val attendeeName: String?
)

data class AiResponse(val answer: String?)

data class ApiError(
    val code: String? = null,
    val message: String? = null
)

data class ApiErrorContainer(
    val error: ApiError? = null
)

data class GenericResponse(val error: ApiError?)
