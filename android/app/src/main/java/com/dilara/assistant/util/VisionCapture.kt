package com.dilara.assistant.util

/**
 * Activity tarafından kayıt edilen kamera / ekran yakalama köprüsü.
 * ToolExecutor ve ViewModel buradan suspend callback çağırır.
 */
object VisionCapture {
    var captureCamera: (suspend (prompt: String) -> Result<String>)? = null
    var captureScreen: (suspend (prompt: String) -> Result<String>)? = null
}
