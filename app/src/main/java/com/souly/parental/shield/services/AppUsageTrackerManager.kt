package com.souly.parental.shield.services

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.souly.parental.shield.data.remote.dto.InstalledAppUsageDto
import java.util.Calendar

class AppUsageTrackerManager(private val context: Context) {

    fun getCurrentForegroundPackage(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val end = System.currentTimeMillis()

        if (usm != null) {
            var latestPkg: String? = null
            var latestTimestamp = 0L

            try {
                val events = usm.queryEvents(end - 300_000L, end)
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTimestamp) {
                        latestTimestamp = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            } catch (e: Exception) {
            }

            if (!latestPkg.isNullOrBlank()) {
                return latestPkg
            }

            try {
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 3_600_000L, end)
                val fromStats = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
                if (!fromStats.isNullOrBlank()) {
                    return fromStats
                }
            } catch (e: Exception) {
            }
        }

        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }?.processName
        } catch (e: Exception) {
            null
        }
    }

    fun getCurrentForegroundAppLabel(): String {
        val pkg = getCurrentForegroundPackage() ?: return "الشاشة الرئيسية"
        return resolveAppLabel(pkg)
    }

    fun collectTodayAppUsage(): List<InstalledAppUsageDto> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val preciseMillisByPkg = computeExactForegroundMillisViaEvents(usm, startOfDay, now)

        val fallbackMillisByPkg = if (preciseMillisByPkg.isEmpty()) {
            try {
                usm.queryAndAggregateUsageStats(startOfDay, now)
                    .mapValues { it.value.totalTimeInForeground }
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val result = mutableListOf<InstalledAppUsageDto>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            val millis = preciseMillisByPkg[pkg] ?: fallbackMillisByPkg[pkg] ?: 0L
            val minutes = (millis / 60_000L).toInt().coerceAtLeast(0)
            result.add(
                InstalledAppUsageDto(
                    package_name = pkg,
                    app_name = label,
                    category_label = categorizePackage(pkg),
                    usage_today_minutes = minutes,
                    is_secondary_utility_app = false
                )
            )
        }

        return result.distinctBy { it.package_name }.sortedByDescending { it.usage_today_minutes }.take(40)
    }

    private fun computeExactForegroundMillisViaEvents(
        usm: UsageStatsManager,
        startMillis: Long,
        endMillis: Long
    ): Map<String, Long> {
        val totalMillis = mutableMapOf<String, Long>()
        val lastResumed = mutableMapOf<String, Long>()

        try {
            val events = usm.queryEvents(startMillis, endMillis)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastResumed[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val start = lastResumed.remove(pkg)
                        if (start != null && event.timeStamp > start) {
                            totalMillis[pkg] = (totalMillis[pkg] ?: 0L) + (event.timeStamp - start)
                        }
                    }
                }
            }
            for ((pkg, resumedAt) in lastResumed) {
                if (endMillis > resumedAt) {
                    totalMillis[pkg] = (totalMillis[pkg] ?: 0L) + (endMillis - resumedAt)
                }
            }
        } catch (e: Exception) {
        }

        return totalMillis
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun categorizePackage(pkg: String): String {
        val lower = pkg.lowercase()
        return when {
            lower.contains("whatsapp") || lower.contains("telegram") || lower.contains("facebook") || lower.contains("instagram") || lower.contains("tiktok") -> "تواصل اجتماعي"
            lower.contains("youtube") || lower.contains("netflix") || lower.contains("video") -> "فيديو وترفيه"
            lower.contains("game") || lower.contains("pubg") || lower.contains("roblox") -> "ألعاب"
            lower.contains("chrome") || lower.contains("browser") -> "متصفح إنترنت"
            else -> "تطبيقات الهاتف"
        }
    }
}
