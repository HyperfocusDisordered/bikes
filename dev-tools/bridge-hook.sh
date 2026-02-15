#!/bin/bash
# Claude Code Stop hook:
# 1. Удаляет "thinking" статус из Telegram
# 2. Отправляет последний ответ Claude в Telegram (автоматически, в формате кода)

OUTBOX="/Users/denisovchar/bikes/dev-tools/.bridge-outbox"
HOOKLOG="/Users/denisovchar/bikes/dev-tools/.bridge-hook-log"
STATUS_FILE="/tmp/claude-status-msg-id"
API_BASE="https://karmarent.app/api/admin"
API_TOKEN="kr-admin-2026"

PID_FILE="/tmp/claude-status-watcher.pid"
FIRST_SENT_FLAG="/tmp/claude-first-text-sent"

log() { echo "[$(date '+%H:%M:%S')] $1" >> "$HOOKLOG"; }
log "Stop hook fired"

# --- Kill status watcher + delete status message ---
if [ -f "$PID_FILE" ]; then
  WATCHER_PID=$(cat "$PID_FILE")
  kill "$WATCHER_PID" 2>/dev/null
  rm -f "$PID_FILE"
  rm -f "$FIRST_SENT_FLAG"
  log "Killed status watcher: $WATCHER_PID"
fi

if [ -f "$STATUS_FILE" ]; then
  MSG_ID=$(cat "$STATUS_FILE")
  rm -f "$STATUS_FILE"
  if [ -n "$MSG_ID" ]; then
    curl -s -X POST "$API_BASE/owner-delete-msg" \
      -H "Authorization: Bearer $API_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"message_id\":$MSG_ID}" >/dev/null 2>&1 &
    log "Deleted status message: $MSG_ID"
  fi
fi

# Read hook input from stdin
INPUT=$(cat)
log "INPUT length: ${#INPUT}"

