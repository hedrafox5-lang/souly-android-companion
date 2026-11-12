package com.souly.parental.shield.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AppRuleDto(
    @SerializedName("package_name") val package_name: String,
    @SerializedName("app_name") val app_name: String? = null,
    @SerializedName("is_restricted") val is_restricted: Boolean = false,
    @SerializedName("daily_limit_hours") val daily_limit_hours: Int = 2
)

data class SafeZoneDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("zone_name") val zone_name: String = "",
    @SerializedName("zone_type") val zone_type: String = "safe",
    @SerializedName("center_lat") val center_lat: Double = 30.0561,
    @SerializedName("center_lng") val center_lng: Double = 31.3301,
    @SerializedName("radius_meters") val radius_meters: Int = 300,
    @SerializedName("is_active") val is_active: Boolean = true
)

data class ScheduleRoutineDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("routine_name") val routine_name: String = "",
    @SerializedName("target_mode") val target_mode: String = "study",
    @SerializedName("days_of_week_json") val days_of_week_json: List<String>? = null,
    @SerializedName("start_time") val start_time: String = "17:00:00",
    @SerializedName("end_time") val end_time: String = "20:00:00",
    @SerializedName("is_enabled") val is_enabled: Boolean = true
)

data class WebFilterRuleDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("rule_type") val rule_type: String = "custom_domain_block",
    @SerializedName("target_value") val target_value: String = "",
    @SerializedName("is_enabled") val is_enabled: Boolean = true
)

data class PolicyConfigData(
    @SerializedName("feature_flags") val feature_flags: Map<String, Boolean>? = emptyMap(),
    @SerializedName("quick_balance_mode") val quick_balance_mode: String? = "normal",
    @SerializedName("is_family_paused") val is_family_paused: Boolean? = false,
    @SerializedName("family_pause_message") val family_pause_message: String? = null,
    @SerializedName("is_locator_tone_active") val is_locator_tone_active: Boolean? = false,
    @SerializedName("dns_family_filter_enabled") val dns_family_filter_enabled: Boolean? = true,
    @SerializedName("vpn_restriction_enabled") val vpn_restriction_enabled: Boolean? = true,
    @SerializedName("app_rules") val app_rules: List<AppRuleDto>? = emptyList(),
    @SerializedName("safe_zones") val safe_zones: List<SafeZoneDto>? = emptyList(),
    @SerializedName("schedule_routines") val schedule_routines: List<ScheduleRoutineDto>? = emptyList(),
    @SerializedName("web_filter_rules") val web_filter_rules: List<WebFilterRuleDto>? = emptyList(),
    @SerializedName("restricted_contacts") val restricted_contacts: List<String>? = emptyList(),
    @SerializedName("wellbeing_keywords") val wellbeing_keywords: List<String>? = emptyList()
)
