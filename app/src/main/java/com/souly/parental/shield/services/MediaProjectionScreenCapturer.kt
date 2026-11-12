package com.souly.parental.shield.services

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import java.io.ByteArrayOutputStream

object MediaProjectionScreenCapturer {

    @Volatile
    private var resultCode: Int = 0

    @Volatile
    private var resultData: Intent? = null

    fun saveProjectionGrant(code: Int, data: Intent?) {
        resultCode = code
        resultData = data
    }

    fun hasProjectionGrant(): Boolean {
        return resultCode != 0 && resultData != null
    }

    fun captureRealScreenWebpOrNull(context: Context): ByteArray? {
        val data = resultData ?: return null
        if (resultCode == 0) return null

        var projection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null

        return try {
            val mpm = context.applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return null
            projection = mpm.getMediaProjection(resultCode, data) ?: return null

            val metrics = context.resources.displayMetrics
            val width = 720
            val height = 1280
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "SoulyScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )

            Thread.sleep(180)
            val image = imageReader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (cropped != bitmap) {
                bitmap.recycle()
            }

            val bos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            cropped.compress(format, 80, bos)
            cropped.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            try { virtualDisplay?.release() } catch (e: Exception) {}
            try { imageReader?.close() } catch (e: Exception) {}
            try { projection?.stop() } catch (e: Exception) {}
        }
    }
}
