package com.dilara.assistant.util

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object BitmapUtils {
    fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Uzun kenarı [maxDim] pikseli geçmeyecek şekilde küçültüp JPEG base64 döner.
     * Çoklu kare (ekran kaydı) gönderiminde payload'ı küçük tutmak için.
     */
    fun toBase64JpegScaled(bitmap: Bitmap, maxDim: Int = 900, quality: Int = 80): String {
        val w = bitmap.width
        val h = bitmap.height
        val longSide = maxOf(w, h)
        val scaled = if (longSide > maxDim) {
            val ratio = maxDim.toFloat() / longSide
            Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
        } else {
            bitmap
        }
        val result = toBase64Jpeg(scaled, quality)
        if (scaled !== bitmap) scaled.recycle()
        return result
    }
}
