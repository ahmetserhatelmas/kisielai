#!/usr/bin/env bash
# Dilara masaüstü kurulum scripti (macOS + Linux)
# Mac'te brew bağımlılıklarını ve mikrofon/ekran izin uyarılarını otomatik kontrol eder.

set -e

cd "$(dirname "$0")/.."

OS="$(uname -s)"

color() { printf "\033[1;36m[Dilara]\033[0m %s\n" "$*"; }
warn()  { printf "\033[1;33m[Dilara]\033[0m %s\n" "$*"; }
err()   { printf "\033[1;31m[Dilara]\033[0m %s\n" "$*" >&2; }

# ============================================================
# 1) macOS — Homebrew bağımlılıkları
# ============================================================
if [ "$OS" = "Darwin" ]; then
    color "macOS algılandı."

    if ! command -v brew >/dev/null 2>&1; then
        err "Homebrew yüklü değil."
        echo "Önce Homebrew'i kur:"
        echo '  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
        exit 1
    fi

    need_install=()
    for pkg in portaudio ffmpeg; do
        if brew list --formula "$pkg" >/dev/null 2>&1; then
            color "✓ $pkg zaten yüklü."
        else
            need_install+=("$pkg")
        fi
    done

    if [ ${#need_install[@]} -gt 0 ]; then
        color "Eksik brew paketleri kuruluyor: ${need_install[*]}"
        brew install "${need_install[@]}"
    fi
fi

# ============================================================
# 2) Linux — sistem paket uyarısı
# ============================================================
if [ "$OS" = "Linux" ]; then
    color "Linux algılandı."
    if ! ldconfig -p 2>/dev/null | grep -q libportaudio; then
        warn "PortAudio yüklü görünmüyor."
        warn "Debian/Ubuntu için:  sudo apt install -y portaudio19-dev ffmpeg"
        warn "Fedora için:         sudo dnf install -y portaudio-devel ffmpeg"
    fi
fi

# ============================================================
# 3) Python kontrolü
# ============================================================
if ! command -v python3 >/dev/null 2>&1; then
    err "python3 bulunamadı. Python 3.11+ kur."
    exit 1
fi

PY_VER=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
color "Python sürümü: $PY_VER"

# ============================================================
# 4) Sanal ortam
# ============================================================
if [ ! -d "desktop/.venv" ]; then
    color "Sanal ortam oluşturuluyor..."
    python3 -m venv desktop/.venv
else
    color "✓ Sanal ortam zaten var."
fi

# shellcheck disable=SC1091
source desktop/.venv/bin/activate

color "pip yükseltiliyor..."
pip install --upgrade pip --quiet

color "Bağımlılıklar yükleniyor (ilk seferde 5-10 dk sürebilir)..."
pip install -r desktop/requirements.txt

# ============================================================
# 5) .env şablonu
# ============================================================
if [ ! -f "desktop/.env" ]; then
    color ".env oluşturuluyor (anahtarları doldurmayı unutma)..."
    cp desktop/.env.example desktop/.env
else
    color "✓ desktop/.env zaten var."
fi

# ============================================================
# 6) Mac'e özel izin hatırlatması
# ============================================================
if [ "$OS" = "Darwin" ]; then
    echo ""
    color "Mac için izin notu:"
    echo "  İlk çalıştırmada macOS sana 2 izin soracak:"
    echo "    1. Mikrofon  — wake word ve sesli komut için"
    echo "    2. Ekran Kaydı — ekran analizi tool'u için (opsiyonel)"
    echo ""
    echo "  Eğer sormazsa: Sistem Ayarları → Gizlilik → Mikrofon / Ekran Kaydı"
    echo "  altından Terminal (veya Python'u barındıran uygulamayı) ekle."
fi

# ============================================================
# Bitti
# ============================================================
echo ""
color "Kurulum tamam."
echo ""
echo "Çalıştırmak için:"
echo "  source desktop/.venv/bin/activate"
echo "  cd desktop"
echo "  python -m dilara --probe   # sağlık kontrolü"
echo "  python -m dilara --cli     # terminal modu (test)"
echo "  python -m dilara           # GUI"
