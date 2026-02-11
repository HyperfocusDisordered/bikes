#!/bin/bash
# Claude Code PermissionRequest hook
# ВСЕГДА отправляет permission request Denis'у в Telegram (3 кнопки)
# Anti-recursion: только bridge/permission скрипты пропускает автоматом

APPROVAL_DIR="/tmp/claude-permissions"
mkdir -p "$APPROVAL_DIR"

# Read hook input from stdin, parse with python3
INPUT=$(cat)
PARSED=$(echo "$INPUT" | /usr/bin/python3 -c "
import json, sys
try:
    d = json.loads(sys.stdin.read())
    tn = d.get('tool_name', 'unknown')
    ti = d.get('tool_input', {})
    ti_str = json.dumps(ti)[:150]
    cmd = ti.get('command', '') if isinstance(ti, dict) else ''
    cmd_prefix = cmd.split()[0] if cmd else ''
    print(f'{tn}|||{ti_str}|||{cmd_prefix}')
except:
    print('unknown|||{}|||')
" 2>/dev/null)

TOOL_NAME=$(echo "$PARSED" | /usr/bin/python3 -c "import sys; p=sys.stdin.read().strip().split('|||'); print(p[0])" 2>/dev/null)
TOOL_INPUT=$(echo "$PARSED" | /usr/bin/python3 -c "import sys; p=sys.stdin.read().strip().split('|||'); print(p[1] if len(p)>1 else '{}')" 2>/dev/null)
CMD_PREFIX=$(echo "$PARSED" | /usr/bin/python3 -c "import sys; p=sys.stdin.read().strip().split('|||'); print(p[2] if len(p)>2 else '')" 2>/dev/null)

# Full command for anti-recursion check
FULL_CMD=$(echo "$INPUT" | /usr/bin/python3 -c "
import json, sys
try:
    d = json.loads(sys.stdin.read())
    print(d.get('tool_input', {}).get('command', ''))
except:
    print('')
" 2>/dev/null)

# ANTI-RECURSION: пропускаем bridge/permission скрипты + telegram/karmarent API
if echo "$FULL_CMD" | /usr/bin/grep -qE '(bridge-send\.sh|bridge-hook\.sh|permission-hook\.sh|/tmp/claude-permissions|\.bridge-outbox|\.bridge-inbox|api\.telegram\.org|karmarent\.app/api)'; then
  echo '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
  exit 0
fi

# AUTO-ALLOW: non-Bash tools (Read, Write, Edit, Glob, Grep, WebFetch etc)
# Файловые операции безопасны — только Bash-команды требуют approval
if [ "$TOOL_NAME" != "Bash" ]; then
  echo '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
  exit 0
fi

# Bash → ВСЕГДА в Telegram с 3 кнопками (Allow / Always / Deny)

# Generate unique request ID
REQ_ID="perm-$(date +%s)"
APPROVAL_FILE="$APPROVAL_DIR/$REQ_ID"

# Send to Telegram via python3 (stdin = безопасное экранирование)
echo "$FULL_CMD" | /usr/bin/python3 -c "
import json, urllib.request, sys
cmd = sys.stdin.read().strip()[:100]
text = '🔐 Permission\n\n' + cmd
data = json.dumps({
    'chat_id': 124694357,
    'text': text,
    'reply_markup': {'inline_keyboard': [[
        {'text': '✅ Allow', 'callback_data': 'perm_allow_$REQ_ID'},
        {'text': '♾ Always', 'callback_data': 'perm_always_$REQ_ID'},
        {'text': '❌ Deny', 'callback_data': 'perm_deny_$REQ_ID'}
    ]]}
}).encode()
req = urllib.request.Request('https://api.telegram.org/bot8300954318:AAGT5lYHVCJf_RVw3fUs-GMe2NB6-Y_6Nxw/sendMessage',
    data=data, headers={'Content-Type': 'application/json'})
try: urllib.request.urlopen(req, timeout=5)
except: pass
" 2>/dev/null

# Write request ID and tool info to pending
echo "$REQ_ID" > "$APPROVAL_DIR/pending"
echo "$TOOL_NAME" > "$APPROVAL_DIR/${REQ_ID}.tool"

# Wait for approval (max 30 seconds, then fallback to UI)
WAITED=0
MAX_WAIT=30
while [ $WAITED -lt $MAX_WAIT ]; do
  if [ -f "$APPROVAL_FILE" ]; then
    DECISION=$(cat "$APPROVAL_FILE")
    rm -f "$APPROVAL_FILE"

    if [ "$DECISION" = "allow" ] || [ "$DECISION" = "always" ]; then
      # If "always" — add rule to settings.local.json
      if [ "$DECISION" = "always" ]; then
        SETTINGS="/Users/denisovchar/bikes/.claude/settings.local.json"
        if [ "$TOOL_NAME" = "Bash" ] && [ -n "$CMD_PREFIX" ]; then
          RULE="Bash(${CMD_PREFIX}:*)"
        elif [ "$TOOL_NAME" = "Bash" ]; then
          RULE="Bash"
        else
          RULE="$TOOL_NAME"
        fi
        /usr/bin/python3 -c "
import json, os
f = '$SETTINGS'
try:
    with open(f) as fh: d = json.load(fh)
except: d = {}
if 'permissions' not in d: d['permissions'] = {}
if 'allow' not in d['permissions']: d['permissions']['allow'] = []
rule = '$RULE'
if rule not in d['permissions']['allow']:
    d['permissions']['allow'].append(rule)
    with open(f, 'w') as fh: json.dump(d, fh, indent=2)
" 2>/dev/null
      fi
      echo '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
      exit 0
    else
      echo '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"Denied by Denis via Telegram"}}}'
      exit 0
    fi
  fi
  sleep 2
  WAITED=$((WAITED + 2))
done

# Timeout — НЕ отвечаем (пустой exit) → Claude покажет обычный UI prompt
# Denis за компом → нажмёт в терминале. Denis на телефоне → нажмёт в Telegram до таймаута
exit 0
