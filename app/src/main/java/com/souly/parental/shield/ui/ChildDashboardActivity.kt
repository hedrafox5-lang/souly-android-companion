package com.souly.parental.shield.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.souly.parental.shield.admin.PolicyEnforcerManager
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.databinding.ActivityChildDashboardBinding
import com.souly.parental.shield.services.SafeZoneLocationManager
import com.souly.parental.shield.services.SoulyGuardianForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildDashboardBinding
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var locationManager: SafeZoneLocationManager
    private lateinit var policyEnforcer: PolicyEnforcerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenStore = SecureTokenStore(this)
        if (!tokenStore.isDevicePaired()) {
            startActivity(Intent(this, OnboardingPairActivity::class.java))
            finish()
            return
        }

        locationManager = SafeZoneLocationManager(this)
        policyEnforcer = PolicyEnforcerManager(this)

        SoulyGuardianForegroundService.startServiceIfPaired(this)
        renderCurrentState()

        binding.btnSyncNow.setOnClickListener {
            syncPolicyNow()
        }

        binding.btnOpenPermissionsSetup.setOnClickListener {
            val intent = Intent(this, OnboardingPairActivity::class.java).apply {
                putExtra("manage_permissions_only", true)
            }
            startActivity(intent)
        }

        binding.btnSendSosPanic.setOnClickListener {
            triggerSosEmergencyAlert()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!tokenStore.isDevicePaired()) {
            startActivity(Intent(this, OnboardingPairActivity::class.java))
            finish()
            return
        }
        renderCurrentState()
    }

    private fun renderCurrentState() {
        val childName = tokenStore.getChildName()
        val uuid = tokenStore.getDeviceUuid() ?: "غير مربوط"
        val mode = tokenStore.getQuickMode()
        val cachedPolicy = tokenStore.getCachedPolicy()

        binding.tvChildWelcome.text = "مرحباً بك يا $childName في درع سولي 🛡️"
        binding.tvDeviceUuidBadge.text = "معرّف الجهاز: $uuid"
        binding.tvActiveModeStatus.text = when (mode) {
            "study" -> "الوضع الحالي: وضع المذاكرة والتركيز 📚"
            "sleep" -> "الوضع الحالي: وضع النوم والراحة الليلية 🌙"
            else -> "الوضع الحالي: الوضع العادي المتوازن ⚡"
        }
        binding.tvLastHeartbeatInfo.text = "آخر مزامنة مع لوحة الوالدين: ${tokenStore.getLastHeartbeatTime()}"

        val dnsOn = tokenStore.isDnsFamilyFilterEnabled()
        binding.tvDnsFilterStatus.text = if (dnsOn) "• فلتر الـ DNS العائلي الآمن: مفعّل ✅" else "• فلتر الـ DNS العائلي الآمن: متوقف"
        binding.tvSafeZonesCount.text = "• النطاقات الجغرافية الآمنة المسجلة: ${cachedPolicy?.safe_zones?.size ?: 1} نطاق"
        binding.tvRoutinesCount.text = "• جداول المذاكرة والنوم المبرمجة: ${cachedPolicy?.schedule_routines?.size ?: 1} جدول"
    }

    private fun syncPolicyNow() {
        binding.btnSyncNow.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = SoulyApiClient.create(this@ChildDashboardActivity)
                val res = api.getPolicyConfig()
                res.data?.let { policy ->
                    policyEnforcer.enforcePolicy(policy)
                }
                locationManager.syncCurrentLocationWithHub(false)
                withContext(Dispatchers.Main) {
                    binding.btnSyncNow.isEnabled = true
                    if (!tokenStore.isDevicePaired()) {
                        startActivity(Intent(this@ChildDashboardActivity, OnboardingPairActivity::class.java))
                        finish()
                        return@withContext
                    }
                    renderCurrentState()
                    Toast.makeText(this@ChildDashboardActivity, "✅ تمت مزامنة السياسات والموقع مع لوحة الأب بنجاح", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnSyncNow.isEnabled = true
                    Toast.makeText(this@ChildDashboardActivity, "⚠️ تعذر الاتصال حالياً، سيتم المزامنة تلقائياً في الخلفية", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun triggerSosEmergencyAlert() {
        binding.btnSendSosPanic.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            locationManager.syncCurrentLocationWithHub(isSosEmergency = true)
            withContext(Dispatchers.Main) {
                binding.btnSendSosPanic.isEnabled = true
                Toast.makeText(this@ChildDashboardActivity, "🆘 تم إرسال إشارة الاستغاثة وموقعك الحالي إلى هاتف والدك فوراً!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
