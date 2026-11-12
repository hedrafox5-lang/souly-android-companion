package com.souly.parental.shield.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.souly.parental.shield.R
import com.souly.parental.shield.SoulyShieldApp
import com.souly.parental.shield.admin.SoulyDeviceAdminReceiver
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.ui.FamilyPauseLockActivity

object SirenAndPauseController {

    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSirenPlaying = false
    private const val PAUSE_ALERT_NOTIFICATION_ID = 4209

    fun applyPauseAndSirenState(
        context: Context,
        paused: Boolean,
        siren: Boolean,
        message: String?,
        isAppBlockOnly: Boolean = false
    ) {
        val appContext = context.applicationContext
        val store = SecureTokenStore(appContext)
        val resolvedMsg = message ?: store.getFamilyPauseMessage()

        if (!isAppBlockOnly) {
            store.saveFamilyPauseState(paused, siren, resolvedMsg)
        }

        if (siren) {
            startEmergencyLocatorSiren(appContext)
        } else if (!paused) {
            stopEmergencyLocatorSiren()
        }

        if (paused) {
            if (!isAppBlockOnly) {
                lockHardwareScreenIfAdmin(appContext)
            }

            if (WindowOverlayLockManager.canDrawOverlay(appContext)) {
                WindowOverlayLockManager.showOverlayLock(appContext, resolvedMsg, isAppBlockOnly)
            }

            showFullScreenPauseNotification(appContext, resolvedMsg, isAppBlockOnly)

            try {
                val lockIntent = Intent(appContext, FamilyPauseLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("pause_message", resolvedMsg)
                    putExtra("is_app_block_only", isAppBlockOnly)
                }
                appContext.startActivity(lockIntent)
            } catch (e: Exception) {
            }
        } else {
            WindowOverlayLockManager.dismissOverlayLock(appContext)
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm?.cancel(PAUSE_ALERT_NOTIFICATION_ID)
        }
    }

    private fun lockHardwareScreenIfAdmin(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComp = SoulyDeviceAdminReceiver.getComponentName(context)
            if (dpm.isAdminActive(adminComp)) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
        }
    }

    private fun showFullScreenPauseNotification(context: Context, message: String, isAppBlockOnly: Boolean) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val fullScreenIntent = Intent(context, FamilyPauseLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("pause_message", message)
                putExtra("is_app_block_only", isAppBlockOnly)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                99,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, SoulyShieldApp.CHANNEL_ALERTS_ID)
                .setSmallIcon(R.drawable.ic_shield_logo)
                .setContentTitle(if (isAppBlockOnly) "🛡️ تنبيه تقييد تطبيق" else "⏸️ وضع التوقف العائلي المؤقت")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(!isAppBlockOnly)
                .setAutoCancel(isAppBlockOnly)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .build()

            nm.notify(PAUSE_ALERT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
        }
    }

    fun startEmergencyLocatorSiren(context: Context) {
        if (isSirenPlaying) return
        isSirenPlaying = true
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                it.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            }
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 15_000)
            mainHandler.postDelayed({ stopEmergencyLocatorSiren() }, 15_500L)
        } catch (e: Exception) {
            isSirenPlaying = false
        }
    }

    fun stopEmergencyLocatorSiren() {
        isSirenPlaying = false
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (e: Exception) {
        } finally {
            toneGenerator = null
        }
    }
}
