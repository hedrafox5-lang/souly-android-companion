package com.souly.parental.shield.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.PairDeviceRequest
import com.souly.parental.shield.services.SoulyGuardianForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AutoPairBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        val tokenStore = SecureTokenStore(appCtx)

        if (tokenStore.isDevicePaired()) {
            return
        }

        val pairingCode = intent?.getStringExtra("pairing_code")?.trim() ?: return
        if (!pairingCode.uppercase().startsWith("SOULY-")) {
            return
        }

        val serverUrl = intent.getStringExtra("server_url")?.trim()
        if (!serverUrl.isNullOrBlank() && serverUrl.startsWith("https://")) {
            tokenStore.setHubBaseUrl(serverUrl)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = SoulyApiClient.create(appCtx)
                val res = api.pairDevice(
                    PairDeviceRequest(
                        pairing_code = pairingCode,
                        device_model = "${Build.MANUFACTURER} ${Build.MODEL}",
                        manufacturer = Build.MANUFACTURER ?: "Android",
                        android_version = "Android ${Build.VERSION.RELEASE}",
                        sdk_int = Build.VERSION.SDK_INT
                    )
                )
                val data = res.data
                if (res.success && data != null) {
                    tokenStore.savePairingCredentials(
                        deviceUuid = data.device_uuid,
                        sanctumToken = data.sanctum_token,
                        smsSecret = data.sms_command_secret,
                        hubBaseUrl = tokenStore.getHubBaseUrl()
                    )
                    SoulyGuardianForegroundService.startServiceIfPaired(appCtx)
                }
            } catch (e: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
