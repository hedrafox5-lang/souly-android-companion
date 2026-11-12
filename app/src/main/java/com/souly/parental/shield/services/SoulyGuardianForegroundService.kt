package com.souly.parental.shield.services

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.souly.parental.shield.R
import com.souly.parental.shield.SoulyShieldApp
import com.souly.parental.shield.admin.PolicyEnforcerManager
import com.souly.parental.shield.data.local.AppDatabase
import com.souly.parental.shield.data.local.OfflineTelemetryEntity
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.AppUsageSyncRequest
import com.souly.parental.shield.data.remote.dto.CommandAckRequest
import com.souly.parental.shield.data.remote.dto.HeartbeatRequest
import com.souly.parental.shield.data.remote.dto.InstalledAppUsageDto
import com.souly.parental.shield.data.remote.dto.PendingCommandDto
import com.souly.parental.shield.data.remote.dto.SimSlotDto
import com.souly.parental.shield.ui.ChildDashboardActivity
import com.souly.parental.shield.workers.PeriodicPolicySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SoulyGuardianForegroundService : LifecycleService() {

    private var pulseJob: Job? = null
    private var streamRelayJob: Job? = null
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var usageTracker: AppUsageTrackerManager
    private lateinit var locationManager: SafeZoneLocationManager
    private lateinit var policyEnforcer: PolicyEnforcerManager
    private val gson = Gson()
    private var fastTickCounter = 0
    private var cachedTodayUsageMap: Map<String, Int> = emptyMap()

    companion object {
        const val NOTIFICATION_ID = 4201

        fun canStartFromBackgroundSafely(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val appCtx = context.applicationContext
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val ignoringBattery = pm?.isIgnoringBatteryOptimizations(appCtx.packageName) == true
            val hasOverlay = WindowOverlayLockManager.canDrawOverlay(appCtx)
            return ignoringBattery || hasOverlay
        }

        fun startServiceIfPaired(context: Context, fromBackgroundWorker: Boolean = false) {
            val appCtx = context.applicationContext
            if (!SecureTokenStore(appCtx).isDevicePaired()) return
            if (fromBackgroundWorker && !canStartFromBackgroundSafely(appCtx)) {
                return
            }
            try {
                val intent = Intent(appCtx, SoulyGuardianForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tokenStore = SecureTokenStore(applicationContext)
        usageTracker = AppUsageTrackerManager(applicationContext)
        locationManager = SafeZoneLocationManager(applicationContext)
        policyEnforcer = PolicyEnforcerManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        promoteToForegroundWithSafeTypes()
        startHeartbeatAndCommandLoop()
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        PeriodicPolicySyncWorker.enqueuePeriodicWork(applicationContext)
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        PeriodicPolicySyncWorker.enqueuePeriodicWork(applicationContext)
        stopSelf(startId)
    }

    private fun promoteToForegroundWithSafeTypes() {
        val notification = buildPersistentGuardianNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasFgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasFgLoc && hasBgLoc) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (MediaProjectionScreenCapturer.hasProjectionGrant()) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasFgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasFgLoc && hasBgLoc) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildPersistentGuardianNotification(): Notification {
        val dashIntent = Intent(this, ChildDashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            dashIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (tokenStore.getQuickMode()) {
            "study" -> "وضع المذاكرة والتركيز 📚"
            "sleep" -> "وضع النوم الليلي 🌙"
            else -> "حماية نشطة ومتوازنة 🛡️"
        }

        return NotificationCompat.Builder(this, SoulyShieldApp.CHANNEL_GUARDIAN_ID)
            .setSmallIcon(R.drawable.ic_shield_logo)
            .setContentTitle("درع سولي العائلي متصل")
            .setContentText("الحالة: $modeLabel")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startHeartbeatAndCommandLoop() {
        if (pulseJob?.isActive == true) return
        pulseJob = lifecycleScope.launch(Dispatchers.IO) {
            syncFullPolicyAndUsage()
            while (isActive && tokenStore.isDevicePaired()) {
                if (fastTickCounter % 5 == 0) {
                    refreshLocalUsageCache()
                }
                enforceForegroundAppMultiTierGuard()
                if (fastTickCounter % 8 == 0) {
                    executeSinglePulseCycle()
                }
                if (fastTickCounter % 20 == 0 && fastTickCounter > 0) {
                    syncFullPolicyAndUsage()
                }
                fastTickCounter++
                delay(3_000L)
            }
        }
    }

    private fun refreshLocalUsageCache() {
        try {
            val usageList = usageTracker.collectTodayAppUsage()
            cachedTodayUsageMap = usageList.associateBy({ it.package_name }, { it.usage_today_minutes })
        } catch (e: Exception) {
        }
    }

    private fun enforceForegroundAppMultiTierGuard() {
        if (!isScreenInteractive()) return
        val currentPkg = usageTracker.getCurrentForegroundPackage() ?: return
        if (currentPkg == packageName || currentPkg.startsWith("com.android.systemui") || currentPkg.startsWith("com.android.settings") || currentPkg.contains("launcher")) {
            if (!tokenStore.isFamilyPaused() && tokenStore.getQuickMode() != "sleep") {
                WindowOverlayLockManager.dismissOverlayLock(applicationContext)
            }
            return
        }

        val quickMode = tokenStore.getQuickMode()
        if (tokenStore.isFamilyPaused() || quickMode == "sleep") {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = tokenStore.getFamilyPauseMessage(),
                isAppBlockOnly = false
            )
            return
        }

        val policy = tokenStore.getCachedPolicy() ?: return
        val matchedRule = policy.app_rules?.firstOrNull { it.package_name == currentPkg }
        val usedMinutes = cachedTodayUsageMap[currentPkg] ?: 0
        val quotaMinutes = (matchedRule?.daily_limit_hours ?: 24).coerceAtLeast(1) * 60

        if (matchedRule != null && matchedRule.is_restricted) {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "تطبيق (${matchedRule.app_name ?: currentPkg}) مقيد حالياً من قِبل ولي الأمر 🛡️",
                isAppBlockOnly = true
            )
            return
        }

        if (matchedRule != null && usedMinutes >= quotaMinutes) {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "انتهى الوقت اليومي المسموح لتطبيق (${matchedRule.app_name ?: currentPkg}) (${matchedRule.daily_limit_hours} ساعة) ⏱️",
                isAppBlockOnly = true
            )
            return
        }

        if (quickMode == "study" && isEntertainmentOrGamePackage(currentPkg)) {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "الهاتف الآن في (وضع المذاكرة والتركيز 📚) — تطبيقات الألعاب والترفيه مؤجلة لحين انتهاء وقت المذاكرة.",
                isAppBlockOnly = true
            )
        }
    }

    private fun isEntertainmentOrGamePackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("tiktok") || lower.contains("pubg") || lower.contains("roblox") ||
            lower.contains("freefire") || lower.contains("instagram") || lower.contains("facebook") ||
            lower.contains("snapchat") || lower.contains("game")
    }

    private suspend fun executeSinglePulseCycle() {
        val deviceUuid = tokenStore.getDeviceUuid() ?: return
        val battery = readBatteryState()
        val isScreenOn = isScreenInteractive()
        val fgApp = usageTracker.getCurrentForegroundAppLabel()
        val wifiName = readNetworkAndWifiLabel()
        val loc = locationManager.getCurrentLocationOrNull()
        val simSlots = readSafeSimSlotTelemetry()

        val req = HeartbeatRequest(
            device_uuid = deviceUuid,
            battery_level = battery.first,
            battery_percent = battery.first,
            is_charging = battery.second,
            is_screen_on = isScreenOn,
            current_foreground_app = fgApp,
            current_activity_subtitle = if (isScreenOn) "الشاشة نشطة الآن" else "الشاشة مغلقة",
            wifi_ssid = wifiName,
            gps_accuracy = loc?.accuracy ?: 5.0f,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            sim_slots = simSlots
        )

        val api = SoulyApiClient.create(applicationContext)
        val dao = AppDatabase.getInstance(applicationContext).offlineTelemetryDao()

        try {
            val res = api.sendHeartbeat(req)
            val nowTime = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            tokenStore.saveLastHeartbeatTime(nowTime)

            val pendingCmds = res.data?.pending_commands ?: emptyList()
            if (pendingCmds.isNotEmpty()) {
                handlePendingCommands(pendingCmds)
            }

            flushOfflineQueue(dao)
        } catch (e: Exception) {
            dao.insertItem(
                OfflineTelemetryEntity(
                    payloadType = "heartbeat",
                    payloadJson = gson.toJson(req)
                )
            )
        }
    }

    private suspend fun flushOfflineQueue(dao: com.souly.parental.shield.data.local.OfflineTelemetryDao) {
        val api = SoulyApiClient.create(applicationContext)
        val items = dao.getPendingItems()
        for (item in items) {
            try {
                if (item.payloadType == "heartbeat") {
                    val hb = gson.fromJson(item.payloadJson, HeartbeatRequest::class.java)
                    api.sendHeartbeat(hb)
                }
                dao.deleteById(item.id)
            } catch (e: Exception) {
                break
            }
        }
    }

    private suspend fun handlePendingCommands(commands: List<PendingCommandDto>) {
        val api = SoulyApiClient.create(applicationContext)
        for (cmd in commands) {
            val payload = cmd.payload ?: emptyMap()
            when (cmd.command_type) {
                "FAMILY_PAUSE_LOCATOR_TONE", "EMERGENCY_PANIC_LOCK_SIREN" -> {
                    val paused = (payload["paused"] as? Boolean) ?: (payload["locked"] as? Boolean) ?: true
                    val siren = (payload["siren"] as? Boolean) ?: false
                    val msg = (payload["message"] as? String) ?: "تم إيقاف الهاتف مؤقتاً من قِبل والدك."
                    SirenAndPauseController.applyPauseAndSirenState(applicationContext, paused, siren, msg, isAppBlockOnly = false)
                }
                "SET_QUICK_BALANCE_MODE", "SET_QUICK_LOCKDOWN_MODE" -> {
                    val mode = (payload["mode"] as? String) ?: "normal"
                    tokenStore.saveQuickMode(mode)
                    syncFullPolicyAndUsage()
                }
                "CAPTURE_INSTANT_SNAPSHOT", "CAPTURE_INSTANT_SCREENSHOT" -> {
                    uploadSnapshotMultiTier(api)
                }
                "START_WEBRTC_STREAM" -> {
                    val sessionUuid = payload["session_uuid"] as? String
                    if (!sessionUuid.isNullOrBlank()) {
                        startLiveCheckInFrameRelay(sessionUuid)
                    }
                }
                "STOP_WEBRTC_STREAM" -> {
                    streamRelayJob?.cancel()
                    streamRelayJob = null
                }
                "UPDATE_APP_RULE", "SYNC_SAFE_ZONES", "SYNC_SCHEDULE_ROUTINES", "UPDATE_WEB_FILTER_DNS",
                "BLOCK_STRANGER_CONTACT", "TOGGLE_CALL_NUMBER_BLOCK", "TOGGLE_CONTACT_BLOCK" -> {
                    syncFullPolicyAndUsage()
                }
                "FORCE_TELEMETRY_SYNC_PULSE" -> {
                    locationManager.syncCurrentLocationWithHub()
                    syncFullPolicyAndUsage()
                }
            }

            try {
                api.acknowledgeCommand(
                    cmd.command_uuid,
                    CommandAckRequest(
                        status = "executed",
                        execution_response = mapOf("command_type" to cmd.command_type, "executed_by_sdk" to Build.VERSION.SDK_INT)
                    )
                )
            } catch (e: Exception) {
            }
        }
    }

    private fun startLiveCheckInFrameRelay(sessionUuid: String) {
        streamRelayJob?.cancel()
        streamRelayJob = lifecycleScope.launch(Dispatchers.IO) {
            val api = SoulyApiClient.create(applicationContext)
            var frameIndex = 1
            while (isActive && frameIndex <= 36) {
                try {
                    val bytes = MediaProjectionScreenCapturer.captureRealScreenWebpOrNull(applicationContext)
                        ?: generateStatusBitmapBytes("بث اطمئنان مباشر #$frameIndex")
                    val reqFile = bytes.toRequestBody("image/webp".toMediaTypeOrNull())
                    val part = MultipartBody.Part.createFormData("frame", "latest_frame.webp", reqFile)
                    val res = api.uploadStreamFrameRelay(sessionUuid, part)
                    if (res["should_stop"] == true) break
                } catch (e: Exception) {
                    break
                }
                frameIndex++
                delay(5_000L)
            }
        }
    }

    private suspend fun uploadSnapshotMultiTier(api: com.souly.parental.shield.data.remote.SoulyAndroidApiService) {
        try {
            val fgLabel = usageTracker.getCurrentForegroundAppLabel()
            val bytes = MediaProjectionScreenCapturer.captureRealScreenWebpOrNull(applicationContext)
                ?: generateStatusBitmapBytes("لقطة حالة فورية بأمر الأب")
            val reqFile = bytes.toRequestBody("image/webp".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", "snapshot.webp", reqFile)
            val catBody = "snapshot".toRequestBody("text/plain".toMediaTypeOrNull())
            val appBody = fgLabel.toRequestBody("text/plain".toMediaTypeOrNull())
            val reasonBody = "لقطة شاشة فورية بأمر ولي الأمر".toRequestBody("text/plain".toMediaTypeOrNull())

            api.uploadActivitySnapshot(part, catBody, appBody, reasonBody)
        } catch (e: Exception) {
        }
    }

    private fun generateStatusBitmapBytes(subtitle: String): ByteArray {
        val fgLabel = usageTracker.getCurrentForegroundAppLabel()
        val bmp = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#041512"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F5D4")
            textSize = 28f
        }
        canvas.drawText("Souly Child Shield — $subtitle", 40f, 80f, paint)
        paint.color = Color.WHITE
        paint.textSize = 24f
        canvas.drawText("الطفل: ${tokenStore.getChildName()}", 40f, 150f, paint)
        canvas.drawText("التطبيق النشط: $fgLabel", 40f, 210f, paint)
        canvas.drawText("الوضع الحالي: ${tokenStore.getQuickMode()}", 40f, 270f, paint)
        canvas.drawText("الشبكة: ${readNetworkAndWifiLabel()}", 40f, 330f, paint)
        val nowStr = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()).format(Date())
        canvas.drawText("التوقيت: $nowStr", 40f, 390f, paint)

        val bos = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bmp.compress(format, 85, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private suspend fun syncFullPolicyAndUsage() {
        try {
            val api = SoulyApiClient.create(applicationContext)
            val usageList: List<InstalledAppUsageDto> = usageTracker.collectTodayAppUsage()
            cachedTodayUsageMap = usageList.associateBy({ it.package_name }, { it.usage_today_minutes })

            val pendingBrowsing = SoulyFamilyDnsVpnService.drainPendingBrowsingLogs()
            if (usageList.isNotEmpty() || pendingBrowsing.isNotEmpty()) {
                api.syncInstalledAppsAndUsage(
                    AppUsageSyncRequest(
                        installed_apps = usageList,
                        browsing_logs = pendingBrowsing
                    )
                )
            }

            val policyRes = api.getPolicyConfig()
            policyRes.data?.let { policy ->
                policyEnforcer.enforcePolicy(policy, usageList)
            }
        } catch (e: Exception) {
            val cached = tokenStore.getCachedPolicy()
            if (cached != null) {
                policyEnforcer.enforcePolicy(cached)
            }
        }
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = ((level * 100f) / scale.coerceAtLeast(1)).toInt().coerceIn(1, 100)
        return Pair(pct, isCharging)
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    private fun readNetworkAndWifiLabel(): String {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val raw = wm?.connectionInfo?.ssid?.trim('"')
                if (raw.isNullOrBlank() || raw == "<unknown ssid>") "شبكة Wi-Fi منزلية" else raw
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                "بيانات الهاتف (4G/5G)"
            } else {
                "متصل بالشبكة"
            }
        } catch (e: Exception) {
            "Wi-Fi / 4G"
        }
    }

    private fun readSafeSimSlotTelemetry(): List<SimSlotDto> {
        return try {
            val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return emptyList()
            val carrier = tm.simOperatorName?.ifBlank { tm.networkOperatorName }?.ifBlank { "Carrier EG" } ?: "Carrier EG"
            val stateStr = when (tm.simState) {
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_READY -> "READY"
                else -> "ACTIVE"
            }
            val rawFp = "$carrier|${tm.simOperator}|$stateStr"
            val fpHash = MessageDigest.getInstance("SHA-256")
                .digest(rawFp.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)

            listOf(
                SimSlotDto(
                    slot_index = 0,
                    carrier_name = carrier,
                    sim_state = stateStr,
                    fingerprint_hash = fpHash,
                    change_detected = stateStr == "ABSENT"
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
}
