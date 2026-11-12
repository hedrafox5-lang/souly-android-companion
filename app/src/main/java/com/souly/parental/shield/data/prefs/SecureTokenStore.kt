package com.souly.parental.shield.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.souly.parental.shield.data.remote.dto.PolicyConfigData

class SecureTokenStore(context: Context) {

    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)
    private val gson = Gson()

    private fun createSecurePrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "souly_shield_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            appContext.getSharedPreferences("souly_shield_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    fun savePairingCredentials(
        deviceUuid: String,
        sanctumToken: String,
        smsSecret: String,
        hubBaseUrl: String,
        childName: String = "طفل سولي"
    ) {
        prefs.edit()
            .putString("device_uuid", deviceUuid)
            .putString("sanctum_token", sanctumToken)
            .putString("sms_command_secret", smsSecret)
            .putString("hub_base_url", normalizeBaseUrl(hubBaseUrl))
            .putString("child_name", childName)
            .putBoolean("is_paired", true)
            .apply()
    }

    fun clearPairingSession() {
        prefs.edit()
            .remove("device_uuid")
            .remove("sanctum_token")
            .remove("sms_command_secret")
            .putBoolean("is_paired", false)
            .apply()
    }

    fun isDevicePaired(): Boolean {
        return prefs.getBoolean("is_paired", false) && !getSanctumToken().isNullOrBlank()
    }

    fun getDeviceUuid(): String? = prefs.getString("device_uuid", null)

    fun getSanctumToken(): String? = prefs.getString("sanctum_token", null)

    fun getSmsSecret(): String? = prefs.getString("sms_command_secret", null)

    fun getChildName(): String = prefs.getString("child_name", "طفل سولي") ?: "طفل سولي"

    fun setHubBaseUrl(url: String) {
        prefs.edit().putString("hub_base_url", normalizeBaseUrl(url)).apply()
    }

    fun getHubBaseUrl(): String {
        val raw = prefs.getString("hub_base_url", "https://soulyeg.online") ?: "https://soulyeg.online"
        return normalizeBaseUrl(raw)
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith("/api/android/v1")) {
            "$trimmed/"
        } else {
            "$trimmed/api/android/v1/"
        }
    }

    fun saveQuickMode(mode: String) {
        prefs.edit().putString("quick_balance_mode", mode).apply()
    }

    fun getQuickMode(): String = prefs.getString("quick_balance_mode", "normal") ?: "normal"

    fun saveFamilyPauseState(paused: Boolean, siren: Boolean, message: String?) {
        prefs.edit()
            .putBoolean("is_family_paused", paused)
            .putBoolean("is_siren_active", siren)
            .putString("family_pause_message", message ?: "تم إيقاف الهاتف مؤقتاً من قِبل والدك.")
            .apply()
    }

    fun isFamilyPaused(): Boolean = prefs.getBoolean("is_family_paused", false)

    fun getFamilyPauseMessage(): String =
        prefs.getString("family_pause_message", "تم إيقاف الهاتف مؤقتاً من قِبل والدك.") ?: "تم إيقاف الهاتف مؤقتاً من قِبل والدك."

    fun saveDnsAndVpnFlags(dnsEnabled: Boolean, vpnRestricted: Boolean) {
        prefs.edit()
            .putBoolean("dns_family_filter_enabled", dnsEnabled)
            .putBoolean("vpn_restriction_enabled", vpnRestricted)
            .apply()
    }

    fun isDnsFamilyFilterEnabled(): Boolean = prefs.getBoolean("dns_family_filter_enabled", true)

    fun saveLastHeartbeatTime(timestamp: String) {
        prefs.edit().putString("last_heartbeat_time", timestamp).apply()
    }

    fun getLastHeartbeatTime(): String = prefs.getString("last_heartbeat_time", "لم تتم المزامنة بعد") ?: "لم تتم المزامنة بعد"

    fun saveCachedPolicy(policy: PolicyConfigData) {
        prefs.edit().putString("cached_policy_json", gson.toJson(policy)).apply()
        saveQuickMode(policy.quick_balance_mode ?: "normal")
        saveDnsAndVpnFlags(
            policy.dns_family_filter_enabled ?: true,
            policy.vpn_restriction_enabled ?: true
        )
    }

    fun getCachedPolicy(): PolicyConfigData? {
        val raw = prefs.getString("cached_policy_json", null) ?: return null
        return try {
            gson.fromJson(raw, PolicyConfigData::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
