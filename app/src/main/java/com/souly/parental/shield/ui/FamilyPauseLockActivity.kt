package com.souly.parental.shield.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.souly.parental.shield.admin.SoulyDeviceAdminReceiver
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.databinding.ActivityFamilyPauseLockBinding
import com.souly.parental.shield.services.SirenAndPauseController
import com.souly.parental.shield.services.WindowOverlayLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FamilyPauseLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyPauseLockBinding
    private lateinit var tokenStore: SecureTokenStore
    private var isAppBlockOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        binding = ActivityFamilyPauseLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenStore = SecureTokenStore(this)
        applyIntentExtras(intent)

        if (!isAppBlockOnly) {
            enableDeviceOwnerPinningIfAvailable()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isAppBlockOnly) {
                    WindowOverlayLockManager.dismissOverlayLock(this@FamilyPauseLockActivity)
                    navigateToSystemHomeScreen()
                } else if (!tokenStore.isFamilyPaused() && tokenStore.getQuickMode() != "sleep") {
                    WindowOverlayLockManager.dismissOverlayLock(this@FamilyPauseLockActivity)
                    stopPinningSafely()
                    finish()
                }
            }
        })

        binding.btnStopSirenAcknowledge.setOnClickListener {
            SirenAndPauseController.stopEmergencyLocatorSiren()
            if (isAppBlockOnly) {
                WindowOverlayLockManager.dismissOverlayLock(this)
                navigateToSystemHomeScreen()
            } else {
                Toast.makeText(this, "تم إيقاف نغمة التنبيه الصوتية", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCheckUnlockStatus.setOnClickListener {
            if (isAppBlockOnly) {
                WindowOverlayLockManager.dismissOverlayLock(this)
                navigateToSystemHomeScreen()
            } else {
                checkIfParentUnlockedDevice()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntentExtras(intent)
    }

    private fun applyIntentExtras(incomingIntent: Intent?) {
        isAppBlockOnly = incomingIntent?.getBooleanExtra("is_app_block_only", false) ?: false
        val msg = incomingIntent?.getStringExtra("pause_message") ?: tokenStore.getFamilyPauseMessage()
        binding.tvPauseCustomMessage.text = msg
        if (isAppBlockOnly) {
            binding.btnCheckUnlockStatus.text = "🏠 العودة إلى الشاشة الرئيسية للهاتف"
        } else {
            binding.btnCheckUnlockStatus.text = "🔄 التحقق من فك القفل من لوحة الأب"
        }
    }

    private fun navigateToSystemHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun enableDeviceOwnerPinningIfAvailable() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComp = SoulyDeviceAdminReceiver.getComponentName(this)
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(adminComp, arrayOf(packageName))
                startLockTask()
            }
        } catch (e: Exception) {
        }
    }

    private fun stopPinningSafely() {
        try {
            stopLockTask()
        } catch (e: Exception) {
        }
    }

    private fun checkIfParentUnlockedDevice() {
        binding.btnCheckUnlockStatus.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = SoulyApiClient.create(this@FamilyPauseLockActivity)
                val res = api.getPolicyConfig()
                val paused = res.data?.is_family_paused == true
                tokenStore.saveFamilyPauseState(paused, false, res.data?.family_pause_message)
                withContext(Dispatchers.Main) {
                    binding.btnCheckUnlockStatus.isEnabled = true
                    if (!paused) {
                        SirenAndPauseController.stopEmergencyLocatorSiren()
                        WindowOverlayLockManager.dismissOverlayLock(this@FamilyPauseLockActivity)
                        stopPinningSafely()
                        Toast.makeText(this@FamilyPauseLockActivity, "🔓 تم فك الإيقاف المؤقت من لوحة الأب!", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@FamilyPauseLockActivity, "لا يزال وضع التوقف العائلي مفعّلاً من قِبل والدك", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnCheckUnlockStatus.isEnabled = true
                    Toast.makeText(this@FamilyPauseLockActivity, "تأكد من اتصال الإنترنت للتحقق من حالة القفل", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
