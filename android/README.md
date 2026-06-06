# Dilara — Android

Jetpack Compose tabanlı Android istemcisi.

## Mevcut durum (Faz 2 başlangıç iskeleti)

- ✅ Compose UI: aktivasyon butonu + sohbet ekranı + giriş alanı
- ✅ Manifest: tüm gerekli izinler (mikrofon, takvim, çağrı, vb.)
- ✅ Wake word foreground servisi iskeleti
- ✅ Accessibility servisi iskeleti (cihaz kontrolü için)
- ✅ Ktor + kotlinx.serialization bağımlılıkları
- ⏳ Backend bağlantısı (placeholder echo)
- ⏳ Wake word implementasyonu (Porcupine/openWakeWord ONNX)
- ⏳ STT (Android SpeechRecognizer + Whisper alternatifi)
- ⏳ TTS (Android TTS + opsiyonel Edge-TTS köprüsü)

## Açma

Android Studio Hedgehog (veya üstü) ile `android/` klasörünü aç ve
çalıştır.

## Mimari

```
ui/             # Compose UI
viewmodel/      # ChatViewModel
service/        # Wake word + accessibility servisleri
data/api/       # Backend istemcisi (Ktor)
```

## Güvenlik

- Tüm izinler runtime'da, yalnızca kullanıcı onayı sonrası açılır.
- Wake word servisi sadece master aktivasyon sonrası başlar.
- Banka uygulamaları için Accessibility servisi kara liste mantığıyla
  hareket eder (DilaraAccessibilityService.kt'de TODO).
