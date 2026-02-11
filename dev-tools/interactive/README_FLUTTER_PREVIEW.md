# Flutter Preview Setup

## Быстрый старт

Для работы Flutter превью нужно собрать Flutter Web приложение:

```bash
cd /Users/denisovchar/bikes
flutter pub get
flutter build web --release --base-href "/flutter-app/"
```

Или используйте скрипт:

```bash
./dev-tools/interactive/build-flutter.sh
```

## Как это работает

1. **app.cljd** - модифицирован для поддержки параметра `?component=home-screen` из URL
2. **preview.html** - загружает Flutter приложение через iframe
3. **server.js** - обслуживает собранное Flutter приложение по пути `/flutter-app/`

## Поддерживаемые компоненты

- `home-screen` - главный экран
- `qr-scanner-screen` - экран сканирования QR
- `bike-rental-screen` - экран аренды

## Использование

После сборки Flutter приложения, превью будет доступно по адресу:
- `/preview?component=home-screen`
- `/preview?component=qr-scanner-screen`
- `/preview?component=bike-rental-screen`

## Troubleshooting

Если превью не работает:
1. Убедитесь, что Flutter приложение собрано: `ls build/web/index.html`
2. Проверьте, что сервер обслуживает `/flutter-app/` путь
3. Откройте консоль браузера для отладки

