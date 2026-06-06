package com.dilara.assistant.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Bildirim ve ekran olaylarını izleyen Accessibility Service.
 *
 * Bildirimleri bellekte tutar; ToolExecutor.get_notifications() buradan okur.
 */
class DilaraAccessibilityService : AccessibilityService() {

    companion object {
        data class NotificationEntry(val app: String, val text: String, val timestampMs: Long)

        private val _notifications = ArrayDeque<NotificationEntry>(50)

        fun getNotifications(): List<NotificationEntry> = synchronized(_notifications) {
            _notifications.toList().takeLast(20)
        }

        private fun addNotification(entry: NotificationEntry) {
            synchronized(_notifications) {
                // Aynı içerik varsa güncelle
                _notifications.removeIf { it.app == entry.app && it.text == entry.text }
                _notifications.addLast(entry)
                if (_notifications.size > 50) _notifications.removeFirst()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType

        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text.joinToString(" ").trim()
            if (text.isBlank()) return
            val pkgName = event.packageName?.toString() ?: "?"
            val appLabel = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkgName, 0)
                ).toString()
            }.getOrDefault(pkgName)

            // Banka uygulamalarından gelen bildirimleri atla
            val blacklist = setOf("akbank", "garanti", "ziraat", "halkbank", "vakifbank", "isbank")
            if (blacklist.any { pkgName.lowercase().contains(it) }) return

            addNotification(NotificationEntry(appLabel, text, System.currentTimeMillis()))
        }
    }

    override fun onInterrupt() {}
}
