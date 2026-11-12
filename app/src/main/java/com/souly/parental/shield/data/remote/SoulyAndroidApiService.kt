package com.souly.parental.shield.data.remote

import com.souly.parental.shield.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface SoulyAndroidApiService {

    @POST("pair-device")
    suspend fun pairDevice(@Body request: PairDeviceRequest): ApiResponse<PairDeviceData>

    @GET("policy-config")
    suspend fun getPolicyConfig(): ApiResponse<PolicyConfigData>

    @POST("heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest): ApiResponse<HeartbeatData>

    @GET("commands/pending")
    suspend fun getPendingCommands(): ApiResponse<HeartbeatData>

    @POST("commands/{commandUuid}/ack")
    suspend fun acknowledgeCommand(
        @Path("commandUuid") commandUuid: String,
        @Body request: CommandAckRequest
    ): ApiResponse<Map<String, Boolean>>

    @POST("sms-fallback/ack")
    suspend fun acknowledgeSmsFallback(
        @Body request: Map<String, String>
    ): ApiResponse<Map<String, Boolean>>

    @POST("sync/location-safe-zones")
    suspend fun syncLocationAndWifi(
        @Body request: LocationSyncRequest
    ): ApiResponse<LocationSyncResponse>

    @POST("sync/app-rules-web-filter")
    suspend fun syncInstalledAppsAndUsage(
        @Body request: AppUsageSyncRequest
    ): ApiResponse<PolicyConfigData>

    @Multipart
    @POST("upload/activity-snapshot")
    suspend fun uploadActivitySnapshot(
        @Part file: MultipartBody.Part,
        @Part("upload_category") uploadCategory: RequestBody,
        @Part("foreground_app_name") foregroundAppName: RequestBody,
        @Part("capture_reason") captureReason: RequestBody
    ): ApiResponse<Map<String, String>>

    @Multipart
    @POST("stream/{sessionUuid}/frame-relay")
    suspend fun uploadStreamFrameRelay(
        @Path("sessionUuid") sessionUuid: String,
        @Part frame: MultipartBody.Part
    ): Map<String, Any?>
}
