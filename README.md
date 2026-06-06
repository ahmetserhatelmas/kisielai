# Dilara AI Assistant

> Dijital İleri Lojik Akıllı Reaktif Asistan

Kişisel, Türkçe konuşan, sesli, hafızalı, Android + Windows üzerinde çalışan yapay zekâ asistanı.

Bu Siri klonu değil. Tamamen senin için, senin verilerinle, senin izninle çalışan, sen kapatmadıkça internete bile bağlanmayan bir asistan.

## Hızlı başlangıç

```bash
# Masaüstü
./scripts/setup_desktop.sh
# desktop/.env dosyasına OPENAI_API_KEY ekle
cd desktop && python -m dilara --probe   # sağlık kontrolü
python -m dilara                         # GUI başlat
```

Detaylı kurulum: [`docs/ILK_CALISTIRMA.md`](docs/ILK_CALISTIRMA.md)

## Mimari

```
kisiselai/
├── desktop/        # Python masaüstü (PySide6 GUI)
│   └── dilara/
│       ├── core/         # Konfig, izin, olay, şifreleme
│       ├── services/     # STT/TTS/LLM/Wake/Memory/Vision/Web/...
│       ├── modules/      # Personality, Tools, Assistant
│       ├── ui/           # Compose-tarzı PySide6 UI
│       └── data/         # Lokal SQLite + ChromaDB
├── backend/        # FastAPI senkronizasyon backend
├── android/        # Kotlin + Jetpack Compose
├── docs/           # Mimari, kurulum, ilk çalıştırma
└── scripts/        # Kurulum scriptleri
```

Detaylı mimari: [`docs/MIMARI.md`](docs/MIMARI.md)

## Faz durumu

| Faz | Kapsam | Durum |
|---|---|---|
| **1** | Windows MVP: ses, LLM, hafıza, dosya, web | ✅ Çalışır iskelet |
| **2** | Android + senkronizasyon | 🚧 Backend hazır, Android iskelet |
| **3** | Görsel, öngörü, lokal LLM | 🚧 Görsel hazır, diğerleri iskelet |

## Güvenlik prensipleri

1. **Manuel aktivasyon zorunlu.** Uygulama kapalı modda başlar.
2. **Yetki verilmeden:** İnternet yok, sync yok, mikrofon yok, kamera yok, sistem kontrolü yok.
3. **Banka / finans hariç.** `BANK_BLACKLIST` içinde geçen herhangi bir komut `SecurityViolation` fırlatır.
4. **Lokal-first.** Tüm hafıza önce lokalde, AES (Fernet) ile şifreli. Anahtar OS keyring'de.
5. **Backend zero-knowledge'a yakın.** Hafıza şifreli olarak gönderilir, sunucu okuyamaz.
6. **Multi-provider LLM.** Tek bir AI sağlayıcısına bağımlı değiliz; biri çökerse otomatik fallback.

## Teknoloji seçimleri

| Katman | Seçim | Neden |
|---|---|---|
| LLM | OpenAI / Anthropic / Gemini (modüler) | En iyi Türkçe + fallback |
| TTS | Edge-TTS varsayılan, ElevenLabs opsiyonel | Edge bedava, doğal Türkçe |
| STT | faster-whisper (lokal) | Offline + Türkçe çok iyi |
| Wake word | openWakeWord (ONNX) | Lokal, düşük güç |
| Hafıza | SQLite + ChromaDB | Lokal, hızlı, vektör desteği |
| GUI | PySide6 | Cross-platform, modern |
| Backend | FastAPI + SQLAlchemy async | Hız + tip güvenliği |
| Android | Kotlin + Jetpack Compose | Native, modern |

## İlk komutlar denemek için

GUI'de "Yetki Ver"e bastıktan sonra:

- "Selam Dilara, nasılsın?"
- "Ciddi moda geç."
- "Yarın sabah 7'de spora gidiyorum, bunu hatırla."
- "Türkiye ekonomisi hakkında kısa bir Word raporu hazırla."
- "Bilgisayarımdaki sesi 30'a indir."
- "Ekrana bak ve bana ne gördüğünü anlat."

## Lisans

Kişisel kullanım. Dağıtım yok.
