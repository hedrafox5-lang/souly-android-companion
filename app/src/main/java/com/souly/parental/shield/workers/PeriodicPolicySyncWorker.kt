package com.souly.parental.shield.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.souly.parental.shield.admin.PolicyEnforcerManager
import com.souly.parental.shield.data.local.AppDatabase
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.HeartbeatRequest
import com.souly.parental.shield.services.SafeZoneLocationManager
import com.souly.parental.shield.services.SoulyGuardianForegroundService
import java.util.concurrent.TimeUnit

class PeriodicPolicySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SecureTokenStore(applicationContext)
        if (!store.isDevicePaired()) return Result.success()

        SoulyGuardianForegroundService.startServiceIfPaired(applicationContext, fromBackgroundWorker = true)

        try {
            val api = SoulyApiClient.create(applicationContext)
            val dao = AppDatabase.getInstance(applicationContext).offlineTelemetryDao()
            val gson = Gson()
            for (item in dao.getPendingItems()) {
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

            val policyRes = api.getPolicyConfig()
            policyRes.data?.let { policy ->
                PolicyEnforcerManager(applicationContext).enforcePolicy(policy)
            }
            SafeZoneLocationManager(applicationContext).syncCurrentLocationWithHub()
        } catch (e: Exception) {
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "souly_periodic_guardian_watchdog"

        fun enqueuePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PeriodicPolicySyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
