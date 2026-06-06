# Dilara — Mimari Dokümanı

## Genel Bakış

Dilara üç ana parçadan oluşur:

```
┌──────────────────────┐     ┌──────────────────────┐
│  Desktop (Python)    │     │  Android (Kotlin)    │
│  - PySide6 GUI       │     │  - Compose UI        │
│  - Wake / STT / TTS  │     │  - Wake servisi      │
│  - LLM orchestrator  │     │  - Accessibility     │
│  - Hafıza (lokal)    │     │  - Hafıza (lokal)    │
└──────────┬───────────┘     └──────────┬───────────┘
           │                            │
           └────────────┬───────────────┘
                        │ HTTPS + JWT
                ┌───────▼────────┐
                │  Backend       │
                │  (FastAPI)     │
                │  - Auth        │
                │  - Profil      │
                │  - Hafıza sync │
                │  - SQLite/PG   │
                └────────────────┘
```

## Katmanlar

### 1. Çekirdek (`core/`)
- **Settings** — `.env` ve env değişkenlerinden tipli ayar yönetimi.
- **PermissionManager** — Master aktivasyon + bireysel izinler. Hassas
  her servis çağırmadan önce `permissions.ensure(Permission.X)` yapar.
- **EventBus** — Modüller arası gevşek bağlı pub/sub.
- **Cipher** — AES (Fernet) tabanlı şeffaf şifreleme. Anahtar OS
  keyring'de saklanır.

### 2. Servisler (`services/`)
Her servis tek bir dış sisteme veya tek bir yeteneğe karşılık gelir.
Hepsi **provider abstraction** kullanır — sağlayıcı değiştirmek için
sadece factory'yi düzenle.

| Servis | Sağlayıcılar | Lokalize? |
|---|---|---|
| LLM | OpenAI, Anthropic, Gemini | Hayır (API) |
| TTS | Edge (free), ElevenLabs, OpenAI | Edge offline değil ama bedava |
| STT | faster-whisper (lokal), Whisper API | faster-whisper offline |
| Wake | openWakeWord (ONNX) | Tamamen lokal |
| Memory | SQLite + ChromaDB | Tamamen lokal |
| Vision | OpenAI gpt-4o-mini | API |
| Web | DuckDuckGo + trafilatura | Online |

### 3. Modüller (`modules/`)
- **Personality** — Mod (normal/serious), system prompt üretimi.
- **Tools** — LLM tool calling kayıt defteri. Her tool izin gates'i.
- **Assistant** — Ana orchestrator: mesaj → LLM → tools → TTS.

### 4. UI (`ui/`)
PySide6 ile koyu temalı, sade sohbet arayüzü.

## Akışlar

### Sesli sohbet akışı

```
Wake word ("Selam Dilara")
       │
       ▼
MicrophoneCapture.record_until_silence()
       │
       ▼
STT.transcribe()  → metin
       │
       ▼
Personality.detect_mode_command()
       │
       ▼
ShortTermMemory + Personality.system_prompt()
       │
       ▼
LLM.chat(messages, tools=...)
       │
       ├── tool_calls? → ToolRegistry.call() → result → tekrar LLM
       │
       ▼
text response
       │
       ├── TTS.synthesize() → play
       │
       └── (arka plan) MemoryExtractor.extract_from_turn()
```

### Yetki akışı

```
Uygulama açıldı → master_active = false
       │
       ▼
Kullanıcı "Yetki Ver" butonuna basar
       │
       ▼
PermissionManager.activate(grant_all=True)
   → INTERNET, MICROPHONE, MEMORY_WRITE, FILE_WRITE: granted
   → CAMERA, SCREEN, SYSTEM_CONTROL, SYNC: pending (kullanıcı manuel)
       │
       ▼
permission.changed event → wake word başlar, GUI günceller
```

## Güvenlik Modeli

1. **Lokal-first.** Tüm hafıza önce lokalde, AES ile şifreli.
2. **Banka kara listesi.** `BANK_BLACKLIST` içindeki herhangi bir
   isim geçen tool çağrısı `SecurityViolation` fırlatır.
3. **Açık-kapı varsayılanı yok.** Her hassas işlem `ensure(Permission)`
   ile gates'lenir.
4. **API anahtarları** sadece `.env`'de — git'e gitmez.
5. **Backend kompromise olsa** kullanıcının verisi şifreli olduğu için
   sunucu okuyamaz (zero-knowledge'a yakın).

## MVP'den Sonrası

### Faz 2 (Android tam)
- Porcupine ile özel "Hey Dilara" wake word
- Android STT (offline + Whisper hibrit)
- Backend ile tam senkronizasyon
- Cihaz kontrolü: arama, alarm, müzik, takvim
- Push notification → wake on phone

### Faz 3 (İleri özellikler)
- Görsel analiz (kamera + ekran)
- Öngörü modülü: kullanıcı rutinine göre tahmin
- Lokal LLM (llama.cpp ile mini server)
- Ev sunucusuna geçiş (N100 PC)
