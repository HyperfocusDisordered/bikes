#!/bin/bash
# Скрипт для сборки Flutter Web приложения для превью

set -e

echo "🔨 Building Flutter Web application..."

cd "$(dirname "$0")/../.."

# Проверяем наличие Flutter
if ! command -v flutter &> /dev/null; then
    echo "❌ Flutter не найден. Установите Flutter SDK: https://flutter.dev/docs/get-started/install"
    exit 1
fi

# Устанавливаем зависимости
echo "📦 Installing dependencies..."
flutter pub get

# Собираем Flutter Web приложение
echo "🏗️  Building Flutter Web..."
flutter build web --release --base-href "/flutter-app/"

echo "✅ Flutter Web приложение собрано в build/web/"
echo "💡 Теперь превью будет работать через /preview?component=home-screen"

