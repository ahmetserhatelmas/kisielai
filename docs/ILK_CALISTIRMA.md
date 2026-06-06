# İlk Çalıştırma Kılavuzu

Sıfırdan Dilara'yı çalıştırmak için adım adım rehber.

## 1. Ön gereksinimler

- **Python 3.11+** (3.12 önerilen)
- **Mikrofon ve hoparlör** (sesli kullanım için)
- En az bir LLM API anahtarı: OpenAI / Anthropic / Gemini

## 2. Kurulum

### macOS / Linux

```bash
cd kisiselai
./scripts/setup_desktop.sh
```

### Windows

```powershell
cd kisiselai
.\scripts\setup_desktop.ps1
```

## 3. .env'i doldur

`desktop/.env` dosyasını aç ve **en az** şunları doldur:

```ini
DILARA_USER_NAME=İbo
OPENAI_API_KEY=sk-...
DILARA_LLM_PROVIDER=openai
DILARA_LLM_MODEL=gpt-4o
DILARA_TTS_PROVIDER=edge       # Bedava — Türkçe çok iyi
DILARA_STT_PROVIDER=faster-whisper
DILARA_STT_MODEL=base          # küçük makine için "tiny" da çalışır
```

Daha iyi ses kalitesi için ElevenLabs:

```ini
DILARA_TTS_PROVIDER=elevenlabs
ELEVENLABS_API_KEY=...
ELEVENLABS_VOICE_ID=...   # Türkçe konuşan bir ses ID
```

## 4. Sağlık kontrolü

```bash
source desktop/.venv/bin/activate
cd desktop
python -m dilara --probe
```

Çıktı şuna benzer olmalı:

```
Probe başarılı — tüm modüller import edildi.
  LLM: fallback (gpt-4o)
  TTS: edge / tr-TR-EmelNeural
  STT: faster-whisper / base
  Wake word: hey_dilara (açık)
  Hafıza DB: .../desktop/dilara/data/memory/dilara.db
```

## 5. Çalıştır

### GUI modu (önerilen)

```bash
python -m dilara
```

Pencere açılır. Sağ üstteki **"Yetki Ver / Aktif Et"** butonuna bas.

### CLI modu (test için)

```bash
python -m dilara --cli
```

Terminale yaz, Dilara cevap versin.

## 6. İlk konuşma

1. GUI'de "Yetki Ver"e bas.
2. Mikrofon yetkisi açıkken "Selam Dilara" de.
3. Wake word algılanınca durum çubuğunda "Dinliyorum..." görünür.
4. Konuşmanı bitir; Dilara cevap verir.

## 7. Mod değiştirme

- "Ciddi moda geç" → kısa, profesyonel cevaplar
- "Normal moda geç" → samimi, sıcak

## 8. Hafıza

Konuştukça otomatik öğrenir. Manuel kayıt için:

- "Akşam 23:30'da yatıyorum, bunu hatırla."
- "Sabah 7'de spora gidiyorum, rutinim bu."

İçerik AES ile şifrelenip lokal SQLite'a yazılır.

## Sorun giderme

**`No module named 'sounddevice'`**
PortAudio yüklü değil. macOS: `brew install portaudio`. Linux:
`sudo apt install portaudio19-dev`.

**Mikrofon yok / sessiz**
`python -m sounddevice` ile cihaz listesini kontrol et.

**Wake word algılamıyor**
`DILARA_WAKE_THRESHOLD=0.3` (daha hassas) dene.

**Türkçe doğru gelmiyor**
`DILARA_STT_MODEL=small` veya `medium` kullan (RAM yeterse).
