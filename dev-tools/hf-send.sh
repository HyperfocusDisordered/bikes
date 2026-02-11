#!/bin/bash
# Quick send to Denis via HyperFocus Bridge (direct API call)
# Usage: hf-send.sh "message"
# Or pipe: echo "message" | hf-send.sh

if [ -n "$1" ]; then
  MSG="$1"
else
  MSG=$(cat)
fi

if [ -z "$MSG" ]; then
  echo "Usage: $0 \"message\"" >&2
  exit 1
fi

python3 -c "
import json, urllib.request
data = json.dumps({'text': '''$MSG'''}).encode()
req = urllib.request.Request('https://karmarent.app/api/admin/owner-send',
    data=data,
    headers={'Authorization': 'Bearer kr-admin-2026', 'Content-Type': 'application/json'})
urllib.request.urlopen(req)
" 2>/dev/null && echo "Sent" || echo "Failed"
