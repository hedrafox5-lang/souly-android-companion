package com.souly.parental.shield.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: T? = null
)

data class PairDeviceRequest(
    @SerializedName("pairing_code") val pairing_code: String,
    @SerializedName("child_name") val child_name: String? = null,
    @SerializedName("device_model") val device_model: String,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("android_version") val android_version: String,
    @SerializedName("sdk_int") val sdk_int: Int,
    @SerializedName("battery_level") val battery_level: Int = 100
)

data class PairDeviceData(
    @SerializedName("device_uuid") val device_uuid: String,
    @SerializedName("sanctum_token") val sanctum_token: String,
    @SerializedName("sms_command_secret") val sms_command_secret: String,
    @SerializedName("feature_flags") val feature_flags: Map<String, Boolean>? = null,
    @SerializedName("wellbeing_keywords") val wellbeing_keywords: List<String>? = null
)
