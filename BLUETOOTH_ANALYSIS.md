# Анализ Bluetooth возможностей для байк-шэринга

## Сравнение: PWA Web Bluetooth vs Flutter Bluetooth

### PWA с Web Bluetooth API - Ограничения

#### ❌ Критические ограничения:

1. **iOS поддержка отсутствует**
   - Web Bluetooth API не поддерживается в Safari на iOS
   - Это исключает ~50% пользователей мобильных устройств

2. **Ограниченная поддержка браузеров**
   - Работает только в Chrome на Android 6+, Mac OS X, Linux, Chrome OS
   - Не работает в Firefox, Safari (iOS), Edge (старые версии)

3. **Только BLE (Bluetooth Low Energy)**
   - Не поддерживает классический Bluetooth
   - Ограничения в протоколах

4. **Требования безопасности**
   - Обязательный HTTPS
   - Пользователь должен вручную выбирать устройство из списка
   - Нет автоматического переподключения

5. **Ограничения фонового режима**
   - Service Worker имеет ограничения
   - Сложно поддерживать постоянное соединение

### ✅ Flutter с flutter_blue_plus - Преимущества

#### Полная поддержка:

1. **Кроссплатформенность**
   - ✅ iOS (полная поддержка)
   - ✅ Android (полная поддержка)
   - ✅ Web (через PWA, но с ограничениями Web Bluetooth)
   - ✅ Desktop (Windows, macOS, Linux)

2. **Полный доступ к Bluetooth**
   - ✅ BLE (Bluetooth Low Energy)
   - ✅ Классический Bluetooth (на платформах где доступен)
   - ✅ Все профили и протоколы

3. **Расширенные возможности**
   - ✅ Автоматическое переподключение
   - ✅ Фоновые операции
   - ✅ Уведомления и подписки
   - ✅ Batch операции
   - ✅ Bonding/Pairing

4. **Надежность**
   - ✅ Нативный доступ к Bluetooth стеку
   - ✅ Лучшая обработка ошибок
   - ✅ Поддержка всех характеристик BLE

## Архитектура блокировщика

### Базовый модуль (ESP32/RPi)

```
┌─────────────────────────────────┐
│      Контроллер (ESP32)         │
│  ┌───────────────────────────┐  │
│  │  BLE GATT Server          │  │
│  │  - Service UUID           │  │
│  │  - Lock Characteristic    │  │
│  │  - Status Characteristic  │  │
│  │  - Battery Characteristic │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │  GPS/GSM Module           │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │  Lock Actuator            │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

### Протокол взаимодействия

#### BLE Service Structure:

```
Service UUID: 0000ff00-0000-1000-8000-00805f9b34fb

Characteristics:
├─ Lock Control (WRITE)
│  └─ Commands: 0x01 (lock), 0x02 (unlock), 0x03 (status)
│
├─ Lock Status (READ/NOTIFY)
│  └─ Status: 0x00 (locked), 0x01 (unlocked), 0x02 (error)
│
├─ Battery Level (READ/NOTIFY)
│  └─ Value: 0-100 (percentage)
│
└─ Device Info (READ)
   └─ Firmware version, device ID, etc.
```

## Реализация в Flutter

### Пример использования flutter_blue_plus:

```clojure
;; Подключение к устройству
(defn connect-to-lock [device-id]
  (let [device (find-device device-id)
        service-uuid "0000ff00-0000-1000-8000-00805f9b34fb"
        lock-char-uuid "0000ff01-0000-1000-8000-00805f9b34fb"]
    (-> device
        (connect)
        (discover-services)
        (get-service service-uuid)
        (get-characteristic lock-char-uuid)
        (write [0x02]) ; unlock command
        (listen-status)))) ; subscribe to status updates
```

## Выводы

### Для MVP рекомендуется:

1. **Использовать Flutter** вместо чистого PWA
   - Flutter Web может работать как PWA
   - Но с полным доступом к Bluetooth через нативные плагины

2. **Гибридный подход:**
   - **Web версия (PWA)**: для быстрого доступа через QR код
   - **Нативное приложение**: для полной функциональности Bluetooth
   - **Единая кодовая база**: благодаря Flutter

3. **План развертывания:**
   - Начать с PWA для веб-версии (без Bluetooth, только QR → API)
   - Предложить установку нативного приложения для полной функциональности
   - Постепенно добавлять Web Bluetooth для Chrome пользователей

### Рекомендация для MVP:

**Этап 1 (Текущий MVP):**
- QR сканер → открывает веб-версию
- Предложение установить PWA
- Базовое взаимодействие через API (сервер управляет блокировкой)

**Этап 2 (Расширенный MVP):**
- Нативное приложение с полным Bluetooth
- Прямое управление блокировщиком
- Офлайн режим

**Этап 3 (Полная версия):**
- Web Bluetooth для Chrome пользователей
- Универсальное решение для всех платформ

