# Dilara Backend

Cihazlar arası senkronizasyon için FastAPI backend.

## Kurulum

```bash
python -m venv .venv
source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --reload
```

API dokümantasyonu: http://localhost:8000/docs

## Endpoints

- `POST /auth/register` — yeni kullanıcı
- `POST /auth/login` — giriş
- `GET  /profile/me` — profil
- `PATCH /profile/me` — profil güncelle
- `GET  /memory/` — hafıza listesi
- `PUT  /memory/{id}` — hafıza ekle/güncelle
- `POST /sync/push` — toplu push
- `GET  /sync/pull` — toplu pull

## Güvenlik notu

Hafıza içeriği **client tarafında** AES ile şifrelenir, sunucu sadece
şifreli metni saklar. Backend kompromise olsa bile kullanıcının
verisi düz metin olarak okunamaz.
