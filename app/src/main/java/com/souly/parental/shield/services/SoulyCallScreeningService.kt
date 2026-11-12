package com.souly.parental.shield.services

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.souly.parental.shield.data.prefs.SecureTokenStore

class SoulyCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val rawHandle = callDetails.handle?.schemeSpecificPart ?: ""
        val normalizedIncoming = rawHandle.replace(Regex("\\D+"), "")

        val store = SecureTokenStore(applicationContext)
        val restrictedNumbers = store.getCachedPolicy()?.restricted_contacts ?: emptyList()

        val shouldBlock = normalizedIncoming.isNotBlank() && restrictedNumbers.any { ruleNum ->
            val normRule = ruleNum.replace(Regex("\\D+"), "")
            normRule.isNotBlank() && (normalizedIncoming.endsWith(normRule) || normRule.endsWith(normalizedIncoming))
        }

        val responseBuilder = CallResponse.Builder()
        if (shouldBlock) {
            responseBuilder
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(true)
        } else {
            responseBuilder
                .setDisallowCall(false)
                .setRejectCall(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            respondToCall(callDetails, responseBuilder.build())
        }
    }
}
