# Инструкция по настройке проекта

## Установка зависимостей

### 1. Clojure CLI

Убедитесь, что установлен Clojure CLI:
```bash
clj --version
```

Если нет, установите с [официального сайта](https://clojure.org/guides/install_clojure).

### 2. Flutter SDK

Установите Flutter SDK:
```bash
flutter --version
```

Если нет, следуйте [официальной инструкции](https://flutter.dev/docs/get-started/install).

### 3. ClojureDart

ClojureDart должен быть установлен через Clojure CLI при первом запуске.

### 4. Установка зависимостей проекта

```bash
# Flutter зависимости
flutter pub get

# Clojure зависимости (загружаются автоматически при первом запуске)
clj -M:dev
```

## Структура проекта

```
bikes/
├── src/bikes/              # ClojureDart исходники
│   ├── core.cljd          # Точка входа
│   ├── app.cljd           # Главное приложение
│   ├── screens/           # Экраны приложения
│   ├── components/        # Переиспользуемые компоненты
│   └── services/          # Бизнес-логика и сервисы
├── lib/                    # Dart код (если нужен)
├── web/                    # Web конфигурация (PWA)
│   ├── index.html
│   └── manifest.json
├── assets/                 # Ресурсы (иконки, изображения)
├── deps.edn                # Clojure зависимости
└── pubspec.yaml           # Flutter зависимости
```

## Запуск проекта

### Web (PWA) - для разработки

```bash
flutter run -d chrome --web-renderer html
```

### Android

```bash
flutter run
```

### iOS (только на macOS)

```bash
flutter run
```

## Компиляция ClojureDart

ClojureDart компилируется в Dart код автоматически при запуске Flutter.

Для ручной компиляции (если нужно):

```bash
# Проверка синтаксиса
clj -M:dev -m cljd.build check

# Компиляция
clj -M:dev -m cljd.build compile
```

## Настройка flutter-cljd

Библиотека `flutter-cljd` должна быть указана в `deps.edn`. 

**Важно**: Проверьте актуальную версию на [GitHub](https://github.com/dankinsoid/flutter-cljd) и обновите `deps.edn` при необходимости.

## Разработка

### Горячая перезагрузка

Flutter поддерживает горячую перезагрузку (hot reload):
- Нажмите `r` в консоли Flutter для hot reload
- Нажмите `R` для hot restart

### Отладка

1. Используйте `js/console.log` для отладки в ClojureDart
2. Flutter DevTools доступен через `flutter pub global activate devtools`

## PWA Функции

### Установка PWA

PWA можно установить через браузер:
- Chrome: иконка установки в адресной строке
- Safari (iOS): кнопка "Поделиться" → "На экран Домой"

### Service Worker

Service Worker настраивается автоматически Flutter Web.

## Bluetooth Разработка

### Тестирование Bluetooth

Для тестирования Bluetooth функциональности:

1. **Android**: Используйте реальное устройство (эмулятор не поддерживает Bluetooth)
2. **iOS**: Требуется реальное устройство
3. **Web**: Ограниченная поддержка через Web Bluetooth API (только Chrome)

### Разрешения

Убедитесь, что в `android/app/src/main/AndroidManifest.xml` и `ios/Runner/Info.plist` добавлены необходимые разрешения для Bluetooth и Location.

## Проблемы и решения

### ClojureDart не компилируется

1. Проверьте версию Clojure: `clj --version`
2. Очистите кэш: `rm -rf .cpcache`
3. Переустановите зависимости: `clj -M:dev`

### Flutter не видит изменения

1. Выполните `flutter clean`
2. Запустите `flutter pub get`
3. Перезапустите приложение

### Bluetooth не работает

1. Проверьте разрешения в манифестах
2. Убедитесь, что используете реальное устройство (не эмулятор)
3. Проверьте, что устройство поддерживает BLE

