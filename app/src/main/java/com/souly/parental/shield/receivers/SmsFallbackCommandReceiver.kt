package com.souly.parental.shield.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.services.SafeZoneLocationManager
import com.souly.parental.shield.services.SirenAndPauseController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SmsFallbackCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val appCtx = context.applicationContext

        val candidateTexts = mutableListOf<String>()
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            msgs?.forEach { sms ->
                val body = sms?.messageBody?.trim()
                if (!body.isNullOrBlank() && body.uppercase().startsWith("SOULY#")) {
                    candidateTexts.add(body)
                }
            }
        } else {
            val direct = intent.getStringExtra("signed_command")?.trim()
            if (!direct.isNullOrBlank()) {
                candidateTexts.add(direct)
            }
        }

        if (candidateTexts.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (rawCmd in candidateTexts) {
                    verifyAndExecuteSignedCommand(appCtx, rawCmd)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        suspend fun verifyAndExecuteSignedCommand(context: Context, rawText: String): Boolean {
            val upper = rawText.trim().uppercase()
            if (!upper.startsWith("SOULY#")) return false
            val parts = upper.split("#")
            if (parts.size < 4) return false

            val shortCode = parts[1]
            val bucketStr = parts[2]
            val incomingSig6 = parts[3]

            val store = SecureTokenStore(context)
            val deviceUuid = store.getDeviceUuid() ?: return false
            val secret = store.getSmsSecret() ?: return false

            val currentBucket = System.currentTimeMillis() / 1000L / 300L
            val parsedBucket = bucketStr.toLongOrNull() ?: return false
            if (kotlin.math.abs(currentBucket - parsedBucket) > 2L) return false

            val payloadToSign = "$deviceUuid|$shortCode|$parsedBucket"
            val expectedSig6 = computeHmacSha256Prefix6(payloadToSign, secret)
            if (expectedSig6 != incomingSig6) return false

            when (shortCode) {
                "PAUSE" -> SirenAndPauseController.applyPauseAndSirenState(
                    context = context,
                    paused = true,
                    siren = false,
                    message = "تم تفعيل وضع التوقف العائلي الطارئ بأمر موثق من ولي الأمر."
                )
                "SIREN" -> SirenAndPauseController.applyPauseAndSirenState(
                    context = context,
                    paused = true,
                    siren = true,
                    message = "نداء بحث عائلي عاجل — يرجى التواصل مع والدك فوراً."
                )
                "LOCATE" -> {
                    SafeZoneLocationManager(context).syncCurrentLocationWithHub(isSosEmergency = true)
                }
            }

            try {
                SoulyApiClient.create(context).acknowledgeSmsFallback(
                    mapOf("device_uuid" to deviceUuid, "command" to shortCode, "status" to "executed")
                )
            } catch (e: Exception) {
            }

            return true
        }

        private fun computeHmacSha256Prefix6(data: String, secret: String): String {
            return try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02X".format(it) }.take(6)
            } catch (e: Exception) {
                ""
            }
        }
    }
}
