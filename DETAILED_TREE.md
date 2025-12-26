# Детальное дерево проекта Bikes

> 💡 **Все ссылки кликабельны** - нажмите на них для перехода к коду в IDE

## 📊 Архитектура приложения

```
┌─────────────────────────────────────────────────────────────┐
│  [bikes.core/main](src/bikes/core.cljd#L6)                  │
│  (точка входа)                                               │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  [bikes.app/app](src/bikes/app.cljd#L9)                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ MaterialApp                                          │   │
│  │  ├─ Theme: Blue, Material3                          │   │
│  │  ├─ Routes:                                          │   │
│  │  │   ├─ "/" → [home-screen](src/bikes/screens/home.cljd#L7) │
│  │  │   ├─ "/qr-scanner" → [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8) │
│  │  │   └─ "/rental" → [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) │
│  │  └─ Home: [home-screen](src/bikes/screens/home.cljd#L7) │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🏠 ЭКРАН: [home-screen](src/bikes/screens/home.cljd#L7) (bikes.screens.home)

**Файл:** [`src/bikes/screens/home.cljd`](src/bikes/screens/home.cljd)

### Структура компонентов:

```
home-screen
│
├─ Scaffold
│  ├─ AppBar
│  │  └─ Title: "Bikes" (blue background)
│  │
│  └─ Body (Padding: 16)
│     └─ Column (spacing: 24)
│        │
│        ├─ 📦 Welcome Card
│        │  └─ Card (elevation: 2)
│        │     └─ Padding (20)
│        │        └─ Column (spacing: 12)
│        │           ├─ Text: "Welcome to Bikes!" (24px, bold)
│        │           └─ Text: "Scan QR code to start your ride" (14px, grey)
│        │
│        ├─ 📋 Quick Actions Section
│        │  └─ Column (spacing: 12)
│        │     ├─ Text: "Quick Actions" (18px, bold)
│        │     │
│        │     ├─ 📦 Scan QR Code Card
│        │     │  └─ Card
│        │     │     └─ ListTile
│        │     │        ├─ Leading: QR icon (32px, blue)
│        │     │        ├─ Title: "Scan QR Code"
│        │     │        ├─ Subtitle: "Start a new rental"
│        │     │        ├─ Trailing: ChevronRight icon
│        │     │        └─ onTap: js/console.log("Navigate to QR scanner")
│        │     │           ⚠️ TODO: Навигация на /qr-scanner
│        │     │
│        │     └─ 📦 Current Rental Card (conditional)
│        │        └─ Card (показывается если [@state/current-rental](src/bikes/state/app_state.cljd#L4))
│        │           └─ ListTile
│        │              ├─ Leading: Bike icon (32px, green)
│        │              ├─ Title: "Current Rental"
│        │              ├─ Subtitle: "View active rental"
│        │              ├─ Trailing: ChevronRight icon
│        │              └─ onTap: js/console.log("Navigate to rental")
│        │                 ⚠️ TODO: Навигация на /rental
│        │
│        └─ 📊 Stats Card
│           └─ Card
│              └─ Padding (16)
│                 └─ Column (spacing: 12)
│                    ├─ Text: "Your Stats" (18px, bold)
│                    └─ Row (space-between)
│                       ├─ Column (left)
│                       │  ├─ Text: "Total Rides" (12px, grey)
│                       │  └─ Text: "0" (24px, bold)
│                       └─ Column (right)
│                          ├─ Text: "Total Distance" (12px, grey)
│                          └─ Text: "0 km" (24px, bold)
│                          ⚠️ TODO: Получение данных через API
```

### Используемое состояние:
- [`@state/current-rental`](src/bikes/state/app_state.cljd#L4) - проверка наличия активной аренды

### API вызовы:
- ❌ Нет (TODO: [`api/get-current-rental`](src/bikes/services/api.cljd#L29) для проверки активной аренды)
- ❌ Нет (TODO: API для получения статистики пользователя)

### Навигация:
- ⚠️ TODO: `/qr-scanner` - при нажатии на "Scan QR Code" → [`qr-scanner-screen`](src/bikes/screens/qr_scanner.cljd#L8)
- ⚠️ TODO: `/rental` - при нажатии на "Current Rental" → [`bike-rental-screen`](src/bikes/screens/bike_rental.cljd#L7)

---

## 📷 ЭКРАН: [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8) (bikes.screens.qr-scanner)

**Файл:** [`src/bikes/screens/qr_scanner.cljd`](src/bikes/screens/qr_scanner.cljd)

### Структура компонентов:

```
qr-scanner-screen
│
├─ Local State:
│  ├─ scanned-code (atom nil)
│  ├─ show-install-prompt (atom false)
│  └─ scanning (atom true)
│
├─ Scaffold
│  ├─ AppBar
│  │  ├─ Title: "Scan QR Code" (blue background)
│  │  └─ Leading: Back button
│  │     └─ onPressed: js/console.log("Back")
│  │        ⚠️ TODO: Навигация назад
│  │
│  └─ Body (Center)
│     └─ Column (center, spacing: 24)
│        │
│        ├─ 📷 QR Scanner Container (300x300)
│        │  └─ Container
│        │     ├─ Decoration: Grey background, blue border (2px), radius 12
│        │     └─ Center
│        │        └─ Column (center, spacing: 16)
│        │           ├─ QR Scanner Icon (64px, blue)
│        │           └─ Conditional:
│        │              ├─ Если scanned-code:
│        │              │  └─ Column
│        │              │     ├─ Text: "Code scanned!" (18px, bold, green)
│        │              │     └─ Text: @scanned-code (14px, grey)
│        │              └─ Иначе:
│        │                 └─ Text: "Point camera at QR code" (16px, grey)
│        │
│        ├─ 📝 Instructions
│        │  └─ Padding (horizontal: 32, vertical: 16)
│        │     └─ Text: "Scan the QR code on the bike to start rental"
│        │        (center, 14px, grey-700)
│        │
│        ├─ 🔘 Simulate QR Scan Button (TEST)
│        │  └─ ElevatedButton
│        │     └─ onPressed:
│        │        ├─ Генерирует fake-qr-code: "BIKE-{random}"
│        │        ├─ reset! scanned-code → fake-qr-code
│        │        ├─ [state/set-current-bike](src/bikes/state/app_state.cljd#L9)
│        │        │  └─ {:id fake-qr-code
│        │        │     :location "Current Location"
│        │        │     :battery 50-100%}
│        │        └─ setTimeout → js/console.log("Navigate to rental screen")
│        │           ⚠️ TODO: Навигация на /rental
│        │
│        ├─ 📲 PWA Install Prompt (conditional)
│        │  └─ [pwa-install/install-prompt](src/bikes/components/pwa_install.cljd#L5)
│        │     (показывается если @show-install-prompt)
│        │
│        └─ 🔘 Install App Button
│           └─ TextButton
│              └─ onPressed: reset! show-install-prompt → true
```

### Используемое состояние:
- [`@state/current-bike`](src/bikes/state/app_state.cljd#L5) - устанавливается через [`state/set-current-bike`](src/bikes/state/app_state.cljd#L9)
- Локальные atoms: `scanned-code`, `show-install-prompt`, `scanning`

### API вызовы:
- ❌ Нет (TODO: [`api/get-bike-by-qr`](src/bikes/services/api.cljd#L14) после сканирования QR)
- ⚠️ Сейчас: симуляция через локальное состояние

### Навигация:
- ⚠️ TODO: Назад - при нажатии на Back button
- ⚠️ TODO: `/rental` - после успешного сканирования QR → [`bike-rental-screen`](src/bikes/screens/bike_rental.cljd#L7)

### Компоненты:
- [`pwa-install/install-prompt`](src/bikes/components/pwa_install.cljd#L5) - промпт установки PWA

---

## 🚴 ЭКРАН: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) (bikes.screens.bike-rental)

**Файл:** [`src/bikes/screens/bike_rental.cljd`](src/bikes/screens/bike_rental.cljd)

### Структура компонентов:

```
bike-rental-screen
│
├─ Local State:
│  └─ loading (atom false)
│
├─ Reads State:
│  ├─ bike = [@state/current-bike](src/bikes/state/app_state.cljd#L5)
│  └─ rental = [@state/current-rental](src/bikes/state/app_state.cljd#L4)
│
├─ Scaffold
│  ├─ AppBar
│  │  ├─ Title: "Bike Rental" (blue background)
│  │  └─ Leading: Back button
│  │     └─ onPressed: js/console.log("Back")
│  │        ⚠️ TODO: Навигация назад
│  │
│  └─ Body (Conditional: если bike существует)
│     │
│     ├─ ✅ Если bike существует:
│     │  └─ Padding (16)
│     │     └─ Column (spacing: 24)
│     │        │
│     │        ├─ 📦 Bike Info Card
│     │        │  └─ Card
│     │        │     └─ Padding (16)
│     │        │        └─ Column (spacing: 12)
│     │        │           ├─ Text: "Bike #{:id bike}" (24px, bold)
│     │        │           ├─ Row (spacing: 8)
│     │        │           │  ├─ Location icon (16px, grey)
│     │        │           │  └─ Text: {:location bike} (14px, grey)
│     │        │           └─ Row (spacing: 8)
│     │        │              ├─ Battery icon (16px, green)
│     │        │              └─ Text: "Battery: {:battery bike}%" (14px, grey)
│     │        │
│     │        ├─ 📦 Rental Status Card (conditional)
│     │        │  └─ Card (green-50 background, если rental)
│     │        │     └─ Padding (16)
│     │        │        └─ Column (spacing: 8)
│     │        │           ├─ Text: "Rental Active" (18px, bold, green)
│     │        │           ├─ Text: "Started: {:start-time rental}" (14px)
│     │        │           └─ Text: "Duration: {:duration rental} min" (14px)
│     │        │              ⚠️ TODO: Форматирование времени через [helpers/format-time](src/bikes/utils/helpers.cljd#L15)
│     │        │
│     │        ├─ 🔘 Action Buttons
│     │        │  └─ Column (spacing: 12)
│     │        │     │
│     │        │     ├─ Conditional Button:
│     │        │     │  ├─ Если rental существует:
│     │        │     │  │  └─ ElevatedButton (red background)
│     │        │     │  │     ├─ Text: "End Rental" (white)
│     │        │     │  │     └─ onPressed:
│     │        │     │  │        ├─ reset! loading → true
│     │        │     │  │        ├─ setTimeout (1000ms):
│     │        │     │  │        │  ├─ [state/clear-rental](src/bikes/state/app_state.cljd#L15)
│     │        │     │  │        │  │  └─ Очищает current-rental и current-bike
│     │        │     │  │        │  └─ reset! loading → false
│     │        │     │  │        ⚠️ TODO: [api/end-rental](src/bikes/services/api.cljd#L24)
│     │        │     │  │
│     │        │     │  └─ Иначе (нет rental):
│     │        │     │     └─ ElevatedButton (green background)
│     │        │     │        ├─ Text: "Start Rental" (white)
│     │        │     │        └─ onPressed:
│     │        │     │           ├─ reset! loading → true
│     │        │     │           ├─ setTimeout (1000ms):
│     │        │     │           │  ├─ [state/set-current-rental](src/bikes/state/app_state.cljd#L12)
│     │        │     │           │  │  └─ {:id (random-uuid)
│     │        │     │           │  │     :start-time (js/Date.now)
│     │        │     │           │  │     :duration 0}
│     │        │     │           │  └─ reset! loading → false
│     │        │     │           │  ⚠️ TODO: [api/start-rental](src/bikes/services/api.cljd#L19)
│     │        │     │
│     │        │     └─ Loading Indicator (conditional)
│     │        │        └─ CircularProgressIndicator (если @loading)
│     │        │
│     │        └─ 📋 Instructions Card
│     │           └─ Card
│     │              └─ Padding (16)
│     │                 └─ Column (spacing: 8)
│     │                    ├─ Text: "Instructions" (16px, bold)
│     │                    ├─ Text: "• Scan QR code to unlock the bike"
│     │                    ├─ Text: "• Use the app to lock when finished"
│     │                    └─ Text: "• Return bike to designated area"
│     │
│     └─ ❌ Если bike не существует:
│        └─ Center
│           └─ Column (center, spacing: 16)
│              ├─ Bike icon (64px, grey-400)
│              ├─ Text: "No bike selected" (18px, grey)
│              └─ ElevatedButton
│                 ├─ Text: "Scan QR Code"
│                 └─ onPressed: js/console.log("Scan QR")
│                    ⚠️ TODO: Навигация на [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8)
```

### Используемое состояние:
- [`@state/current-bike`](src/bikes/state/app_state.cljd#L5) - чтение данных о байке
- [`@state/current-rental`](src/bikes/state/app_state.cljd#L4) - чтение/запись данных аренды
- [`state/set-current-rental`](src/bikes/state/app_state.cljd#L12) - установка аренды
- [`state/clear-rental`](src/bikes/state/app_state.cljd#L15) - очистка аренды и байка

### API вызовы:
- ❌ TODO: [`api/start-rental`](src/bikes/services/api.cljd#L19) - при нажатии "Start Rental"
- ❌ TODO: [`api/end-rental`](src/bikes/services/api.cljd#L24) - при нажатии "End Rental"
- ⚠️ Сейчас: симуляция через локальное состояние

### Навигация:
- ⚠️ TODO: Назад - при нажатии на Back button
- ⚠️ TODO: [`/qr-scanner`](src/bikes/screens/qr_scanner.cljd#L8) - если байк не выбран

---

## 📲 КОМПОНЕНТ: [install-prompt](src/bikes/components/pwa_install.cljd#L5) (bikes.components.pwa-install)

**Файл:** [`src/bikes/components/pwa_install.cljd`](src/bikes/components/pwa_install.cljd)

### Структура компонентов:

```
install-prompt
│
└─ Card
   └─ Margin (16)
      └─ Padding (20)
         └─ Column (spacing: 16, cross-axis: start)
            │
            ├─ 📋 Title Row
            │  └─ Row (spacing: 12, start)
            │     ├─ Download icon (24px, blue)
            │     └─ Expanded
            │        └─ Text: "Install App" (18px, bold)
            │
            ├─ 📝 Description
            │  └─ Text: "Install Bikes app for better experience and Bluetooth support"
            │     (14px, grey-700)
            │
            └─ 🔘 Buttons Row
               └─ Row (spacing: 12, end)
                  ├─ TextButton
                  │  ├─ Text: "Later"
                  │  └─ onPressed: js/console.log("Dismiss")
                  │     ⚠️ TODO: Закрыть промпт
                  │
                  └─ ElevatedButton
                     ├─ Text: "Install"
                     └─ onPressed: js/console.log("Install PWA")
                        ⚠️ TODO: Вызов PWA install API
```

### Используемое состояние:
- Нет (чистый компонент)

### API вызовы:
- ❌ TODO: PWA Install API (браузерный API)

---

## 🌐 СЕРВИС: API (bikes.services.api)

**Файл:** [`src/bikes/services/api.cljd`](src/bikes/services/api.cljd)

### Функции и их использование:

```
api/
│
├─ 🔧 [request](src/bikes/services/api.cljd#L7) (базовый HTTP запрос)
│  └─ Параметры: method, endpoint, data
│  └─ Использование: внутренняя функция
│  └─ Статус: ⚠️ TODO - реализация через http пакет
│
├─ 📍 [get-bike-by-qr](src/bikes/services/api.cljd#L14)
│  └─ Параметры: qr-code (string)
│  └─ Endpoint: GET /bikes/{qr-code}
│  └─ Использование: 
│     ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8))
│  └─ Статус: ⚠️ TODO
│
├─ ▶️ [start-rental](src/bikes/services/api.cljd#L19)
│  └─ Параметры: bike-id, user-id
│  └─ Endpoint: POST /rentals/start
│  └─ Body: {:bike-id bike-id :user-id user-id}
│  └─ Использование:
│     ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7))
│  └─ Статус: ⚠️ TODO
│
├─ ⏹️ [end-rental](src/bikes/services/api.cljd#L24)
│  └─ Параметры: rental-id
│  └─ Endpoint: POST /rentals/{rental-id}/end
│  └─ Использование:
│     ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7))
│  └─ Статус: ⚠️ TODO
│
├─ 📊 [get-current-rental](src/bikes/services/api.cljd#L29)
│  └─ Параметры: user-id
│  └─ Endpoint: GET /rentals/current?user-id={user-id}
│  └─ Использование:
│     ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [home-screen](src/bikes/screens/home.cljd#L7))
│  └─ Статус: ⚠️ TODO
│
└─ 🔐 [authenticate](src/bikes/services/api.cljd#L34)
   └─ Параметры: phone-number
   └─ Endpoint: POST /auth/login
   └─ Body: {:phone phone-number}
   └─ Использование:
      ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO - нужен экран логина)
   └─ Статус: ⚠️ TODO
```

### Base URL:
- [`api-base-url`](src/bikes/services/api.cljd#L5): `https://api.bikes.example.com` (⚠️ TODO: заменить на реальный)

---

## 📡 СЕРВИС: Bluetooth (bikes.services.bluetooth)

**Файл:** [`src/bikes/services/bluetooth.cljd`](src/bikes/services/bluetooth.cljd)

### Функции и их использование:

```
bluetooth/
│
├─ 🔍 [scan-for-devices](src/bikes/services/bluetooth.cljd#L23)
│  └─ Параметры: нет
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│  └─ Статус: ⚠️ TODO - реализация через flutter_blue_plus
│
├─ 🔌 [connect-to-device](src/bikes/services/bluetooth.cljd#L29)
│  └─ Параметры: device-id
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│  └─ Статус: ⚠️ TODO
│
├─ 🔓 [unlock-bike](src/bikes/services/bluetooth.cljd#L35)
│  └─ Параметры: device-id
│  └─ Команда: [0x02](src/bikes/services/bluetooth.cljd#L15) ([unlock-command](src/bikes/services/bluetooth.cljd#L15))
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│  └─ Статус: ⚠️ TODO
│
├─ 🔒 [lock-bike](src/bikes/services/bluetooth.cljd#L41)
│  └─ Параметры: device-id
│  └─ Команда: [0x01](src/bikes/services/bluetooth.cljd#L14) ([lock-command](src/bikes/services/bluetooth.cljd#L14))
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│  └─ Статус: ⚠️ TODO
│
├─ 📊 [get-bike-status](src/bikes/services/bluetooth.cljd#L47)
│  └─ Параметры: device-id
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│  └─ Статус: ⚠️ TODO
│
├─ 🔋 [get-battery-level](src/bikes/services/bluetooth.cljd#L53)
│  └─ Параметры: device-id
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│  └─ Статус: ⚠️ TODO
│
└─ 📡 [subscribe-to-status](src/bikes/services/bluetooth.cljd#L59)
   └─ Параметры: device-id, callback
   └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
   └─ Статус: ⚠️ TODO
```

### BLE Константы:
- Service UUID: [`lock-service-uuid`](src/bikes/services/bluetooth.cljd#L6) = `0000ff00-0000-1000-8000-00805f9b34fb`
- Lock Control UUID: [`lock-control-uuid`](src/bikes/services/bluetooth.cljd#L9) = `0000ff01-0000-1000-8000-00805f9b34fb` (WRITE)
- Lock Status UUID: [`lock-status-uuid`](src/bikes/services/bluetooth.cljd#L10) = `0000ff02-0000-1000-8000-00805f9b34fb` (READ/NOTIFY)
- Battery Level UUID: [`battery-level-uuid`](src/bikes/services/bluetooth.cljd#L11) = `0000ff03-0000-1000-8000-00805f9b34fb` (READ/NOTIFY)

### Команды:
- Lock: [`lock-command`](src/bikes/services/bluetooth.cljd#L14) = `0x01`
- Unlock: [`unlock-command`](src/bikes/services/bluetooth.cljd#L15) = `0x02`
- Status: [`status-command`](src/bikes/services/bluetooth.cljd#L16) = `0x03`

### Статусы:
- Locked: [`status-locked`](src/bikes/services/bluetooth.cljd#L19) = `0x00`
- Unlocked: [`status-unlocked`](src/bikes/services/bluetooth.cljd#L20) = `0x01`
- Error: [`status-error`](src/bikes/services/bluetooth.cljd#L21) = `0x02`

---

## 💾 СОСТОЯНИЕ: app-state (bikes.state.app-state)

**Файл:** [`src/bikes/state/app_state.cljd`](src/bikes/state/app_state.cljd)

### Атомы:

```
app-state/
│
├─ [current-rental](src/bikes/state/app_state.cljd#L4) (atom nil)
│  └─ Структура: {:id uuid
│                 :start-time timestamp
│                 :duration minutes}
│  └─ Использование:
│     ├─ Чтение: [home-screen](src/bikes/screens/home.cljd#L7), [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
│     ├─ Запись: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) ([set-current-rental](src/bikes/state/app_state.cljd#L12))
│     └─ Очистка: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) ([clear-rental](src/bikes/state/app_state.cljd#L15))
│
├─ [current-bike](src/bikes/state/app_state.cljd#L5) (atom nil)
│  └─ Структура: {:id string
│                 :location string
│                 :battery number}
│  └─ Использование:
│     ├─ Чтение: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
│     ├─ Запись: [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8) ([set-current-bike](src/bikes/state/app_state.cljd#L9))
│     └─ Очистка: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) ([clear-rental](src/bikes/state/app_state.cljd#L15))
│
├─ [user](src/bikes/state/app_state.cljd#L6) (atom nil)
│  └─ Структура: TODO
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│
└─ [pwa-installed](src/bikes/state/app_state.cljd#L7) (atom false)
   └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
```

### Функции:

```
app-state/
│
├─ [set-current-bike](src/bikes/state/app_state.cljd#L9) [bike-data]
│  └─ Вызывается: [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8)
│
├─ [set-current-rental](src/bikes/state/app_state.cljd#L12) [rental-data]
│  └─ Вызывается: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
│
├─ [clear-rental](src/bikes/state/app_state.cljd#L15) []
│  └─ Вызывается: [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
│  └─ Очищает: current-rental и current-bike
│
├─ [set-user](src/bikes/state/app_state.cljd#L19) [user-data]
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│
└─ [set-pwa-installed](src/bikes/state/app_state.cljd#L22) [installed?]
   └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
```

---

## 🔧 УТИЛИТЫ: helpers (bikes.utils.helpers)

**Файл:** [`src/bikes/utils/helpers.cljd`](src/bikes/utils/helpers.cljd)

### Функции:

```
helpers/
│
├─ [format-duration](src/bikes/utils/helpers.cljd#L4) [minutes]
│  └─ Форматирует минуты в "X min" или "X h Y min"
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7))
│
├─ [format-time](src/bikes/utils/helpers.cljd#L15) [timestamp]
│  └─ Форматирует timestamp в читаемый формат
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7))
│
├─ [generate-id](src/bikes/utils/helpers.cljd#L21) []
│  └─ Генерирует случайный UUID
│  └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ
│
└─ [validate-qr-code](src/bikes/utils/helpers.cljd#L26) [code]
   └─ Проверяет формат QR кода (BIKE-{number})
   └─ Использование: ❌ НЕ ИСПОЛЬЗУЕТСЯ (TODO в [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8))
```

---

## 🔄 ПОТОКИ ДАННЫХ

### Поток 1: Сканирование QR и начало аренды

```
1. Пользователь открывает [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8)
   │
2. Нажимает "Simulate QR Scan" (или сканирует реальный QR)
   │
3. qr-scanner-screen:
   ├─ Генерирует fake-qr-code
   ├─ reset! scanned-code → fake-qr-code
   └─ [state/set-current-bike](src/bikes/state/app_state.cljd#L9)
      └─ Устанавливает current-bike в app-state
   │
4. ⚠️ TODO: [api/get-bike-by-qr](src/bikes/services/api.cljd#L14)(fake-qr-code)
   │  └─ Получение данных о байке с сервера
   │
5. Навигация на /rental
   │
6. [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) читает [@state/current-bike](src/bikes/state/app_state.cljd#L5)
   │
7. Пользователь нажимает "Start Rental"
   │
8. bike-rental-screen:
   ├─ reset! loading → true
   ├─ [state/set-current-rental](src/bikes/state/app_state.cljd#L12)
   │  └─ Устанавливает rental в app-state
   └─ ⚠️ TODO: [api/start-rental](src/bikes/services/api.cljd#L19)(bike-id, user-id)
      └─ Отправка запроса на сервер
```

### Поток 2: Завершение аренды

```
1. Пользователь на [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) с активной арендой
   │
2. Нажимает "End Rental"
   │
3. bike-rental-screen:
   ├─ reset! loading → true
   ├─ ⚠️ TODO: [api/end-rental](src/bikes/services/api.cljd#L24)(rental-id)
   │  └─ Отправка запроса на сервер
   ├─ [state/clear-rental](src/bikes/state/app_state.cljd#L15)
   │  └─ Очищает current-rental и current-bike
   └─ reset! loading → false
   │
4. Экран показывает "No bike selected"
```

### Поток 3: Проверка активной аренды

```
1. Пользователь открывает [home-screen](src/bikes/screens/home.cljd#L7)
   │
2. home-screen читает [@state/current-rental](src/bikes/state/app_state.cljd#L4)
   │
3. ⚠️ TODO: [api/get-current-rental](src/bikes/services/api.cljd#L29)(user-id)
   │  └─ Проверка активной аренды на сервере
   │
4. Если есть активная аренда:
   └─ Показывается карточка "Current Rental"
```

---

## 📋 ЧЕКЛИСТ ИНТЕГРАЦИЙ

### API Интеграции:
- [ ] [`api/get-bike-by-qr`](src/bikes/services/api.cljd#L14) в [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8) после сканирования
- [ ] [`api/start-rental`](src/bikes/services/api.cljd#L19) в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) при старте аренды
- [ ] [`api/end-rental`](src/bikes/services/api.cljd#L24) в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) при завершении
- [ ] [`api/get-current-rental`](src/bikes/services/api.cljd#L29) в [home-screen](src/bikes/screens/home.cljd#L7) при загрузке
- [ ] [`api/authenticate`](src/bikes/services/api.cljd#L34) - нужен экран логина

### Навигация:
- [ ] Навигация на `/qr-scanner` из [home-screen](src/bikes/screens/home.cljd#L7)
- [ ] Навигация на `/rental` из [home-screen](src/bikes/screens/home.cljd#L7) (если есть аренда)
- [ ] Навигация на `/rental` из [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8) после сканирования
- [ ] Навигация назад из [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8)
- [ ] Навигация назад из [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
- [ ] Навигация на `/qr-scanner` из [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7) (если нет байка)

### Bluetooth Интеграции:
- [ ] [`bluetooth/scan-for-devices`](src/bikes/services/bluetooth.cljd#L23) - поиск блокировщиков
- [ ] [`bluetooth/connect-to-device`](src/bikes/services/bluetooth.cljd#L29) - подключение к блокировщику
- [ ] [`bluetooth/unlock-bike`](src/bikes/services/bluetooth.cljd#L35) - разблокировка байка
- [ ] [`bluetooth/lock-bike`](src/bikes/services/bluetooth.cljd#L41) - блокировка байка
- [ ] [`bluetooth/get-bike-status`](src/bikes/services/bluetooth.cljd#L47) - получение статуса
- [ ] [`bluetooth/get-battery-level`](src/bikes/services/bluetooth.cljd#L53) - получение уровня батареи
- [ ] [`bluetooth/subscribe-to-status`](src/bikes/services/bluetooth.cljd#L59) - подписка на обновления

### Утилиты:
- [ ] [`helpers/format-duration`](src/bikes/utils/helpers.cljd#L4) в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
- [ ] [`helpers/format-time`](src/bikes/utils/helpers.cljd#L15) в [bike-rental-screen](src/bikes/screens/bike_rental.cljd#L7)
- [ ] [`helpers/validate-qr-code`](src/bikes/utils/helpers.cljd#L26) в [qr-scanner-screen](src/bikes/screens/qr_scanner.cljd#L8)

### PWA:
- [ ] PWA Install API в [install-prompt](src/bikes/components/pwa_install.cljd#L5)
- [ ] Проверка установки PWA при загрузке
- [ ] Обновление [`pwa-installed`](src/bikes/state/app_state.cljd#L7) в app-state

---

## 📊 СТАТИСТИКА ПРОЕКТА

- **Всего файлов ClojureDart**: 11
- **Экранов**: 3 ([home](src/bikes/screens/home.cljd), [qr-scanner](src/bikes/screens/qr_scanner.cljd), [bike-rental](src/bikes/screens/bike_rental.cljd))
- **Компонентов**: 1 ([pwa-install](src/bikes/components/pwa_install.cljd))
- **Сервисов**: 2 ([api](src/bikes/services/api.cljd), [bluetooth](src/bikes/services/bluetooth.cljd))
- **Утилит**: 1 ([helpers](src/bikes/utils/helpers.cljd))
- **Состояние**: 4 атома ([current-rental](src/bikes/state/app_state.cljd#L4), [current-bike](src/bikes/state/app_state.cljd#L5), [user](src/bikes/state/app_state.cljd#L6), [pwa-installed](src/bikes/state/app_state.cljd#L7))

### Статус реализации:
- ✅ Структура: 100%
- ⚠️ API интеграции: 0% (все TODO)
- ⚠️ Навигация: 0% (все TODO)
- ⚠️ Bluetooth: 0% (все TODO)
- ⚠️ PWA Install: 0% (TODO)

---

*Последнее обновление: добавлены интерактивные ссылки на файлы и функции*
