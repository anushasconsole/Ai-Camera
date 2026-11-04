package com.aicamera.app.network

import com.aicamera.app.data.model.CreateSessionResponse
import com.aicamera.app.data.model.SubmitBatchRequest
import com.aicamera.app.data.model.SubmitBatchResponse
import com.aicamera.app.data.model.UploadPhotoResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface ApiService {

    @POST("sessions")
    suspend fun createOrGetSession(
        @Header("Authorization") bearerToken: String,
    ): CreateSessionResponse

    @GET("sessions/{sessionId}")
    suspend fun getSession(
        @Header("Authorization") bearerToken: String,
        @Path("sessionId") sessionId: String,
    ): Map<String, Any>

    @POST("sessions/{sessionId}/join")
    suspend fun joinSession(
        @Header("Authorization") bearerToken: String,
        @Path("sessionId") sessionId: String,
    ): Map<String, Any>

    @Multipart
    @POST("sessions/{sessionId}/photos")
    suspend fun uploadPhoto(
        @Header("Authorization") bearerToken: String,
        @Path("sessionId") sessionId: String,
        @Part file: MultipartBody.Part,
        @Part("batch_id") batchId: RequestBody,
        @Part("device_id") deviceId: RequestBody,
    ): UploadPhotoResponse

    @DELETE("sessions/{sessionId}/photos/{photoId}")
    suspend fun deletePhoto(
        @Header("Authorization") bearerToken: String,
        @Path("sessionId") sessionId: String,
        @Path("photoId") photoId: String,
    )

    @POST("batches")
    suspend fun submitBatch(
        @Header("Authorization") bearerToken: String,
        @Body request: SubmitBatchRequest,
    ): SubmitBatchResponse

    @GET("batches/{batchId}")
    suspend fun getBatch(
        @Header("Authorization") bearerToken: String,
        @Path("batchId") batchId: String,
    ): Map<String, Any>
}
