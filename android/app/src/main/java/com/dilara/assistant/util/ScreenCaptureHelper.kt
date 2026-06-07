package com.dilara.assistant.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ScreenCaptureHelper {

    /** Aktif Activity penceresini yakalar (hızlı, ek izin gerektirmez). */
    suspend fun captureActivityWindow(activity: Activity): Bitmap =
        suspendCancellableCoroutine { cont ->
            val view = activity.window.decorView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bitmap)
                    else cont.resumeWithException(Exception("Ekran yakalanamadı (kod: $result)."))
                },
                Handler(Looper.getMainLooper()),
            )
        }

    /** MediaProjection ile tüm ekranı yakalar. */
    suspend fun captureWithProjection(
        projection: MediaProjection,
        metrics: DisplayMetrics,
    ): Bitmap = suspendCancellableCoroutine { cont ->
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cont.resume(cropped)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            } finally {
                image.close()
                virtualDisplay?.release()
                reader.close()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection.createVirtualDisplay(
            "DilaraScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }
}
