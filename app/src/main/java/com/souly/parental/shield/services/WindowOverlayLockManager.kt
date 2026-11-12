package com.souly.parental.shield.services

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object WindowOverlayLockManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: LinearLayout? = null
    private var messageTextView: TextView? = null

    fun canDrawOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context.applicationContext)
        } else {
            true
        }
    }

    fun showOverlayLock(context: Context, message: String, isAppBlockOnly: Boolean) {
        val appCtx = context.applicationContext
        if (!canDrawOverlay(appCtx)) return

        mainHandler.post {
            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                if (overlayView != null) {
                    messageTextView?.text = message
                    return@post
                }

                val container = LinearLayout(appCtx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#F2041512"))
                    setPadding(64, 64, 64, 64)
                }

                val titleTv = TextView(appCtx).apply {
                    text = if (isAppBlockOnly) "🛡️ هذا التطبيق مقيد حالياً" else "⏸️ وضع التوقف العائلي المؤقت"
                    setTextColor(Color.parseColor("#00F5D4"))
                    textSize = 22f
                    gravity = Gravity.CENTER
                }

                val msgTv = TextView(appCtx).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, 36, 0, 48)
                }
                messageTextView = msgTv

                val actionBtn = Button(appCtx).apply {
                    text = if (isAppBlockOnly) "🏠 العودة إلى الشاشة الرئيسية للهاتف" else "🔕 إيقاف نغمة التنبيه الصوتية"
                    setBackgroundColor(Color.parseColor("#10B981"))
                    setTextColor(Color.parseColor("#041512"))
                    setOnClickListener {
                        SirenAndPauseController.stopEmergencyLocatorSiren()
                        if (isAppBlockOnly) {
                            dismissOverlayLock(appCtx)
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            appCtx.startActivity(homeIntent)
                        }
                    }
                }

                container.addView(titleTv)
                container.addView(msgTv)
                container.addView(actionBtn)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                wm.addView(container, params)
                overlayView = container
            } catch (e: Exception) {
            }
        }
    }

    fun dismissOverlayLock(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                overlayView?.let { view ->
                    wm?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
            } finally {
                overlayView = null
                messageTextView = null
            }
        }
    }
}
