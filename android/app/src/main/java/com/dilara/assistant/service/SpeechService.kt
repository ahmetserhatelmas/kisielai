package com.dilara.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android SpeechRecognizer ile Türkçe STT.
 *
 * Önemli: SpeechRecognizer ana (UI) thread'de çalışmalıdır.
 * Coroutine çağrısı Dispatchers.Main üzerinden yapılmalı.
 */
class SpeechService(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
    }

    /**
     * Mikrofonu açar, kullanıcı konuşmasını bekler, transkripti döner.
     * Hata durumunda null döner.
     * Ana (UI) thread'de çağrılmalıdır.
     */
    suspend fun listen(): String? = suspendCancellableCoroutine { cont ->
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (cont.isActive) cont.resume(matches?.firstOrNull())
            }

            override fun onError(error: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })

        recognizer?.startListening(buildIntent())

        cont.invokeOnCancellation {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    fun destroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
