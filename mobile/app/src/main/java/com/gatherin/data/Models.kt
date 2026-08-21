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

// ─── Response Wrappers ────────────────────────────────────────────────────────

data class AuthResponse(
    val user: User?,
    @SerializedName("access_token") val accessToken: String?,
    val error: ApiError?
)

data class EventsResponse(val events: List<EventItem>?)

data class RegistrationsResponse(val registrations: List<Registration>?)

data class QrTokenResponse(
    val token: String?,
    @SerializedName("expires_at") val expiresAt: String?
)

data class CheckinResponse(
    val checkin: CheckinDetail?,
    val error: ApiError?
)

data class CheckinDetail(
    @SerializedName("attendee_name") val attendeeName: String?
)

data class AiResponse(val answer: String?)

data class ApiError(val message: String?)

data class GenericResponse(val error: ApiError?)
