package com.gatherin.data

import retrofit2.Response
import okhttp3.ResponseBody
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    // ── Events ────────────────────────────────────────────────────────────────
    @GET("events")
    suspend fun getEvents(): Response<EventsResponse>

    @POST("events")
    suspend fun createEvent(@Body body: EventRequest): Response<EventResponse>

    @PATCH("events/{id}")
    suspend fun updateEvent(@Path("id") eventId: String, @Body body: EventRequest): Response<EventResponse>

    @POST("events/{id}/register")
    suspend fun registerForEvent(@Path("id") eventId: String): Response<GenericResponse>

    @GET("events/{id}/dashboard")
    suspend fun getDashboard(@Path("id") eventId: String): Response<DashboardData>

    @Streaming
    @GET("events/{id}/export")
    suspend fun exportAttendeesCsv(@Path("id") eventId: String): Response<ResponseBody>

    @POST("events/{id}/ai-query")
    suspend fun aiQuery(
        @Path("id") eventId: String,
        @Body body: AiQueryRequest
    ): Response<AiResponse>

    // ── Registrations ─────────────────────────────────────────────────────────
    @GET("registrations")
    suspend fun getRegistrations(): Response<RegistrationsResponse>

    @POST("registrations/{id}/cancel")
    suspend fun cancelRegistration(@Path("id") registrationId: String): Response<GenericResponse>

    @GET("registrations/{id}/qr-token")
    suspend fun getQrToken(@Path("id") registrationId: String): Response<QrTokenResponse>

    // ── Check-in ──────────────────────────────────────────────────────────────
    @POST("checkins")
    suspend fun processCheckin(@Body body: CheckinRequest): Response<CheckinResponse>

    // ── Session (refresh rotation / logout) ───────────────────────────────────
    @POST("auth/refresh")
    suspend fun refreshToken(@Body body: RefreshRequest): Response<RefreshResponse>

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<GenericResponse>
}