# Extract last Claude response from transcript and send to Telegram
# Only send if there's actual text (not just tool calls)
TRANSCRIPT=$(echo "$INPUT" | /usr/bin/python3 -c "
import json, sys
try:
    d = json.loads(sys.stdin.read())
    print(d.get('transcript_path', ''))
except:
    print('')
" 2>/dev/null)
log "TRANSCRIPT: $TRANSCRIPT"

if [ -n "$TRANSCRIPT" ] && [ -f "$TRANSCRIPT" ]; then
  log "Transcript file exists, parsing..."
  /usr/bin/python3 -c "
import json, sys, os

hooklog = '$HOOKLOG'
def hlog(msg):
    with open(hooklog, 'a') as lf:
        from datetime import datetime
        lf.write(f'[{datetime.now().strftime(\"%H:%M:%S\")}] PY: {msg}\n')

outbox = '$OUTBOX'
transcript = '$TRANSCRIPT'

last_text = ''
last_tools = []

# OPTIMIZATION: Only read last 100KB of transcript (not entire 3MB+ file)
import os
file_size = os.path.getsize(transcript)
TAIL_BYTES = 100_000  # 100KB — enough for last few turns
with open(transcript, 'rb') as fb:
    if file_size > TAIL_BYTES:
        fb.seek(file_size - TAIL_BYTES)
        fb.readline()  # skip partial first line
    raw = fb.read().decode('utf-8', errors='replace')
for line in raw.split('\n'):
    line = line.strip()
    if not line:
        continue
    try:
        entry = json.loads(line)
        if entry.get('type') == 'assistant':
            content = entry.get('message', {}).get('content', [])
            if isinstance(content, list):
                text_parts = []
                tool_parts = []
                for block in content:
                    if isinstance(block, dict):
                        if block.get('type') == 'text':
                            t = block.get('text', '').strip()
                            if t:
                                text_parts.append(t)
                        elif block.get('type') == 'tool_use':
                            name = block.get('name', '?')
                            inp = block.get('input', {})
                            # Brief summary of tool usage
                            if name == 'Edit':
                                fp = inp.get('file_path', '?')
                                tool_parts.append(f'✏️ Edit: {fp.rsplit(chr(47),1)[-1]}')
                            elif name == 'Write':
                                fp = inp.get('file_path', '?')
                                tool_parts.append(f'📝 Write: {fp.rsplit(chr(47),1)[-1]}')
                            elif name == 'Read':
                                fp = inp.get('file_path', '?')
                                if 'bridge-' in fp or 'bridge.' in fp:
                                    continue
                                tool_parts.append(f'👁 Read: {fp.rsplit(chr(47),1)[-1]}')
                            elif name == 'Bash':
                                cmd = inp.get('command', '?')[:60]
                                # Skip internal bridge commands
                                import re as _re
                                if _re.search(r'bridge-outbox|bridge-inbox|bridge-hook|bridge-log|bridge-daemon', cmd):
                                    continue
                                tool_parts.append(f'💻 {cmd}')
                            elif name == 'Glob':
                                p = inp.get('pattern', '?')
                                tool_parts.append(f'🔍 Glob: {p}')
                            elif name == 'Grep':
                                p = inp.get('pattern', '?')
                                tool_parts.append(f'🔍 Grep: {p}')
                            elif name == 'Task':
                                d = inp.get('description', '?')
                                tool_parts.append(f'🤖 Agent: {d}')
                            else:
                                tool_parts.append(f'🔧 {name}')
                if text_parts:
                    last_text = '\n'.join(text_parts)
                if tool_parts:
                    last_tools = tool_parts
    except:
        continue

if not last_text and not last_tools:
    hlog('No text or tools found, exiting')
    sys.exit(0)

# Filter: skip 'check telegram' polling turns (don't spam Denis)
import re
is_polling_turn = False
if last_tools:
    all_polling = all(
        re.search(r'owner-msgs|bridge-inbox|check.telegram|\.bridge-outbox|\.bridge-inbox', t, re.IGNORECASE)
        for t in last_tools
    )
    polling_text = not last_text or re.search(
        r'^(\s*|Пусто|пусто|Нет новых|нет новых|No new|Inbox пуст|inbox empty|Проверяю|check telegram|Checking)[\.\s]*$',
        last_text.strip(), re.IGNORECASE
    )
    if all_polling and polling_text:
        is_polling_turn = True

if is_polling_turn:
    hlog('Filtered as polling turn, skipping')
    sys.exit(0)

# Combine text + tools summary
combined = ''
if last_tools:
    combined += '\n'.join(last_tools) + '\n\n'
if last_text:
    combined += last_text

if not combined.strip():
    sys.exit(0)

last_text = combined

# Dedup: don't send same text twice (Stop hook fires after every turn)
import hashlib
text_hash = hashlib.md5(last_text.encode()).hexdigest()
hash_file = '/tmp/claude-bridge-last-hash'
try:
    with open(hash_file) as f:
        if f.read().strip() == text_hash:
            hlog(f'Dedup: same hash {text_hash[:8]}, skipping')
            sys.exit(0)  # Same text, skip
except:
    pass
with open(hash_file, 'w') as f:
    f.write(text_hash)
hlog(f'New hash {text_hash[:8]}, text len={len(last_text)}, writing to outbox')

# Split long messages into chunks for Telegram (max 4096 chars per msg)
import html

CHUNK_SIZE = 3700  # leave room for <pre> tags + header

# Split text into chunks
chunks = []
if len(last_text) <= CHUNK_SIZE:
    chunks = [last_text]
else:
    lines = last_text.split('\n')
    current = ''
    for line in lines:
        if len(current) + len(line) + 1 > CHUNK_SIZE and current:
            chunks.append(current)
            current = line
        else:
            current = current + '\n' + line if current else line
    if current:
        chunks.append(current)

with open(outbox, 'a') as f:
    for i, chunk in enumerate(chunks):
        escaped = html.escape(chunk)
        if len(chunks) > 1:
            formatted = f'🤖 Claude ({i+1}/{len(chunks)}):\n\n<pre>' + escaped + '</pre>'
        else:
            formatted = '🤖 Claude:\n\n<pre>' + escaped + '</pre>'
        msg = json.dumps({'text': formatted})
        f.write(msg + '\n')
    hlog(f'Written {len(chunks)} chunk(s) to outbox')
" 2>>"$HOOKLOG"
  log "Python exit code: $?"
else
  log "SKIP: transcript empty='$([ -z \"$TRANSCRIPT\" ] && echo yes || echo no)' or file missing"
fi

# --- Part 1.5: Git diff/status (if diffs mode enabled) ---
DIFFS_FLAG="/Users/denisovchar/bikes/dev-tools/.bridge-diffs-enabled"
cd /Users/denisovchar/bikes 2>/dev/null
GIT_DIFF=$(/usr/bin/git diff --stat 2>/dev/null)
GIT_STAGED=$(/usr/bin/git diff --cached --stat 2>/dev/null)
if [ -f "$DIFFS_FLAG" ] && { [ -n "$GIT_DIFF" ] || [ -n "$GIT_STAGED" ]; }; then
  /usr/bin/python3 -c "
import json, html, subprocess, os
os.chdir('/Users/denisovchar/bikes')

outbox = '$OUTBOX'

# Get diff stat
diff = subprocess.run(['/usr/bin/git', 'diff', '--stat'], capture_output=True, text=True).stdout.strip()
staged = subprocess.run(['/usr/bin/git', 'diff', '--cached', '--stat'], capture_output=True, text=True).stdout.strip()
# Get short diff (max 2000 chars to not flood)
diff_content = subprocess.run(['/usr/bin/git', 'diff', '--no-color'], capture_output=True, text=True).stdout.strip()
if len(diff_content) > 2000:
    diff_content = diff_content[:2000] + '\n... (truncated)'

parts = []
if staged:
    parts.append('📦 STAGED:\n' + staged)
if diff:
    parts.append('📝 CHANGES:\n' + diff)
if diff_content:
    parts.append(diff_content)

if parts:
    text = '📊 Git Status:\n\n' + '\n\n'.join(parts)
    # Dedup git status
    import hashlib
    git_hash = hashlib.md5(text.encode()).hexdigest()
    hash_file = '/tmp/claude-bridge-git-hash'
    try:
        with open(hash_file) as f:
            if f.read().strip() == git_hash:
                import sys; sys.exit(0)
    except: pass
    with open(hash_file, 'w') as f:
        f.write(git_hash)

    escaped = html.escape(text[:3700])
    msg = json.dumps({'text': '<pre>' + escaped + '</pre>'})
    with open(outbox, 'a') as f:
        f.write(msg + '\n')
" 2>/dev/null
fi

# --- Part 2: Inbox check removed ---
# Inbox теперь читается ТОЛЬКО через notification-hook.sh (idle_prompt event)
# Stop event НЕ поддерживает additionalContext — вызывал JSON validation error
