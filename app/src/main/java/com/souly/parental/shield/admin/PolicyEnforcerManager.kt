package com.souly.parental.shield.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.dto.InstalledAppUsageDto
import com.souly.parental.shield.data.remote.dto.PolicyConfigData
import com.souly.parental.shield.services.SirenAndPauseController
import com.souly.parental.shield.services.SoulyFamilyDnsVpnService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PolicyEnforcerManager(private val context: Context) {

    private val tokenStore = SecureTokenStore(context.applicationContext)

    fun enforcePolicy(policy: PolicyConfigData, todayUsage: List<InstalledAppUsageDto> = emptyList()) {
        tokenStore.saveCachedPolicy(policy)

        val effectiveMode = resolveActiveModeWithSchedules(policy)
        tokenStore.saveQuickMode(effectiveMode)

        val shouldPause = (policy.is_family_paused == true) || (effectiveMode == "sleep")
        val sirenActive = policy.is_locator_tone_active == true
        val pauseMessage = policy.family_pause_message
            ?: if (effectiveMode == "sleep") "وقت النوم والراحة الليلية المحدد من قِبل والدك 🌙"
            else "تم إيقاف الهاتف مؤقتاً من قِبل والدك."

        SirenAndPauseController.applyPauseAndSirenState(
            context = context,
            paused = shouldPause,
            siren = sirenActive,
            message = pauseMessage,
            isAppBlockOnly = false
        )

        val blockedDomains = policy.web_filter_rules
            ?.filter { it.is_enabled }
            ?.map { it.target_value }
            ?: emptyList()

        if (policy.dns_family_filter_enabled == true) {
            SoulyFamilyDnsVpnService.startFilterIfPermitted(context, blockedDomains)
        } else {
            SoulyFamilyDnsVpnService.stopFilter(context)
        }

        enforceDeviceOwnerAndVpnRestrictions(policy, effectiveMode, todayUsage)
    }

    private fun resolveActiveModeWithSchedules(policy: PolicyConfigData): String {
        val baseMode = policy.quick_balance_mode ?: "normal"
        if (baseMode != "normal") return baseMode

        val routines = policy.schedule_routines?.filter { it.is_enabled } ?: return "normal"
        val nowCal = Calendar.getInstance()
        val dayShort = SimpleDateFormat("EEE", Locale.US).format(nowCal.time)
        val currentHm = SimpleDateFormat("HH:mm", Locale.US).format(nowCal.time)

        for (routine in routines) {
            val days = routine.days_of_week_json ?: emptyList()
            if (days.isEmpty() || days.any { it.equals(dayShort, ignoreCase = true) }) {
                val startHm = routine.start_time.take(5)
                val endHm = routine.end_time.take(5)
                if (isTimeWithinWindow(currentHm, startHm, endHm)) {
                    return routine.target_mode
                }
            }
        }
        return baseMode
    }

    private fun isTimeWithinWindow(currentHm: String, startHm: String, endHm: String): Boolean {
        return if (startHm <= endHm) {
            currentHm in startHm..endHm
        } else {
            currentHm >= startHm || currentHm <= endHm
        }
    }

    private fun enforceDeviceOwnerAndVpnRestrictions(
        policy: PolicyConfigData,
        effectiveMode: String,
        todayUsage: List<InstalledAppUsageDto>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val adminComp = SoulyDeviceAdminReceiver.getComponentName(context)

        try {
            if (policy.vpn_restriction_enabled == true) {
                dpm.addUserRestriction(adminComp, UserManager.DISALLOW_CONFIG_VPN)
            } else {
                dpm.clearUserRestriction(adminComp, UserManager.DISALLOW_CONFIG_VPN)
            }
        } catch (e: Exception) {
        }

        val usageMap = todayUsage.associateBy({ it.package_name }, { it.usage_today_minutes })
        val toSuspend = mutableListOf<String>()
        val toUnsuspend = mutableListOf<String>()

        for (rule in (policy.app_rules ?: emptyList())) {
            val pkg = rule.package_name
            if (pkg == context.packageName || pkg.startsWith("com.android.settings")) continue

            val usedMinutes = usageMap[pkg] ?: 0
            val quotaMinutes = (rule.daily_limit_hours.coerceAtLeast(1)) * 60
            val shouldBlock = rule.is_restricted || (usedMinutes >= quotaMinutes) || (effectiveMode == "sleep")

            if (shouldBlock) {
                toSuspend.add(pkg)
            } else {
                toUnsuspend.add(pkg)
            }
        }

        try {
            if (toSuspend.isNotEmpty()) {
                dpm.setPackagesSuspended(adminComp, toSuspend.toTypedArray(), true)
            }
            if (toUnsuspend.isNotEmpty()) {
                dpm.setPackagesSuspended(adminComp, toUnsuspend.toTypedArray(), false)
            }
        } catch (e: Exception) {
        }
    }
}
