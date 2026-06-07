package com.dilara.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Android 14+ zorunluluğu: MediaProjection başlatılmadan önce
 * foregroundServiceType="mediaProjection" olan bir servis çalışıyor olmalıdır.
 * Bu servis ekran analizi boyunca aktif kalır, analiz bitince durdurulur.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "dilara_screen_cap"
        const val NOTIF_ID = 1003
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Dilara Ekran Analizi",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ekran görüntüsü alınırken aktif" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dilara — Ekran analizi")
            .setContentText("Ekran görüntüsü alınıyor…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
}
