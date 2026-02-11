#!/bin/bash

# Автоматический запуск Flutter app на Android эмуляторе

echo "📱 Bikes Flutter App - Android Launcher"
echo "========================================"
echo ""

# Цвета
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Переменные окружения
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=~/Library/Android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Проверка SDK
echo -e "${YELLOW}Шаг 1/5: Проверка Android SDK...${NC}"
if [ ! -d "$ANDROID_HOME" ]; then
    echo -e "${RED}✗ Android SDK не найден!${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Android SDK найден${NC}"
echo ""

# Проверка существующих эмуляторов
echo -e "${YELLOW}Шаг 2/5: Проверка Android эмуляторов...${NC}"
AVD_NAME="Bikes_Pixel_7"

if $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager list avd | grep -q "$AVD_NAME"; then
    echo -e "${GREEN}✓ Эмулятор '$AVD_NAME' уже существует${NC}"
else
    echo -e "${YELLOW}Создание нового эмулятора '$AVD_NAME'...${NC}"

    # Создание AVD
    echo "no" | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
        -n "$AVD_NAME" \
        -k "system-images;android-34;google_apis;arm64-v8a" \
        -d "pixel_7" \
        --force

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Эмулятор создан успешно${NC}"
    else
        echo -e "${RED}✗ Ошибка при создании эмулятора${NC}"
        exit 1
    fi
fi
echo ""

# Запуск эмулятора
echo -e "${YELLOW}Шаг 3/5: Запуск Android эмулятора...${NC}"

# Проверка, запущен ли уже эмулятор
if $ANDROID_HOME/platform-tools/adb devices | grep -q "emulator"; then
    echo -e "${GREEN}✓ Эмулятор уже запущен${NC}"
else
    echo "Запуск эмулятора (это может занять 30-60 секунд)..."

    # Запуск эмулятора в фоне
    $ANDROID_HOME/emulator/emulator -avd "$AVD_NAME" -no-snapshot-save > /tmp/emulator.log 2>&1 &
    EMULATOR_PID=$!

    echo "PID эмулятора: $EMULATOR_PID"
    echo "Ожидание загрузки эмулятора..."

    # Ждем пока эмулятор запустится
    timeout=60
    counter=0
    while [ $counter -lt $timeout ]; do
        if $ANDROID_HOME/platform-tools/adb devices | grep -q "emulator.*device"; then
            echo -e "${GREEN}✓ Эмулятор запущен и готов${NC}"
            break
        fi

        sleep 2
        counter=$((counter + 2))
        echo -n "."
    done
    echo ""

    if [ $counter -ge $timeout ]; then
        echo -e "${RED}✗ Timeout: эмулятор не запустился за $timeout секунд${NC}"
        echo "Проверьте логи: tail -f /tmp/emulator.log"
        exit 1
    fi
fi
echo ""

# Ожидание полной загрузки
echo -e "${YELLOW}Шаг 4/5: Ожидание загрузки системы...${NC}"
$ANDROID_HOME/platform-tools/adb wait-for-device
sleep 10  # Дополнительное время для полной загрузки UI
echo -e "${GREEN}✓ Система загружена${NC}"
echo ""

# Запуск Flutter app
echo -e "${YELLOW}Шаг 5/5: Запуск Flutter приложения...${NC}"
cd /Users/denisovchar/bikes

# Проверка подключенных устройств
echo "Доступные устройства:"
flutter devices

echo ""
echo "Запуск приложения на эмуляторе..."
flutter run -d emulator-5554 2>&1 | tee /tmp/flutter-android.log

echo ""
echo -e "${GREEN}✓ Готово!${NC}"
echo ""
echo "Полезные команды:"
echo "  - Логи эмулятора: tail -f /tmp/emulator.log"
echo "  - Логи Flutter: tail -f /tmp/flutter-android.log"
echo "  - ADB devices: adb devices"
echo "  - Остановить эмулятор: adb -s emulator-5554 emu kill"
