package com.souly.parental.shield.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.souly.parental.shield.admin.SoulyDeviceAdminReceiver
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.PairDeviceRequest
import com.souly.parental.shield.databinding.ActivityOnboardingPairBinding
import com.souly.parental.shield.services.MediaProjectionScreenCapturer
import com.souly.parental.shield.services.SoulyFamilyDnsVpnService
import com.souly.parental.shield.services.SoulyGuardianForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

class OnboardingPairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingPairBinding
    private lateinit var tokenStore: SecureTokenStore
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isProcessingBarcode = false

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startQrScannerCamera()
        } else {
            binding.tvScannerOverlayHint.text = "يمكنك كتابة كود الربط يدوياً بالأسفل بدون كاميرا"
        }
    }

    private val bgLocationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestIgnoreBatteryOptimizationsIfNeeded()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasForegroundLoc = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (hasForegroundLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) {
                bgLocationPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return@registerForActivityResult
            }
        }
        requestIgnoreBatteryOptimizationsIfNeeded()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
        Toast.makeText(this, "تم تحديث صلاحيات الموقع الجغرافي والإشعارات والـ SMS ✅", Toast.LENGTH_SHORT).show()
    }

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        SoulyFamilyDnsVpnService.startFilterIfPermitted(this)
        requestMediaProjectionIfNeeded()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            MediaProjectionScreenCapturer.saveProjectionGrant(result.resultCode, result.data)
            SoulyGuardianForegroundService.startServiceIfPaired(this)
            Toast.makeText(this, "تم تفعيل لقطات الشاشة الحية والـ DNS العائلي ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private val callScreeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = SecureTokenStore(this)
        val managePermissionsOnly = intent.getBooleanExtra("manage_permissions_only", false)

        if (tokenStore.isDevicePaired() && !managePermissionsOnly) {
            SoulyGuardianForegroundService.startServiceIfPaired(this)
            navigateToChildDashboard()
            return
        }

        binding = ActivityOnboardingPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissionButtons()
        setupPairingButton()

        if (managePermissionsOnly && tokenStore.isDevicePaired()) {
            binding.qrScannerContainer.visibility = View.GONE
            binding.pairingFormContainer.visibility = View.GONE
        } else {
            checkCameraAndStartScanner()
            checkIncomingDeepLink(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIncomingDeepLink(intent)
    }

    private fun checkIncomingDeepLink(incomingIntent: Intent?) {
        val data: Uri = incomingIntent?.data ?: return
        if (data.scheme == "soulyshield" && data.host == "pair") {
            val code = data.getQueryParameter("code") ?: data.getQueryParameter("pairing_code")
            val server = data.getQueryParameter("server")
            val child = data.getQueryParameter("child_name")
            if (!server.isNullOrBlank()) binding.etServerUrl.setText(server)
            if (!child.isNullOrBlank()) binding.etChildName.setText(child)
            if (!code.isNullOrBlank()) {
                binding.etPairingCode.setText(code)
                performDevicePairing()
            }
        }
    }

    private fun checkCameraAndStartScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startQrScannerCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startQrScannerCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && !isProcessingBarcode) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                val raw = barcode.rawValue ?: continue
                                handleScannedQrPayload(raw)
                                break
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScannedQrPayload(rawText: String) {
        if (isProcessingBarcode) return
        isProcessingBarcode = true
        runOnUiThread {
            try {
                if (rawText.trim().startsWith("{")) {
                    val json = JSONObject(rawText)
                    val code = json.optString("pairing_code", "")
                    val hubApi = json.optString("hub_api_base", "")
                    val childName = json.optString("child_name", "")
                    if (code.isNotBlank()) binding.etPairingCode.setText(code)
                    if (hubApi.isNotBlank()) binding.etServerUrl.setText(hubApi.removeSuffix("/api/android/v1"))
                    if (childName.isNotBlank()) binding.etChildName.setText(childName)
                } else {
                    binding.etPairingCode.setText(rawText.trim())
                }
                binding.tvPairingStatus.text = "تم التقاط رمز الـ QR بنجاح! جاري الربط التلقائي..."
                performDevicePairing()
            } catch (e: Exception) {
                isProcessingBarcode = false
            }
        }
    }

    private fun setupPairingButton() {
        binding.btnPairDeviceNow.setOnClickListener {
            performDevicePairing()
        }
    }

    private fun performDevicePairing() {
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val childName = binding.etChildName.text.toString().trim().ifBlank { "طفل سولي" }

        if (code.isBlank()) {
            Toast.makeText(this, "يرجى إدخال كود الربط المشفر أو مسح رمز الـ QR", Toast.LENGTH_SHORT).show()
            isProcessingBarcode = false
            return
        }

        tokenStore.setHubBaseUrl(serverUrl)
        binding.btnPairDeviceNow.isEnabled = false
        binding.tvPairingStatus.text = "⏳ جاري الاتصال بسيرفر سولي وإتمام الربط المشفر..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = SoulyApiClient.create(this@OnboardingPairActivity)
                val response = api.pairDevice(
                    PairDeviceRequest(
                        pairing_code = code,
                        child_name = childName,
                        device_model = "${Build.MANUFACTURER} ${Build.MODEL}",
                        manufacturer = Build.MANUFACTURER ?: "Android",
                        android_version = "Android ${Build.VERSION.RELEASE}",
                        sdk_int = Build.VERSION.SDK_INT
                    )
                )

                withContext(Dispatchers.Main) {
                    val data = response.data
                    if (response.success && data != null) {
                        tokenStore.savePairingCredentials(
                            deviceUuid = data.device_uuid,
                            sanctumToken = data.sanctum_token,
                            smsSecret = data.sms_command_secret,
                            hubBaseUrl = serverUrl,
                            childName = childName
                        )
                        SoulyGuardianForegroundService.startServiceIfPaired(this@OnboardingPairActivity)
                        Toast.makeText(this@OnboardingPairActivity, "🎉 تم ربط الهاتف بلوحة تحكم الوالدين بنجاح!", Toast.LENGTH_LONG).show()
                        navigateToChildDashboard()
                    } else {
                        binding.btnPairDeviceNow.isEnabled = true
                        isProcessingBarcode = false
                        binding.tvPairingStatus.text = "⚠️ تعذر الربط: ${response.message ?: "تأكد من صحة الكود"}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnPairDeviceNow.isEnabled = true
                    isProcessingBarcode = false
                    binding.tvPairingStatus.text = "⚠️ خطأ في الاتصال بالسيرفر: تأكد من الرابط واتصال الإنترنت"
                }
            }
        }
    }

    private fun setupPermissionButtons() {
        binding.btnGrantLocation.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECEIVE_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            locationPermLauncher.launch(perms.toTypedArray())
        }

        binding.btnGrantUsageStats.setOnClickListener {
            if (!hasUsageStatsPermission()) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else {
                Toast.makeText(this, "صلاحية إحصائيات الاستخدام مفعّلة بالفعل ✅", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null && !nm.canUseFullScreenIntent()) {
                    try {
                        val fsIntent = Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(fsIntent)
                    } catch (e: Exception) {
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "صلاحية الظهور فوق التطبيقات مفعّلة بالفعل ✅", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantDeviceAdmin.setOnClickListener {
            requestCallScreeningRoleIfAvailable()
            val comp = SoulyDeviceAdminReceiver.getComponentName(this)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(com.souly.parental.shield.R.string.device_admin_description))
            }
            startActivity(intent)
        }

        binding.btnGrantVpnDns.setOnClickListener {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermLauncher.launch(vpnIntent)
            } else {
                SoulyFamilyDnsVpnService.startFilterIfPermitted(this)
                requestMediaProjectionIfNeeded()
            }
        }
    }

    private fun requestMediaProjectionIfNeeded() {
        if (!MediaProjectionScreenCapturer.hasProjectionGrant()) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            } catch (e: Exception) {
            }
        } else {
            Toast.makeText(this, "درع الـ DNS ولقطات الشاشة الفورية نشطان بالفعل ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestCallScreeningRoleIfAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rm = getSystemService(RoleManager::class.java) ?: return
                if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    callScreeningRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun navigateToChildDashboard() {
        startActivity(Intent(this, ChildDashboardActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
