#!/bin/bash
# Restart Claude Code session to pick up new hooks
# Run this OUTSIDE of Claude Code

echo "Restarting Claude Code session..."

# Find and kill current claude process
CLAUDE_PID=$(pgrep -f "claude" | head -1)
if [ -n "$CLAUDE_PID" ]; then
  echo "Killing claude PID: $CLAUDE_PID"
  kill "$CLAUDE_PID" 2>/dev/null
  sleep 2
fi

# Start new claude session in the bikes directory
cd /Users/denisovchar/bikes
echo "Starting new claude session..."
exec claude --resume
