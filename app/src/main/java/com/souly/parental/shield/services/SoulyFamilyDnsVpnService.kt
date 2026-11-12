package com.souly.parental.shield.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.dto.BrowsingLogItemDto
import java.util.concurrent.ConcurrentLinkedQueue

class SoulyFamilyDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START_DNS_SHIELD = "com.souly.parental.shield.START_DNS_SHIELD"
        const val ACTION_STOP_DNS_SHIELD = "com.souly.parental.shield.STOP_DNS_SHIELD"

        @Volatile
        private var activeBlockedDomains: Set<String> = emptySet()

        private val pendingBrowsingLogs = ConcurrentLinkedQueue<BrowsingLogItemDto>()

        fun getActiveBlockedDomains(): Set<String> = activeBlockedDomains

        fun recordFilteredDomainEvent(domain: String) {
            pendingBrowsingLogs.add(
                BrowsingLogItemDto(
                    page_title = "حظر نطاق محظور: $domain",
                    url = "https://$domain",
                    browser_package = "Souly_DNS_Shield",
                    is_private_tab = false,
                    filter_status = "filtered_by_dns"
                )
            )
            while (pendingBrowsingLogs.size > 25) {
                pendingBrowsingLogs.poll()
            }
        }

        fun drainPendingBrowsingLogs(): List<BrowsingLogItemDto> {
            val list = mutableListOf<BrowsingLogItemDto>()
            while (true) {
                val item = pendingBrowsingLogs.poll() ?: break
                list.add(item)
            }
            return list
        }

        fun startFilterIfPermitted(context: Context, blockedDomains: List<String> = emptyList()) {
            val appCtx = context.applicationContext
            val prepIntent = prepare(appCtx)
            if (prepIntent == null) {
                val intent = Intent(appCtx, SoulyFamilyDnsVpnService::class.java).apply {
                    action = ACTION_START_DNS_SHIELD
                    putStringArrayListExtra("blocked_domains", ArrayList(blockedDomains))
                }
                appCtx.startService(intent)
            }
        }

        fun stopFilter(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, SoulyFamilyDnsVpnService::class.java).apply {
                action = ACTION_STOP_DNS_SHIELD
            }
            appCtx.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_DNS_SHIELD) {
            closeTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        val passedDomains = intent?.getStringArrayListExtra("blocked_domains")
        val policyDomains = SecureTokenStore(applicationContext).getCachedPolicy()
            ?.web_filter_rules
            ?.filter { it.is_enabled }
            ?.map { it.target_value.lowercase().trim() }
            ?: emptyList()

        val merged = mutableSetOf<String>()
        passedDomains?.forEach { if (it.isNotBlank()) merged.add(it.lowercase().trim()) }
        policyDomains.forEach { if (it.isNotBlank()) merged.add(it) }
        activeBlockedDomains = merged

        establishFamilyDnsOnlyInterface()
        return START_STICKY
    }

    private fun establishFamilyDnsOnlyInterface() {
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("Souly Family Safe DNS Shield")
                .addAddress("10.215.173.1", 32)
                .addDnsServer("1.1.1.3")
                .addDnsServer("1.0.0.3")
                .setBlocking(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            closeTunnel()
        }
    }

    private fun closeTunnel() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
        } finally {
            vpnInterface = null
        }
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }
}
