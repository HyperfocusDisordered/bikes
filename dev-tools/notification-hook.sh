#!/bin/bash
# Claude Code Notification hook (idle_prompt)
# При idle — проверяет inbox и доставляет через systemMessage
# (additionalContext не поддерживается для Notification events,
#  но systemMessage — top-level поле, валидно для всех events)

INBOX="/Users/denisovchar/bikes/dev-tools/.bridge-inbox"

if [ -f "$INBOX" ] && [ -s "$INBOX" ]; then
  # Atomic read: mv before read to prevent race condition
  READING="${INBOX}.reading.$$"
  mv "$INBOX" "$READING" 2>/dev/null
  if [ -f "$READING" ]; then
    MSGS=$(cat "$READING")
    rm -f "$READING"

    echo "$MSGS" | /usr/bin/python3 -c "
import json, sys
msgs = sys.stdin.read().strip()
if msgs:
    result = {
        'continue': True,
        'systemMessage': 'СООБЩЕНИЯ ОТ DENIS (Telegram):\n' + msgs
    }
    print(json.dumps(result))
" 2>/dev/null
  fi
fi
