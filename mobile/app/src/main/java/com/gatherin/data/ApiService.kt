package com.gatherin.data

import retrofit2.Response
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

    @POST("events/{id}/register")
    suspend fun registerForEvent(@Path("id") eventId: String): Response<GenericResponse>

    @GET("events/{id}/dashboard")
    suspend fun getDashboard(@Path("id") eventId: String): Response<DashboardData>

    @POST("events/{id}/ai-query")
    suspend fun aiQuery(
        @Path("id") eventId: String,
        @Body body: AiQueryRequest
    ): Response<AiResponse>

    // ── Registrations ─────────────────────────────────────────────────────────
    @GET("registrations")
    suspend fun getRegistrations(): Response<RegistrationsResponse>

    @GET("registrations/{id}/qr-token")
    suspend fun getQrToken(@Path("id") registrationId: String): Response<QrTokenResponse>

    // ── Check-in ──────────────────────────────────────────────────────────────
    @POST("checkins")
    suspend fun processCheckin(@Body body: CheckinRequest): Response<CheckinResponse>
}
