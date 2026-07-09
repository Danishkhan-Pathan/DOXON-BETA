package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            var currentRequest = originalRequest
            var response = chain.proceed(currentRequest)
            
            // If the requested model returns 503 Service Unavailable, 429 Quota Exceeded, or 404
            // automatically fall back to gemini-3.1-flash-lite which has an active quota and high stability.
            if (!response.isSuccessful && (response.code == 503 || response.code == 429 || response.code == 404)) {
                val originalUrl = originalRequest.url.toString()
                if (originalUrl.contains("/models/gemini-3.5-flash") || 
                    originalUrl.contains("/models/gemini-3.1-pro-preview") || 
                    originalUrl.contains("/models/gemini-3.1-flash-lite-preview")) {
                    
                    val fallbackUrl = originalUrl
                        .replace("/models/gemini-3.5-flash", "/models/gemini-3.1-flash-lite")
                        .replace("/models/gemini-3.1-pro-preview", "/models/gemini-3.1-flash-lite")
                        .replace("/models/gemini-3.1-flash-lite-preview", "/models/gemini-3.1-flash-lite")
                    
                    response.close()
                    currentRequest = originalRequest.newBuilder()
                        .url(fallbackUrl)
                        .build()
                    response = chain.proceed(currentRequest)
                }
            }
            
            var tryCount = 0
            val maxLimit = 3
            while (!response.isSuccessful && response.code == 503 && tryCount < maxLimit) {
                tryCount++
                response.close()
                try {
                    Thread.sleep(1000L * tryCount)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                response = chain.proceed(currentRequest)
            }
            response
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        })
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}
