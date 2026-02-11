#!/bin/bash
# Скрипт для запуска интерактивного дерева проекта

# Переходим в корень проекта (на 2 уровня выше)
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT"

echo "🚀 Запуск Interactive Project Tree..."
echo "📁 Корень проекта: $PROJECT_ROOT"
echo ""

# Проверяем Python
if command -v python3 &> /dev/null; then
    echo "✅ Используем Python HTTP Server"
    echo "🌐 Откройте: http://localhost:8000/dev-tools/interactive/index.html"
    echo "📝 Нажмите Ctrl+C для остановки"
    echo ""
    python3 -m http.server 8000
elif command -v python &> /dev/null; then
    echo "✅ Используем Python HTTP Server"
    echo "🌐 Откройте: http://localhost:8000/dev-tools/interactive/index.html"
    echo "📝 Нажмите Ctrl+C для остановки"
    echo ""
    python -m SimpleHTTPServer 8000
else
    echo "❌ Python не найден"
    echo "💡 Установите Python или используйте: npx serve ."
    exit 1
fi

