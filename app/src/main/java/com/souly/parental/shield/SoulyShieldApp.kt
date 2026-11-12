package com.souly.parental.shield

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.souly.parental.shield.workers.PeriodicPolicySyncWorker

class SoulyShieldApp : Application() {

    companion object {
        const val CHANNEL_GUARDIAN_ID = "souly_guardian_service_channel"
        const val CHANNEL_ALERTS_ID = "souly_safety_alerts_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        PeriodicPolicySyncWorker.enqueuePeriodicWork(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            val guardianChannel = NotificationChannel(
                CHANNEL_GUARDIAN_ID,
                getString(R.string.channel_guardian_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة درع سولي للرعاية الرقمية وتنظيم وقت الشاشة"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                getString(R.string.channel_alerts_title),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيهات التوقف العائلي وصافرة البحث والطوارئ"
                enableVibration(true)
            }

            manager.createNotificationChannel(guardianChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }
}
