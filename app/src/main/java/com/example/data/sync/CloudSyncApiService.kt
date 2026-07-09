package com.example.data.sync

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface CloudSyncApiService {

    @POST("api/sync/auth/register")
    suspend fun registerUser(
        @Body request: RegisterRequest
    ): AuthResponse

    @POST("api/sync/auth/login")
    suspend fun loginUser(
        @Body request: LoginRequest
    ): AuthResponse

    @POST("api/sync/chats/upload")
    suspend fun uploadEncryptedBackup(
        @Header("Authorization") authToken: String,
        @Body request: SyncUploadRequest
    ): SyncUploadResponse

    @GET("api/sync/chats/download")
    suspend fun downloadEncryptedBackup(
        @Header("Authorization") authToken: String
    ): SyncDownloadResponse
}

data class RegisterRequest(
    val email: String,
    val passwordHash: String,
    val clientChallenge: String
)

data class LoginRequest(
    val email: String,
    val passwordHash: String
)

data class AuthResponse(
    val email: String,
    val token: String,
    val message: String,
    val success: Boolean
)

data class SyncUploadRequest(
    val email: String,
    val encryptedPayload: String, // Double protection GCM ciphertext
    val clientTimestamp: Long
)

data class SyncUploadResponse(
    val success: Boolean,
    val lastSyncedTime: Long,
    val message: String
)

data class SyncDownloadResponse(
    val success: Boolean,
    val encryptedPayload: String?,
    val lastSyncedTime: Long,
    val message: String
)
