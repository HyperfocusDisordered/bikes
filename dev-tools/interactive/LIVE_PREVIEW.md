# Live Preview в Cursor

## Проблема

Live Preview в Cursor не поддерживает внешние ссылки (localhost). Нужно использовать относительные пути.

## Решение

Файлы теперь загружаются через относительные пути:
- HTML файл: `dev-tools/interactive/index.html`
- Исходники: `src/bikes/...`
- Относительный путь: `../../src/bikes/...`

## Использование

1. Откройте `dev-tools/interactive/index.html` в Cursor
2. Правый клик → "Open Preview" или "Show Preview"
3. Файлы должны загружаться автоматически

## Если файлы не загружаются

Проверьте консоль браузера (F12) - там будут логи попыток загрузки.

Альтернатива: используйте локальный сервер:
```bash
cd dev-tools/interactive
./start.sh
```

