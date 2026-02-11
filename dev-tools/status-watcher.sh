#!/bin/bash
# Status watcher: follows Claude transcript and updates Telegram status message
# - Sends first text response as a separate permanent message
# - Keeps progress message (with dots) below it, updated with tool actions
# Started by inbox-hook.sh, killed by bridge-hook.sh

STATUS_FILE="/tmp/claude-status-msg-id"
PID_FILE="/tmp/claude-status-watcher.pid"
FIRST_SENT_FLAG="/tmp/claude-first-text-sent"
API_BASE="https://karmarent.app/api/admin"
API_TOKEN="kr-admin-2026"
PROJ_DIR="$HOME/.claude/projects/-Users-denisovchar-mobile-app-ios"

# Save PID for cleanup
echo $$ > "$PID_FILE"
rm -f "$FIRST_SENT_FLAG"

# Wait for status message to be sent (inbox-hook runs curl in background)
sleep 2

MSG_ID=$(cat "$STATUS_FILE" 2>/dev/null)
if [ -z "$MSG_ID" ]; then
  rm -f "$PID_FILE"
  exit 0
fi

# Find most recent transcript file
TRANSCRIPT=$(ls -t "$PROJ_DIR"/*.jsonl 2>/dev/null | head -1)
if [ -z "$TRANSCRIPT" ] || [ ! -f "$TRANSCRIPT" ]; then
  rm -f "$PID_FILE"
  exit 0
fi

# Start from current end of file
OFFSET=$(wc -c < "$TRANSCRIPT" | tr -d ' ')
FIRST_TEXT_SENT=0
TOOL_STATUS=""
DOT_PHASE=0
DOTS=("." ".." "...")
TICK=0

while true; do
  sleep 1.5

  # Check if we should stop
  if [ ! -f "$PID_FILE" ]; then
    exit 0
  fi

  # Cycle dots
  DOT_PHASE=$(( (DOT_PHASE + 1) % 3 ))
  TICK=$((TICK + 1))

  # Every 2nd tick (~3s), check transcript for new content
  if [ $((TICK % 2)) -eq 0 ]; then
    CURRENT_SIZE=$(wc -c < "$TRANSCRIPT" 2>/dev/null | tr -d ' ')
    if [ "$CURRENT_SIZE" -gt "$OFFSET" ]; then
      PARSED=$(dd if="$TRANSCRIPT" bs=1 skip="$OFFSET" count=$((CURRENT_SIZE - OFFSET)) 2>/dev/null | /usr/bin/python3 -c "
import json, sys

first_text = ''
tool_status = ''
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        entry = json.loads(line)
        if entry.get('type') == 'assistant':
            for block in entry.get('message', {}).get('content', []):
                if not isinstance(block, dict):
                    continue
                if block.get('type') == 'text' and not first_text:
                    t = block.get('text', '').strip()
                    if t and len(t) > 3:
                        first_text = t[:300]
                elif block.get('type') == 'tool_use':
                    name = block.get('name', '')
                    inp = block.get('input', {})
                    if name == 'Read':
                        tool_status = f'Reading {inp.get(\"file_path\", \"?\").rsplit(\"/\", 1)[-1]}'
                    elif name == 'Edit':
                        tool_status = f'Editing {inp.get(\"file_path\", \"?\").rsplit(\"/\", 1)[-1]}'
                    elif name == 'Write':
                        tool_status = f'Writing {inp.get(\"file_path\", \"?\").rsplit(\"/\", 1)[-1]}'
                    elif name == 'Bash':
                        tool_status = inp.get('description', inp.get('command', '?'))[:50]
                    elif name == 'Glob':
                        tool_status = f'Searching {inp.get(\"pattern\", \"?\")}'
                    elif name == 'Grep':
                        tool_status = f'Grep: {inp.get(\"pattern\", \"?\")[:30]}'
                    elif name == 'Task':
                        tool_status = f'Agent: {inp.get(\"description\", \"?\")}'
                    elif name == 'WebFetch':
                        tool_status = 'Fetching web'
                    elif name == 'WebSearch':
                        tool_status = f'Search: {inp.get(\"query\", \"?\")[:30]}'
                    elif 'xcodebuild' in name:
                        tool_status = f'Xcode: {name.replace(\"mcp__xcodebuild__\", \"\")}'
                    else:
                        tool_status = name
    except:
        continue

print(f'{first_text}|||{tool_status}')
" 2>/dev/null)

      OFFSET=$CURRENT_SIZE

      NEW_FIRST=$(echo "$PARSED" | /usr/bin/python3 -c "import sys; print(sys.stdin.read().split('|||')[0])" 2>/dev/null)
      NEW_TOOL=$(echo "$PARSED" | /usr/bin/python3 -c "import sys; parts=sys.stdin.read().split('|||'); print(parts[1] if len(parts)>1 else '')" 2>/dev/null)

      # Send first text as a separate permanent message (once)
      if [ -n "$NEW_FIRST" ] && [ "$FIRST_TEXT_SENT" -eq 0 ]; then
        FIRST_TEXT_SENT=1
        touch "$FIRST_SENT_FLAG"
        /usr/bin/python3 -c "
import json, urllib.request
text = '''$NEW_FIRST'''.strip()
if text:
    data = json.dumps({'text': text}).encode()
    req = urllib.request.Request('$API_BASE/owner-send', data=data,
        headers={'Authorization': 'Bearer $API_TOKEN', 'Content-Type': 'application/json'})
    try:
        urllib.request.urlopen(req, timeout=5)
    except:
        pass
" 2>/dev/null
      fi

      if [ -n "$NEW_TOOL" ]; then
        TOOL_STATUS="$NEW_TOOL"
      fi
    fi
  fi

  # Update progress message with dots
  /usr/bin/python3 -c "
import json, urllib.request
tool = '''$TOOL_STATUS'''.strip()
dots = '${DOTS[$DOT_PHASE]}'
if tool:
    msg = f'⏳ {tool}{dots}'
else:
    msg = f'⏳ Claude думает{dots}'
data = json.dumps({'message_id': $MSG_ID, 'text': msg}).encode()
req = urllib.request.Request('$API_BASE/owner-edit-msg', data=data,
    headers={'Authorization': 'Bearer $API_TOKEN', 'Content-Type': 'application/json'})
try:
    urllib.request.urlopen(req, timeout=3)
except:
    pass
" 2>/dev/null
done
