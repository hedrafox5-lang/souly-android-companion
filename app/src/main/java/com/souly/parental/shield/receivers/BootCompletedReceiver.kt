package com.souly.parental.shield.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.souly.parental.shield.services.SoulyGuardianForegroundService
import com.souly.parental.shield.workers.PeriodicPolicySyncWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PeriodicPolicySyncWorker.enqueuePeriodicWork(context)
        SoulyGuardianForegroundService.startServiceIfPaired(context)
    }
}
