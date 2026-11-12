package com.souly.parental.shield.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LocationPointDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy_meters") val accuracy_meters: Float,
    @SerializedName("address_text") val address_text: String,
    @SerializedName("speed_kmh") val speed_kmh: Float,
    @SerializedName("movement_state") val movement_state: String,
    @SerializedName("is_outside_safe_zone") val is_outside_safe_zone: Boolean
)

data class WifiLogDto(
    @SerializedName("ssid") val ssid: String,
    @SerializedName("estimated_location_label") val estimated_location_label: String = "شبكة لاسلكية",
    @SerializedName("security_type") val security_type: String = "WPA2"
)

data class LocationSyncRequest(
    @SerializedName("device_uuid") val device_uuid: String,
    @SerializedName("is_outside_safe_zone") val is_outside_safe_zone: Boolean,
    @SerializedName("locations") val locations: List<LocationPointDto>,
    @SerializedName("wifi_history") val wifi_history: List<WifiLogDto> = emptyList()
)

data class LocationSyncResponse(
    @SerializedName("is_outside_safe_zone") val is_outside_safe_zone: Boolean = false,
    @SerializedName("active_safe_zones") val active_safe_zones: List<SafeZoneDto>? = emptyList()
)

data class InstalledAppUsageDto(
    @SerializedName("package_name") val package_name: String,
    @SerializedName("app_name") val app_name: String,
    @SerializedName("category_label") val category_label: String = "تطبيقات الهاتف",
    @SerializedName("usage_today_minutes") val usage_today_minutes: Int = 0,
    @SerializedName("is_secondary_utility_app") val is_secondary_utility_app: Boolean = false
)

data class BrowsingLogItemDto(
    @SerializedName("page_title") val page_title: String,
    @SerializedName("url") val url: String,
    @SerializedName("browser_package") val browser_package: String = "DNS_Shield",
    @SerializedName("is_private_tab") val is_private_tab: Boolean = false,
    @SerializedName("filter_status") val filter_status: String = "filtered_by_dns"
)

data class AppUsageSyncRequest(
    @SerializedName("installed_apps") val installed_apps: List<InstalledAppUsageDto>,
    @SerializedName("browsing_logs") val browsing_logs: List<BrowsingLogItemDto> = emptyList()
)
