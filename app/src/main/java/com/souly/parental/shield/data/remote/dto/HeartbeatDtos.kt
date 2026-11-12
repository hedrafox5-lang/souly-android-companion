package com.souly.parental.shield.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SimSlotDto(
    @SerializedName("slot_index") val slot_index: Int = 0,
    @SerializedName("carrier_name") val carrier_name: String = "Carrier",
    @SerializedName("sim_state") val sim_state: String = "READY",
    @SerializedName("fingerprint_hash") val fingerprint_hash: String = "",
    @SerializedName("change_detected") val change_detected: Boolean = false
)

data class HeartbeatRequest(
    @SerializedName("device_uuid") val device_uuid: String,
    @SerializedName("battery_level") val battery_level: Int,
    @SerializedName("battery_percent") val battery_percent: Int,
    @SerializedName("is_charging") val is_charging: Boolean,
    @SerializedName("is_screen_on") val is_screen_on: Boolean,
    @SerializedName("current_foreground_app") val current_foreground_app: String?,
    @SerializedName("current_activity_subtitle") val current_activity_subtitle: String?,
    @SerializedName("wifi_ssid") val wifi_ssid: String?,
    @SerializedName("gps_accuracy") val gps_accuracy: Float = 5.0f,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("sim_slots") val sim_slots: List<SimSlotDto>? = null
)

data class PendingCommandDto(
    @SerializedName("command_uuid") val command_uuid: String,
    @SerializedName("command_type") val command_type: String,
    @SerializedName("payload") val payload: Map<String, Any?>? = null
)

data class HeartbeatData(
    @SerializedName("sim_changed_alert") val sim_changed_alert: Boolean = false,
    @SerializedName("pending_commands") val pending_commands: List<PendingCommandDto>? = emptyList()
)

data class CommandAckRequest(
    @SerializedName("status") val status: String = "executed",
    @SerializedName("execution_response") val execution_response: Map<String, Any?> = emptyMap()
)
