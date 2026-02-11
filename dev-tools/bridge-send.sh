#!/bin/bash
# Send message to Denis via HyperFocus Bridge
# Usage: ./bridge-send.sh "Your message"
# With buttons: ./bridge-send.sh '{"text":"Question?","buttons":[{"label":"Yes","data":"yes"},{"label":"No","data":"no"}]}'

OUTBOX="$(dirname "$0")/.bridge-outbox"

if [ -z "$1" ]; then
  echo "Usage: $0 \"message\" or $0 '{\"text\":\"...\",\"buttons\":[...]}'"
  exit 1
fi

# Check if JSON
if echo "$1" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null; then
  echo "$1" >> "$OUTBOX"
else
  echo "$1" >> "$OUTBOX"
fi

echo "Queued for sending"
