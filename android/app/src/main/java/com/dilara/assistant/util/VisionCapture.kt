package com.dilara.assistant.util

/**
 * Activity tarafından kayıt edilen kamera / ekran yakalama köprüsü.
 */
object VisionCapture {
    var captureCamera: (suspend (prompt: String) -> Result<String>)? = null
    /** MainActivity ekran izni + yakalama akışını başlatır. */
    var launchScreenCapture: (() -> Unit)? = null
}
