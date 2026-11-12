package com.souly.parental.shield.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.LocationPointDto
import com.souly.parental.shield.data.remote.dto.LocationSyncRequest
import com.souly.parental.shield.data.remote.dto.SafeZoneDto
import com.souly.parental.shield.data.remote.dto.WifiLogDto
import kotlinx.coroutines.tasks.await

class SafeZoneLocationManager(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val tokenStore = SecureTokenStore(context.applicationContext)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationOrNull(): Location? {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return null

        return try {
            val tokenSource = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token).await()
                ?: fusedClient.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncCurrentLocationWithHub(isSosEmergency: Boolean = false): Location? {
        val deviceUuid = tokenStore.getDeviceUuid() ?: return null
        val loc = getCurrentLocationOrNull() ?: return null
        val cachedZones = tokenStore.getCachedPolicy()?.safe_zones ?: emptyList()
        val isOutside = evaluateOutsideSafeZones(loc.latitude, loc.longitude, cachedZones)
        val speedKmh = (loc.speed * 3.6f).coerceAtLeast(0f)

        val point = LocationPointDto(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy_meters = loc.accuracy,
            address_text = if (isSosEmergency) "🆘 نداء استغاثة عائلي فوري (SOS)" else "تحديث الموقع الجغرافي المباشر",
            speed_kmh = speedKmh,
            movement_state = if (isSosEmergency) "طوارئ SOS" else if (speedKmh > 5f) "متحرك" else "ثابت",
            is_outside_safe_zone = isOutside
        )

        val wifiLogs = readConnectedWifiHistoryItem(isOutside)

        try {
            val api = SoulyApiClient.create(context)
            api.syncLocationAndWifi(
                LocationSyncRequest(
                    device_uuid = deviceUuid,
                    is_outside_safe_zone = isOutside,
                    locations = listOf(point),
                    wifi_history = wifiLogs
                )
            )
        } catch (e: Exception) {
        }

        return loc
    }

    private fun readConnectedWifiHistoryItem(isOutside: Boolean): List<WifiLogDto> {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val rawSsid = wm?.connectionInfo?.ssid?.trim('"')
            if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>") {
                listOf(
                    WifiLogDto(
                        ssid = rawSsid,
                        estimated_location_label = if (isOutside) "نقطة اتصال خارج النطاق" else "نقطة اتصال داخل النطاق الآمن",
                        security_type = "WPA2/WPA3"
                    )
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun evaluateOutsideSafeZones(lat: Double, lng: Double, zones: List<SafeZoneDto>): Boolean {
        val activeSafeZones = zones.filter { it.is_active && it.zone_type == "safe" }
        if (activeSafeZones.isEmpty()) return false

        val results = FloatArray(1)
        for (z in activeSafeZones) {
            Location.distanceBetween(lat, lng, z.center_lat, z.center_lng, results)
            if (results[0] <= z.radius_meters) {
                return false
            }
        }
        return true
    }
}
