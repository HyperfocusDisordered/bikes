#!/bin/bash
# Один вход — Claude с авторестартом + tmux для daemon nudge
SESSION="claude"
CLAUDE="/opt/homebrew/bin/claude"

if /opt/homebrew/bin/tmux has-session -t $SESSION 2>/dev/null; then
  exec /opt/homebrew/bin/tmux attach -t $SESSION
else
  exec /opt/homebrew/bin/tmux new-session -s $SESSION "cd $HOME/bikes && $CLAUDE --dangerously-skip-permissions; echo '⚡ Claude exited. Press Enter to restart...'; read"
fi
