#!/usr/bin/env bash
# Backend kurar
set -e

cd "$(dirname "$0")/.."

if [ ! -d "backend/.venv" ]; then
    python3 -m venv backend/.venv
fi

source backend/.venv/bin/activate
pip install --upgrade pip
pip install -r backend/requirements.txt

if [ ! -f "backend/.env" ]; then
    cp backend/.env.example backend/.env
fi

echo "[Dilara] Backend kuruldu."
echo "Çalıştırmak için:"
echo "  source backend/.venv/bin/activate"
echo "  cd backend"
echo "  uvicorn app.main:app --reload"
