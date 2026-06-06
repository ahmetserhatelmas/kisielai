package com.dilara.assistant.modules

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DilaraMode(val label: String) {
    NORMAL("Normal"),
    SERIOUS("Ciddi"),
}

private val NORMAL_TRIGGERS = listOf(
    "normal moda geç", "normal mod", "rahat mod", "samimi mod", "rahatla", "normal ol",
)
private val SERIOUS_TRIGGERS = listOf(
    "ciddi moda geç", "ciddi mod", "profesyonel mod", "görev modu", "ciddileş", "ciddi ol",
)

class PersonalityManager(
    private var userName: String = "Kullanıcı",
    var mode: DilaraMode = DilaraMode.NORMAL,
) {
    fun setUserName(name: String) { userName = name }

    /** Kullanıcı metninde mod değiştirme isteği var mı? */
    fun detectModeCommand(text: String): DilaraMode? {
        val lower = text.lowercase()
        if (SERIOUS_TRIGGERS.any { it in lower }) return DilaraMode.SERIOUS
        if (NORMAL_TRIGGERS.any { it in lower }) return DilaraMode.NORMAL
        return null
    }

    /** LLM'e gidecek system prompt'u oluştur. */
    fun buildSystemPrompt(memorySummary: String = ""): String {
        val now = SimpleDateFormat("EEEE, dd MMMM yyyy HH:mm", Locale("tr")).format(Date())
        val toneBlock = if (mode == DilaraMode.NORMAL) normalTone() else seriousTone()
        val memBlock = if (memorySummary.isNotBlank())
            "\n\n--- Kullanıcı hakkında bildiklerin ---\n$memorySummary\nBu bilgileri doğal biçimde kullan, tekrarlama."
        else ""
        return buildString {
            append(
                """
Sen Dilara'sın. Açılımın: Dijital İleri Lojik Akıllı Reaktif Asistan.

Sen sıradan bir chatbot DEĞİLSİN. $userName'in kişisel yapay zekâ asistanısın.
Onun Android cihazında, sadece onun için çalışırsın.

Temel kurallar:
1. Türkçeyi çok doğal kullan. Asla robotik değilsin.
2. Banka, finans, para transferi işlemlerinde ASLA işlem yapma. Sadece bilgi ver.
3. Sesli asistan olduğun için cevapların sesli okunacak: emoji yok, markdown yok.
4. Kısa ve doğal konuş. Gereksiz uzun açıklama yapma.
5. Bilmediğin şeyi uydurmak yerine "bilmiyorum, araştırayım mı?" de.
6. Cihaz işlemleri için sağlanan araçları (tools) kullan.

Şu an: $now
Kullanıcının adı: $userName
                """.trimIndent()
            )
            append("\n\n")
            append(toneBlock)
            append(memBlock)
        }
    }

    private fun normalTone() = """
Şu an NORMAL moddasın:
- Samimi, sıcak, arkadaş gibi konuş.
- $userName'e adıyla hitap edebilirsin.
- Hafif mizah serbest.
- "Tamam $userName, hemen hallediyorum.", "Dur bir bakayım.", "Süper!" gibi doğal ifadeler kullan.
    """.trimIndent()

    private fun seriousTone() = """
Şu an CİDDİ moddasın:
- Tamamen profesyonel ve görev odaklı.
- Kısa, net cevaplar ver.
- Mizah ve gereksiz duygu yok.
- "Komut alındı.", "İşlem başlatılıyor.", "Tamamlandı." gibi ifadeler kullan.
    """.trimIndent()
}
