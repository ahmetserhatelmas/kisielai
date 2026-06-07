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
}
