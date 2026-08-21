package com.gatherin.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Singleton Retrofit client.
 * Call [setToken] right after login so all subsequent requests carry
 * the Authorization Bearer header automatically.
 */
object RetrofitClient {

    // ngrok URL for testing the backend from a physical phone.
    // Local dev via Android emulator can use http://10.0.2.2:3000/api/v1/ instead.
    private const val BASE_URL = "https://wool-refuse-anthem.ngrok-free.dev/api/v1/"

    @Volatile private var token: String? = null

    fun setToken(t: String?) { token = t }

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            token?.let { addHeader("Authorization", "Bearer $it") }
        }.build()
        chain.proceed(req)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
