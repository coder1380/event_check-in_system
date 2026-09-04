package com.gatherin.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import androidx.core.content.edit

/**
 * Persistent session storage backed by SharedPreferences.
 * Keeps the access token, refresh token and logged-in user across app restarts,
 * mirroring the web client's localStorage behaviour.
 */
object SessionManager {

    private const val PREFS = "gatherin_session"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_USER = "user"
    private const val KEY_SERVER_URL = "custom_server_url"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    val accessToken: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_ACCESS, null) else null

    val refreshToken: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_REFRESH, null) else null

    val customServerUrl: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_SERVER_URL, null) else null

    fun savedUser(): User? =
        if (::prefs.isInitialized) prefs.getString(KEY_USER, null)?.let {
            runCatching { gson.fromJson(it, User::class.java) }.getOrNull()
        } else null

    /** Persist tokens + user after login / registration. */
    fun saveSession(user: User?, access: String?, refresh: String?) {
        if (!::prefs.isInitialized) return
        access?.let { prefs.edit { putString(KEY_ACCESS, it) } }
        refresh?.let { prefs.edit { putString(KEY_REFRESH, it) } }
        user?.let { prefs.edit { putString(KEY_USER, gson.toJson(it)) } }
    }

    /** Rotate tokens only (used by the automatic 401 refresher). */
    fun rotateTokens(access: String, refresh: String) {
        if (!::prefs.isInitialized) return
        prefs.edit {
            putString(KEY_ACCESS, access)
                .putString(KEY_REFRESH, refresh)
        }
    }

    fun saveServerUrl(url: String) {
        if (!::prefs.isInitialized) return
        prefs.edit { putString(KEY_SERVER_URL, RetrofitClient.normalizeBaseUrl(url)) }
    }

    fun clear() {
        if (::prefs.isInitialized) prefs.edit { clear() }
    }
}

/**
 * Singleton Retrofit client.
 * - All requests automatically carry the Authorization Bearer header.
 * - On a 401 the OkHttp Authenticator transparently rotates the refresh token
 *   via POST /auth/refresh and retries the original request once.
 */
object RetrofitClient {

//    private const val BASE_URL = "https://event-checkin-backend-production-87a2.up.railway.app/api/v1/"
//    const val DEFAULT_BASE_URL = "https://wool-refuse-anthem.ngrok-free.dev/api/v1/"
    const val DEFAULT_BASE_URL = "https://wool-refuse-anthem.ngrok-free.dev/api/v1/"

    fun normalizeBaseUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return DEFAULT_BASE_URL
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        if (!trimmed.contains("/api/v1")) {
            trimmed = if (trimmed.endsWith("/")) "${trimmed}api/v1/" else "$trimmed/api/v1/"
        }
        if (!trimmed.endsWith("/")) {
            trimmed = "$trimmed/"
        }
        return trimmed
    }

    val BASE_URL: String
        get() = normalizeBaseUrl(SessionManager.customServerUrl ?: DEFAULT_BASE_URL)

    /** Origin URL (no /api/v1 path) used for the Socket.IO connection. */
    val SOCKET_BASE_URL: String
        get() = BASE_URL.substringBefore("/api/v1")

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            addHeader("ngrok-skip-browser-warning", "true")
            addHeader("User-Agent", "Gatherin-Android-App")
            SessionManager.accessToken?.let { addHeader("Authorization", "Bearer $it") }
        }.build()
        chain.proceed(req)
    }

    private val refreshInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            addHeader("ngrok-skip-browser-warning", "true")
            addHeader("User-Agent", "Gatherin-Android-App")
        }.build()
        chain.proceed(req)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    /**
     * Transparently handles expired access tokens: rotates the refresh token
     * server-side and replays the failed request. Returns null when the
     * session cannot be recovered (caller should sign out).
     */
    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.code != 401) return null

            synchronized(this) {
                val staleAccess = response.request.header("Authorization")

                // Another thread already refreshed — just replay with the new token.
                val currentAccess = SessionManager.accessToken?.let { "Bearer $it" }
                if (currentAccess != null && currentAccess != staleAccess) {
                    return response.request.newBuilder()
                        .header("Authorization", currentAccess)
                        .build()
                }

                val refresh = SessionManager.refreshToken ?: return null

                val refreshed = try {
                    runBlocking {
                        val res = refreshApi.refreshToken(RefreshRequest(refresh))
                        val body = res.body()
                        if (res.isSuccessful && body?.accessToken != null && body.refreshToken != null) {
                            SessionManager.rotateTokens(body.accessToken, body.refreshToken)
                            true
                        } else false
                    }
                } catch (_: Exception) { false }

                if (!refreshed) {
                    SessionManager.clear()
                    return null
                }

                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${SessionManager.accessToken}")
                    .build()
            }
        }
    }

    private var currentBaseUrl: String? = null
    private var currentApi: ApiService? = null
    private var currentRefreshApi: ApiService? = null

    val api: ApiService
        get() {
            val url = BASE_URL
            if (currentApi == null || currentBaseUrl != url) {
                synchronized(this) {
                    val activeUrl = BASE_URL
                    val okHttpClient = OkHttpClient.Builder()
                        .authenticator(tokenAuthenticator)
                        .addInterceptor(authInterceptor)
                        .addInterceptor(loggingInterceptor)
                        .build()

                    val retrofit = Retrofit.Builder()
                        .baseUrl(activeUrl)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()

                    val refreshClient = OkHttpClient.Builder()
                        .addInterceptor(refreshInterceptor)
                        .addInterceptor(loggingInterceptor)
                        .build()

                    val refreshRetrofit = Retrofit.Builder()
                        .baseUrl(activeUrl)
                        .client(refreshClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()

                    currentBaseUrl = activeUrl
                    currentApi = retrofit.create(ApiService::class.java)
                    currentRefreshApi = refreshRetrofit.create(ApiService::class.java)
                }
            }
            return currentApi!!
        }

    val refreshApi: ApiService
        get() {
            api // Ensures currentRefreshApi is initialized
            return currentRefreshApi!!
        }
}
