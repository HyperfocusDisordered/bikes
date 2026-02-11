#!/bin/bash
# Тест загрузки всех файлов проекта

cd "$(dirname "$0")/../.."
python3 -m http.server 8004 > /tmp/test_files.log 2>&1 &
SERVER_PID=$!
sleep 2

echo "Тестирую загрузку файлов..."
echo ""

files=(
    "src/bikes/core.cljd"
    "src/bikes/app.cljd"
    "src/bikes/screens/home.cljd"
    "src/bikes/screens/qr_scanner.cljd"
    "src/bikes/screens/bike_rental.cljd"
    "src/bikes/components/pwa_install.cljd"
    "src/bikes/services/api.cljd"
    "src/bikes/services/bluetooth.cljd"
    "src/bikes/state/app_state.cljd"
    "src/bikes/utils/helpers.cljd"
)

for file in "${files[@]}"; do
    result=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8004/$file")
    if [ "$result" = "200" ]; then
        size=$(curl -s "http://localhost:8004/$file" | wc -c)
        echo "✅ $file ($size bytes)"
    else
        echo "❌ $file (HTTP $result)"
    fi
done

kill $SERVER_PID 2>/dev/null
echo ""
echo "Тест завершен"
