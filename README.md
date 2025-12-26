# Bikes - Bike Sharing PWA

Проект байк-шэринга на ClojureDart/Flutter с поддержкой PWA и Bluetooth взаимодействия с блокировщиками.

## Технологии

- **ClojureDart** - Clojure для Dart/Flutter
- **Flutter** - кроссплатформенный фреймворк
- **flutter-cljd** - библиотека для работы с Flutter Material widgets
- **PWA** - поддержка прогрессивного веб-приложения
- **Bluetooth** - взаимодействие с блокировщиками через flutter_blue_plus

## Структура проекта

```
bikes/
├── src/
│   └── bikes/
│       ├── core.cljd          # Точка входа
│       ├── app.cljd           # Главное приложение
│       ├── screens/
│       │   ├── qr_scanner.cljd    # Экран сканирования QR
│       │   ├── bike_unlock.cljd    # Экран разблокировки
│       │   └── bike_rental.cljd    # Экран аренды
│       ├── services/
│       │   ├── bluetooth.cljd      # Сервис Bluetooth
│       │   └── api.cljd            # API клиент
│       └── components/
│           └── pwa_install.cljd    # Компонент установки PWA
├── lib/                        # Dart код (если нужен)
├── assets/                     # Ресурсы
├── deps.edn                    # Clojure зависимости
└── pubspec.yaml               # Flutter зависимости
```

## Установка и запуск

### Требования

- Clojure CLI (clj)
- Flutter SDK
- Dart SDK

### Установка зависимостей

```bash
# Flutter зависимости
flutter pub get

# Clojure зависимости
clj -M:dev
```

### Запуск

```bash
# Web (PWA)
flutter run -d chrome --web-renderer html

# Android
flutter run

# iOS
flutter run
```

## MVP Функциональность

1. **QR Scanner** - сканирование QR кода на байке
2. **PWA Install** - предложение установить PWA
3. **Bluetooth Connection** - подключение к блокировщику
4. **Unlock/Lock** - разблокировка/блокировка байка
5. **Rental Management** - управление арендой

## Bluetooth Возможности

Flutter с flutter_blue_plus предоставляет:
- ✅ Полный доступ к Bluetooth Low Energy (BLE)
- ✅ Работа на iOS и Android
- ✅ Поддержка характеристик и сервисов
- ✅ Чтение/запись данных
- ✅ Подписки на уведомления
- ✅ Сканирование устройств

Это решает ограничения Web Bluetooth API в PWA!

