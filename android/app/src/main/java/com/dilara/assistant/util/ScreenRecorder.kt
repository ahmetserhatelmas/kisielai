package com.dilara.assistant.util

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics

/**
 * Ekran kaydı oturumu: MediaProjection ile VirtualDisplay'i açık tutar,
 * belirli aralıklarla (ör. 1.5 sn) ekrandan kare yakalar.
 *
 * Anlık fotoğraftan farkı: "başla" → süre boyunca kareler → "bitir" → kareleri döndür.
 * Toplanan kareler Vision API'ye "video" gibi gönderilip yorumlanır.
 */
object ScreenRecorder {

    private const val FRAME_INTERVAL_MS = 1500L
    private const val MAX_FRAMES = 8 // Vision API maliyeti için sınırlı tut

    @Volatile
    var isRecording: Boolean = false
        private set

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handler: Handler? = null
    private var captureRunnable: Runnable? = null

    private var width = 0
    private var height = 0

    private val frames = ArrayList<Bitmap>()

    /** Kayıt oturumunu başlatır. */
    fun start(projection: MediaProjection, metrics: DisplayMetrics) {
        if (isRecording) return
        clearFrames()

        width = metrics.widthPixels
        height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader

        virtualDisplay = projection.createVirtualDisplay(
            "DilaraScreenRecord",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null,
        )

        val h = Handler(Looper.getMainLooper())
        handler = h
        isRecording = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isRecording) return
                grabFrame()
                h.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
        captureRunnable = runnable
        // İlk kareyi biraz gecikmeyle al (VirtualDisplay hazırlansın)
        h.postDelayed(runnable, 600)
    }

    private fun grabFrame() {
        val imageReader = reader ?: return
        val image = imageReader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
            bmp.recycle()

            synchronized(frames) {
                if (frames.size >= MAX_FRAMES) {
                    // En eskiyi at, en yenileri tut (ring buffer)
                    frames.removeAt(0).recycle()
                }
                frames.add(cropped)
            }
        } catch (_: Exception) {
            // tek kare kaçsa sorun değil
        } finally {
            image.close()
        }
    }

    /** Kaydı durdurur ve toplanan kareleri döndürür. Sahiplik çağırana geçer. */
    fun stop(): List<Bitmap> {
        isRecording = false
        captureRunnable?.let { handler?.removeCallbacks(it) }
        captureRunnable = null
        handler = null
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        return synchronized(frames) {
            val out = ArrayList(frames)
            frames.clear() // recycle etme — sahiplik çağırana geçti
            out
        }
    }

    private fun clearFrames() {
        synchronized(frames) {
            frames.forEach { it.recycle() }
            frames.clear()
        }
    }

    /** Her şeyi serbest bırak (kareler dahil). */
    fun release() {
        stop()
        clearFrames()
    }
}
