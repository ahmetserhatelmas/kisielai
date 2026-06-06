# Dilara masaüstü uygulamasını kurar (Windows PowerShell)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-Not (Test-Path "desktop\.venv")) {
    Write-Host "[Dilara] Sanal ortam oluşturuluyor..."
    python -m venv desktop\.venv
}

& "desktop\.venv\Scripts\Activate.ps1"

Write-Host "[Dilara] pip yükseltiliyor..."
python -m pip install --upgrade pip

Write-Host "[Dilara] Bağımlılıklar yükleniyor..."
pip install -r desktop\requirements.txt

if (-Not (Test-Path "desktop\.env")) {
    Write-Host "[Dilara] .env oluşturuluyor..."
    Copy-Item desktop\.env.example desktop\.env
}

Write-Host ""
Write-Host "[Dilara] Kurulum tamam."
Write-Host "Çalıştırmak için:"
Write-Host "  desktop\.venv\Scripts\Activate.ps1"
Write-Host "  cd desktop"
Write-Host "  python -m dilara"
