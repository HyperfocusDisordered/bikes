#!/bin/bash
# Bridge tmux proxy — handles ALL tmux operations
# Avoids macOS TCC "node would like to access data from other apps" alerts
# Communication: file-based signals in the same directory
# Started by bridge-daemon.js as detached process

DIR="$(cd "$(dirname "$0")" && pwd)"
STATE="$DIR/.bridge-tmux-state"
NUDGE="$DIR/.bridge-nudge"
CMD="$DIR/.bridge-cmd"
LOG="$DIR/.bridge-proxy-log"
PID_FILE="$DIR/.bridge-proxy-pid"
TMUX="/opt/homebrew/bin/tmux"

echo $$ > "$PID_FILE"
echo "[$(date +%H:%M:%S)] 🔧 Tmux proxy started (PID: $$)" >> "$LOG"

cleanup() {
  rm -f "$PID_FILE"
  echo "[$(date +%H:%M:%S)] Proxy stopped" >> "$LOG"
  exit 0
}
trap cleanup SIGINT SIGTERM

while true; do
  ts=$(date +%s)

  # ── 1. Update tmux state ──
  if $TMUX has-session -t claude 2>/dev/null; then
    running=true
    clients=$($TMUX list-clients -t claude 2>/dev/null | wc -l | tr -d ' ')
    [ "$clients" -gt 0 ] && attached=true || attached=false

    tail=$($TMUX capture-pane -t claude -p 2>/dev/null | tail -5)
    echo "$tail" | grep -q '❯' && idle=true || idle=false
  else
    running=false
    attached=false
    idle=false
  fi

  printf '{"running":%s,"attached":%s,"idle":%s,"ts":%d}\n' \
    "$running" "$attached" "$idle" "$ts" > "$STATE.tmp"
  mv "$STATE.tmp" "$STATE"

  # ── 2. Check nudge signal ──
  if [ -f "$NUDGE" ]; then
    rm -f "$NUDGE"
    if [ "$running" = true ] && [ "$idle" = true ]; then
      $TMUX send-keys -t claude "check telegram"
      sleep 0.1
      $TMUX send-keys -t claude Enter
      echo "[$(date +%H:%M:%S)] ⚡ NUDGE sent" >> "$LOG"
    fi
  fi

  # ── 3. Check command signal ──
  if [ -f "$CMD" ]; then
    cmd_content=$(cat "$CMD")
    rm -f "$CMD"
    action=$(echo "$cmd_content" | python3 -c "import sys,json; print(json.load(sys.stdin).get('action',''))" 2>/dev/null)

    case "$action" in
      kill)
        pane_pid=$($TMUX list-panes -t claude -F '#{pane_pid}' 2>/dev/null)
        [ -n "$pane_pid" ] && pkill -P "$pane_pid" 2>/dev/null
        $TMUX kill-session -t claude 2>/dev/null
        echo "[$(date +%H:%M:%S)] 🧹 Killed tmux claude session" >> "$LOG"
        ;;
      start)
        proj_dir=$(echo "$cmd_content" | python3 -c "import sys,json; print(json.load(sys.stdin).get('dir',''))" 2>/dev/null)
        claude_cmd=$(echo "$cmd_content" | python3 -c "import sys,json; print(json.load(sys.stdin).get('claudeCmd',''))" 2>/dev/null)
        if [ -n "$proj_dir" ] && [ -n "$claude_cmd" ]; then
          $TMUX new-session -d -s claude \
            "export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; cd '$proj_dir' && while true; do $claude_cmd --dangerously-skip-permissions; echo '⚡ Claude exited, restarting in 5s...'; sleep 5; done"
          echo "[$(date +%H:%M:%S)] 🚀 Started Claude in $proj_dir" >> "$LOG"
        fi
        ;;
      restart)
        # Kill old session first
        pane_pid=$($TMUX list-panes -t claude -F '#{pane_pid}' 2>/dev/null)
        [ -n "$pane_pid" ] && pkill -P "$pane_pid" 2>/dev/null
        $TMUX kill-session -t claude 2>/dev/null
        echo "[$(date +%H:%M:%S)] 🧹 Killed old session for restart" >> "$LOG"
        sleep 2
        # Start new
        proj_dir=$(echo "$cmd_content" | python3 -c "import sys,json; print(json.load(sys.stdin).get('dir',''))" 2>/dev/null)
        claude_cmd=$(echo "$cmd_content" | python3 -c "import sys,json; print(json.load(sys.stdin).get('claudeCmd',''))" 2>/dev/null)
        if [ -n "$proj_dir" ] && [ -n "$claude_cmd" ]; then
          $TMUX new-session -d -s claude \
            "export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; cd '$proj_dir' && while true; do $claude_cmd --dangerously-skip-permissions; echo '⚡ Claude exited, restarting in 5s...'; sleep 5; done"
          echo "[$(date +%H:%M:%S)] 🚀 Started Claude in $proj_dir (restart)" >> "$LOG"
        fi
        ;;
    esac
  fi

  sleep 2
done
