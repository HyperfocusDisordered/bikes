#!/bin/bash
# Send message to owner via Karma Rent bot API
# Usage: ./owner-send.sh "Your message here"
# With buttons: ./owner-send.sh "Question?" '[{"label":"Yes","data":"yes"},{"label":"No","data":"no"}]'

TEXT="$1"
BUTTONS="$2"
API="https://karmarent.app/api/admin/owner-send"
TOKEN="kr-admin-2026"

if [ -z "$TEXT" ]; then
  echo "Usage: $0 \"message\" [buttons_json]"
  exit 1
fi

if [ -z "$BUTTONS" ]; then
  curl -s "$API" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"text\": \"$TEXT\"}" | python3 -m json.tool 2>/dev/null
else
  curl -s "$API" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"text\": \"$TEXT\", \"buttons\": $BUTTONS}" | python3 -m json.tool 2>/dev/null
fi
