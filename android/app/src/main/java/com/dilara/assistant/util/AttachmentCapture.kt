package com.dilara.assistant.util

/**
 * Activity tarafından kaydedilen galeri / dosya köprüsü.
 * captureImage / captureVideo : Vision API'ye gönderilecek sonucu döner.
 * readFile : dosya metnini döner; LLM'e düz mesaj olarak gönderilir.
 */
object AttachmentCapture {
    /** Galeriden resim seçer, Vision API sonucu döner. */
    var captureImage: (suspend (prompt: String) -> Result<String>)? = null

    /** Galeriden video seçer, ilk karesi Vision API'ye gönderilir. */
    var captureVideo: (suspend (prompt: String) -> Result<String>)? = null

    /** Dosya seçer, metin içeriğini döner. */
    var readFile: (suspend () -> Result<String>)? = null
}
