#!/bin/bash
# Read unread messages from owner
# Usage: ./owner-read.sh        — unread messages
#        ./owner-read.sh history — all messages

API="https://karmarent.app/api/admin"
TOKEN="kr-admin-2026"

if [ "$1" = "history" ]; then
  curl -s "$API/owner-history" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool 2>/dev/null
else
  curl -s "$API/owner-msgs" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool 2>/dev/null
fi
