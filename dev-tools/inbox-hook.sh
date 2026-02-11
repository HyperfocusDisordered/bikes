#!/bin/bash
# Claude Code UserPromptSubmit hook:
# 1. Убивает старый watcher если есть
# 2. Отправляет "thinking" статус в Telegram (сохраняет message_id)
# 3. Запускает status-watcher для live-обновлений
# 4. Проверяет inbox на сообщения от Denis

INBOX="/Users/denisovchar/bikes/dev-tools/.bridge-inbox"
STATUS_FILE="/tmp/claude-status-msg-id"
PID_FILE="/tmp/claude-status-watcher.pid"
API_BASE="https://karmarent.app/api/admin"
API_TOKEN="kr-admin-2026"
WATCHER="/Users/denisovchar/bikes/dev-tools/status-watcher.sh"

# --- Kill old watcher if still running ---
if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE")
  kill "$OLD_PID" 2>/dev/null
  rm -f "$PID_FILE"
fi

# --- Send "thinking" status + start watcher ---
(
  RESP=$(curl -s -X POST "$API_BASE/owner-send" \
    -H "Authorization: Bearer $API_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"text":"⏳ Claude думает..."}' 2>/dev/null)

  MSG_ID=$(echo "$RESP" | /usr/bin/python3 -c "
import json, sys
try:
    d = json.loads(sys.stdin.read())
    mid = d.get('data', {}).get('message_id')
    if mid:
        print(mid)
except:
    pass
" 2>/dev/null)

  if [ -n "$MSG_ID" ]; then
    echo "$MSG_ID" > "$STATUS_FILE"
    # Start transcript watcher for live status updates
    nohup bash "$WATCHER" >/dev/null 2>&1 &
  fi
) &

# --- Read inbox atomically ---
if [ -f "$INBOX" ] && [ -s "$INBOX" ]; then
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
        'hookSpecificOutput': {
            'hookEventName': 'UserPromptSubmit',
            'additionalContext': 'СООБЩЕНИЯ ОТ DENIS (Telegram):\n' + msgs
        }
    }
    print(json.dumps(result))
" 2>/dev/null
  fi
fi
