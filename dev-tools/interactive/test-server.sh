#!/bin/bash
cd "$(dirname "$0")"
echo "Запуск тестового сервера на порту 8002"
echo "Откройте: http://localhost:8002/test-load.html"
python3 -m http.server 8002
