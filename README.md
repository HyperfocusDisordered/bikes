# 🚴 Bikes - PWA Bike Sharing App

Bike sharing PWA приложение с **ClojureDart** фронтендом и **Clojure** бэкендом.

## 🏗️ Архитектура

- **Frontend**: ClojureDart (Flutter web)
- **Backend**: Clojure HTTP API  
- **Database**: In-memory (mock данные)
- **Maps**: Google Maps API

## 📦 Структура проекта

```
bikes/
├── src/bikes/               # ClojureDart frontend
│   ├── screens/            # Экраны приложения
│   ├── state/              # Global state (atoms)
│   ├── services/           # API клиенты
│   └── components/         # UI компоненты
├── backend/                # Clojure API backend
│   └── src/bikes_api/
│       └── simple.clj      # HTTP сервер
├── lib/                    # Flutter/Dart (альтернативная реализация)
└── dev-tools/              # Инструменты разработки
    └── interactive/        # Интерактивная документация
```

## 🚀 Быстрый старт

### 1. Запуск Clojure Backend

```bash
cd backend
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
clj -M -m bikes-api.simple
```

Backend запустится на **http://localhost:3000**

### 2. Запуск Frontend

```bash
# ClojureDart (рекомендуется)
clj -M:cljd flutter

# Или Flutter/Dart
flutter run
```

### 3. Интерактивная документация

```bash
cd dev-tools/interactive
python3 -m http.server 3456
open http://localhost:3456
```

## 🗺️ Экраны приложения

### ClojureDart Frontend

1. **🗺️ map-screen** - Карта с велосипедами
2. **🏠 home** - Домашний экран  
3. **🚴 bike-rental** - Аренда велосипеда
4. **📷 qr-scanner** - Сканер QR кодов

## 🔌 API Endpoints

- `GET /api/bikes` - Список всех велосипедов
- `GET /api/bikes/:id` - Информация о велосипеде
- `POST /api/rentals/start` - Начать аренду
- `GET /api/rentals/current` - Текущая аренда

## 📊 Mock данные

- **3 велосипеда** в Тбилиси (батарея 85%, 60%, 95%)

## 🛠️ Технологии

### Backend
- **Clojure** + **http-kit** + **cheshire**

### Frontend
- **ClojureDart** + **Flutter** + **Google Maps**

## 📝 Статус MVP

### ✅ Завершено
- ✅ 4 основных экрана
- ✅ Google Maps интеграция
- ✅ Global state (atoms)
- ✅ Clojure HTTP API backend (localhost:3000)
- ✅ Flutter app интеграция с Clojure API
- ✅ API тестирование (6/6 tests passed)
- ✅ E2E тестирование (7/7 scenarios passed)
- ✅ Логирование и мониторинг
- ✅ Автоматизированные тест-скрипты

### 🧪 Тестирование
Приложение полностью протестировано! См. [TESTING_COMPLETE.md](TESTING_COMPLETE.md)

**Быстрый запуск**:
```bash
# Terminal 1: Backend
cd backend && export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" && clj -M -m bikes-api.simple

# Terminal 2: Frontend
flutter run -d chrome

# Terminal 3: Tests
./dev-tools/test-api-integration.sh
```

**Приложение доступно на**: http://localhost:50671 (или другой динамический порт)

## 📖 Документация

- [Backend README](backend/README.md)
- [Interactive Docs](http://localhost:3456)
- [Project Data](dev-tools/interactive/project-data.json)

---

Made with ❤️ using Clojure & ClojureDart
