#!/bin/bash
# Запуск сервера для Live Preview Cursor на порту 3000

cd "$(dirname "$0")"

echo "🚀 Запуск сервера для Cursor Live Preview..."
echo ""

# Проверяем Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js не установлен. Установите Node.js: https://nodejs.org/"
    exit 1
fi

# Запускаем сервер
node server.js

