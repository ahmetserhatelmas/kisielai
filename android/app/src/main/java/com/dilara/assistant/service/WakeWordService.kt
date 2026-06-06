package com.dilara.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.dilara.assistant.MainActivity

/**
 * Wake word arka plan servisi.
 *
 * SpeechRecognizer ile sürekli dinler; sonuçta "dilara" geçiyorsa
 * MainActivity'ye WAKE_DETECTED action'ı gönderir.
 * Her tanıma turunda kendini otomatik yeniden başlatır.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_WAKE_DETECTED = "com.dilara.assistant.WAKE_DETECTED"
        private const val CHANNEL_ID = "dilara_wake"
        private const val NOTIFICATION_ID = 1001
        private val WAKE_KEYWORDS = listOf("dilara", "selam dilara", "hey dilara")
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var shouldRun = false

    // ── Yaşam döngüsü ──────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shouldRun = true
        handler.post { startListening() }
        return START_STICKY
    }

    override fun onDestroy() {
        shouldRun = false
        stopListening()
        super.onDestroy()
    }

    // ── Dinleme döngüsü ────────────────────────────────────────────────────────

    private fun startListening() {
        if (!shouldRun || isListening) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(wakeListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        recognizer?.startListening(intent)
        isListening = true
    }

    private fun stopListening() {
        isListening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun restartAfterDelay(delayMs: Long = 1500) {
        isListening = false
        if (shouldRun) {
            handler.postDelayed({ startListening() }, delayMs)
        }
    }

    // ── RecognitionListener ───────────────────────────────────────────────────

    private val wakeListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return restartAfterDelay()
            val detected = matches.any { text ->
                WAKE_KEYWORDS.any { kw -> text.lowercase().contains(kw) }
            }
            if (detected) {
                sendWakeBroadcast()
                restartAfterDelay(3000) // Aktif konuşma için kısa duraklama
            } else {
                restartAfterDelay()
            }
        }

        override fun onError(error: Int) {
            // Zaman aşımı ve sessizlik hatalarında hemen yeniden başla
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_SERVER -> 2000L
                else -> 1000L
            }
            restartAfterDelay(delay)
        }
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    private fun sendWakeBroadcast() {
        val intent = Intent(ACTION_WAKE_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── Bildirim ──────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "Dilara Wake Word", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Selam Dilara dinleme servisi" }
            mgr.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dilara dinliyor")
            .setContentText("'Selam Dilara' diyerek konuşabilirsin.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }
}
