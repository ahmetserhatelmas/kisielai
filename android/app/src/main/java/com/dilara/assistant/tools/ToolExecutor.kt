package com.dilara.assistant.tools

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import com.dilara.assistant.data.api.FunctionDef
import com.dilara.assistant.data.api.OpenAITool
import com.dilara.assistant.service.DilaraAccessibilityService
import com.dilara.assistant.util.VisionCapture
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val BANK_BLACKLIST = setOf(
    "akbank", "garanti", "yapikredi", "yapı kredi", "isbank", "iş bankası",
    "ziraat", "halkbank", "vakifbank", "denizbank", "qnb", "papara",
    "binance", "btcturk", "paypal", "wise", "banka", "transfer", "havale", "eft",
)

class ToolExecutor(private val context: Context) {

    private fun checkBlacklist(text: String) {
        val lower = text.lowercase()
        if (BANK_BLACKLIST.any { it in lower }) {
            throw SecurityException("Güvenlik politikası: finans işlemleri kapsam dışıdır.")
        }
    }

    // ── Araç şemaları ──────────────────────────────────────────────────────────

    val tools: List<OpenAITool> = listOf(
        tool("current_time", "Şu anki tarih ve saati Türkçe formatında döner.", "{}"),
        tool("set_alarm", "Alarm kur.", """{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}"""),
        tool("add_calendar_event", "Takvime etkinlik ekle.", """{"type":"object","properties":{"title":{"type":"string"},"year":{"type":"integer"},"month":{"type":"integer"},"day":{"type":"integer"},"hour":{"type":"integer"},"minute":{"type":"integer"},"description":{"type":"string"}},"required":["title","year","month","day"]}"""),
        tool("send_sms", "SMS gönder. Banka işlemleri kapsam dışı.", """{"type":"object","properties":{"number":{"type":"string"},"message":{"type":"string"}},"required":["number","message"]}"""),
        tool("make_call", "Telefon ara. Banka işlemleri kapsam dışı.", """{"type":"object","properties":{"number":{"type":"string"}},"required":["number"]}"""),
        tool("set_volume", "Sistem ses seviyesini ayarla (0-15 arası).", """{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":15}},"required":["level"]}"""),
        tool("set_bluetooth", "Bluetooth aç veya kapat.", """{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}"""),
        tool("set_wifi", "Wi-Fi ayar panelini aç. Android 10+ kısıtı nedeniyle doğrudan açıp kapatamaz, kullanıcıyı yönlendirir.", """{"type":"object","properties":{}}"""),
        tool("open_app", "Bir uygulamayı paket adına göre aç.", """{"type":"object","properties":{"package_name":{"type":"string"}},"required":["package_name"]}"""),
        tool("play_music", "Müzik uygulamasında bir şarkı/sanatçı ara ve çal.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        tool("get_notifications", "Son bildirimleri oku.", """{"type":"object","properties":{}}"""),
        tool("remember", "Kullanıcı hakkında öğrenilen bir bilgiyi kaydet.", """{"type":"object","properties":{"text":{"type":"string"},"category":{"type":"string","enum":["rutin","tercih","kişi","not","olay","genel"]}},"required":["text"]}"""),
        tool("switch_mode", "Dilara'nın modunu değiştir.", """{"type":"object","properties":{"mode":{"type":"string","enum":["normal","ciddi"]}},"required":["mode"]}"""),
        tool("analyze_camera", "Kamerayı aç, fotoğraf çek ve görseli analiz et.", """{"type":"object","properties":{"prompt":{"type":"string","description":"Görsele sorulacak soru"}}}"""),
        tool("look_at_screen", "Telefon ekranının görüntüsünü al ve analiz et.", """{"type":"object","properties":{"prompt":{"type":"string","description":"Ekrana sorulacak soru"}}}"""),
    )

    // ── Araç yürütücü ─────────────────────────────────────────────────────────

    suspend fun execute(
        name: String,
        args: JsonObject,
        onRemember: suspend (String, String) -> Unit = { _, _ -> },
        onModeChange: (String) -> Unit = {},
    ): String = when (name) {

        "current_time" -> {
            val now = SimpleDateFormat("EEEE, dd MMMM yyyy HH:mm", Locale("tr")).format(Date())
            now
        }

        "set_alarm" -> {
            val hour = args["hour"]?.jsonPrimitive?.int ?: return@execute "Saat belirtilmedi."
            val minute = args["minute"]?.jsonPrimitive?.int ?: 0
            val label = args["label"]?.jsonPrimitive?.contentOrNull ?: "Dilara Alarmı"
            runCatching {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Alarm ${"%02d".format(hour)}:${"%02d".format(minute)} için kuruldu."
            }.getOrElse { "Alarm kurulamadı: ${it.message}" }
        }

        "add_calendar_event" -> {
            val title = args["title"]?.jsonPrimitive?.contentOrNull ?: return@execute "Başlık belirtilmedi."
            val year = args["year"]?.jsonPrimitive?.int ?: Calendar.getInstance().get(Calendar.YEAR)
            val month = args["month"]?.jsonPrimitive?.int ?: (Calendar.getInstance().get(Calendar.MONTH) + 1)
            val day = args["day"]?.jsonPrimitive?.int ?: Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            val hour = args["hour"]?.jsonPrimitive?.int ?: 9
            val minute = args["minute"]?.jsonPrimitive?.int ?: 0
            val description = args["description"]?.jsonPrimitive?.contentOrNull ?: ""
            runCatching {
                val cal = Calendar.getInstance().apply {
                    set(year, month - 1, day, hour, minute, 0)
                }
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3600_000)
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.Events.DESCRIPTION, description)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "$title etkinliği $day/$month/$year ${"%02d".format(hour)}:${"%02d".format(minute)} için takvime eklendi."
            }.getOrElse { "Etkinlik eklenemedi: ${it.message}" }
        }

        "send_sms" -> {
            val number = args["number"]?.jsonPrimitive?.contentOrNull ?: return@execute "Numara belirtilmedi."
            val message = args["message"]?.jsonPrimitive?.contentOrNull ?: return@execute "Mesaj boş."
            checkBlacklist(message)
            runCatching {
                @Suppress("DEPRECATION")
                val sms = SmsManager.getDefault()
                val parts = sms.divideMessage(message)
                sms.sendMultipartTextMessage(number, null, parts, null, null)
                "$number numarasına SMS gönderildi."
            }.getOrElse { "SMS gönderilemedi: ${it.message}" }
        }

        "make_call" -> {
            val number = args["number"]?.jsonPrimitive?.contentOrNull ?: return@execute "Numara belirtilmedi."
            checkBlacklist(number)
            runCatching {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$number")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "$number numarası aranıyor."
            }.getOrElse { "Arama başlatılamadı: ${it.message}" }
        }

        "set_volume" -> {
            val level = args["level"]?.jsonPrimitive?.int ?: 8
            runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, 15), AudioManager.FLAG_SHOW_UI)
                "Ses seviyesi $level olarak ayarlandı."
            }.getOrElse { "Ses ayarlanamadı: ${it.message}" }
        }

        "set_wifi" -> {
            runCatching {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                }
                context.startActivity(intent)
                "Wi-Fi ayarları açıldı. Oradan açıp kapatabilirsin."
            }.getOrElse { "Wi-Fi ayarları açılamadı: ${it.message}" }
        }

        "set_bluetooth" -> {
            val enabled = args["enabled"]?.jsonPrimitive?.boolean ?: false
            runCatching {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = btManager?.adapter
                if (adapter == null) return@execute "Bluetooth desteklenmiyor."
                if (enabled) {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Bluetooth açılması istendi."
                } else {
                    @Suppress("DEPRECATION")
                    adapter.disable()
                    "Bluetooth kapatıldı."
                }
            }.getOrElse { "Bluetooth değiştirilemedi: ${it.message}" }
        }

        "open_app" -> {
            val pkg = args["package_name"]?.jsonPrimitive?.contentOrNull ?: return@execute "Paket adı belirtilmedi."
            checkBlacklist(pkg)
            runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return@execute "$pkg uygulaması bulunamadı."
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "$pkg uygulaması açıldı."
            }.getOrElse { "Uygulama açılamadı: ${it.message}" }
        }

        "play_music" -> {
            val query = args["query"]?.jsonPrimitive?.contentOrNull ?: ""
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://open.spotify.com/search/$query")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Spotify yoksa YouTube Music'e dene
                val spotifyIntent = context.packageManager
                    .getLaunchIntentForPackage("com.spotify.music")
                if (spotifyIntent != null) {
                    val searchIntent = Intent("com.spotify.music.playbackservice.PLAY").apply {
                        `package` = "com.spotify.music"
                        putExtra("query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(searchIntent)
                    } catch (e: Exception) {
                        context.startActivity(intent)
                    }
                } else {
                    context.startActivity(intent)
                }
                "\"$query\" için müzik aranıyor."
            }.getOrElse { "Müzik açılamadı: ${it.message}" }
        }

        "get_notifications" -> {
            val notifications = DilaraAccessibilityService.getNotifications()
            if (notifications.isEmpty()) "Okunmamış bildirim yok."
            else "Son bildirimler:\n" + notifications.joinToString("\n") { "- ${it.app}: ${it.text}" }
        }

        "remember" -> {
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return@execute "Bilgi boş."
            val category = args["category"]?.jsonPrimitive?.contentOrNull ?: "genel"
            onRemember(text, category)
            "\"$text\" bilgisi kaydedildi."
        }

        "switch_mode" -> {
            val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "normal"
            onModeChange(mode)
            if (mode == "ciddi") "Ciddi moda geçtim." else "Normal moda geçtim."
        }

        "analyze_camera" -> {
            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull
                ?: "Bu fotoğrafta ne var? Türkçe ve kısa anlat."
            val capture = VisionCapture.captureCamera
                ?: return@execute "Kamera şu an kullanılamıyor."
            capture(prompt).getOrElse { return@execute "Kamera hatası: ${it.message}" }
        }

        "look_at_screen" -> {
            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull
                ?: "Telefon ekranında ne görüyorsun? Türkçe ve kısa anlat."
            val capture = VisionCapture.captureScreen
                ?: return@execute "Ekran analizi şu an kullanılamıyor."
            capture(prompt).getOrElse { return@execute "Ekran hatası: ${it.message}" }
        }

        else -> "Bilinmeyen araç: $name"
    }

    // ── Yardımcı ─────────────────────────────────────────────────────────────

    private fun tool(name: String, description: String, parametersJson: String): OpenAITool {
        val params = Json.parseToJsonElement(parametersJson).jsonObject
        return OpenAITool(function = FunctionDef(name = name, description = description, parameters = params))
    }
}
