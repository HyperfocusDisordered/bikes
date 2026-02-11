#!/bin/bash
# Watches inbox file and types "check telegram" into Claude when messages arrive
INBOX="/Users/denisovchar/bikes/dev-tools/.bridge-inbox"
NUDGE="/tmp/claude-bridge-nudge"
LAST_SIZE=0

while true; do
  if [ -f "$NUDGE" ]; then
    rm -f "$NUDGE"
    # Small delay to let inbox finish writing
    sleep 0.5
    if [ -f "$INBOX" ] && [ -s "$INBOX" ]; then
      osascript -e '
        tell application "Claude" to activate
        delay 0.3
        tell application "System Events"
          keystroke "check telegram"
          keystroke return
        end tell
      '
    fi
  fi
  sleep 2
done
