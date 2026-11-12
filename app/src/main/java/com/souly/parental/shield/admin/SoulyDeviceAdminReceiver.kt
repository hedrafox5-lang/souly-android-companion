package com.souly.parental.shield.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.widget.Toast

class SoulyDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SoulyDeviceAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        applyDeviceOwnerSafetyRestrictions(context)
        Toast.makeText(context, "تم تفعيل صلاحية حماية درع سولي العائلي بنجاح", Toast.LENGTH_SHORT).show()
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        applyDeviceOwnerSafetyRestrictions(context)
    }

    private fun applyDeviceOwnerSafetyRestrictions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val adminComp = getComponentName(context)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.addUserRestriction(adminComp, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(adminComp, UserManager.DISALLOW_SAFE_BOOT)
            } catch (e: Exception) {
            }
        }
    }
}
