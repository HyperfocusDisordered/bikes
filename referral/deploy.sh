#!/bin/bash
# Pre-deploy checks + fly deploy
# Usage: ./deploy.sh

set -e
cd "$(dirname "$0")"

echo "🔍 Pre-deploy checks..."

# 1. Check paren balance in all .clj files
errors=0
for f in src/referral/*.clj; do
  open=$(grep -o '(' "$f" | wc -l | tr -d ' ')
  close=$(grep -o ')' "$f" | wc -l | tr -d ' ')
  if [ "$open" != "$close" ]; then
    echo "❌ PAREN MISMATCH: $f — open=$open close=$close"
    errors=$((errors + 1))
  fi
done

# 2. Check bracket balance
for f in src/referral/*.clj; do
  open=$(grep -o '\[' "$f" | wc -l | tr -d ' ')
  close=$(grep -o '\]' "$f" | wc -l | tr -d ' ')
  if [ "$open" != "$close" ]; then
    echo "❌ BRACKET MISMATCH: $f — open=$open close=$close"
    errors=$((errors + 1))
  fi
done

# 3. Check that all migration files referenced in db.clj exist
for migration in $(grep -oE 'migrations/[0-9]+_[a-z_]+\.sql' src/referral/db.clj | sort -u); do
  if [ ! -f "resources/$migration" ]; then
    echo "❌ MISSING MIGRATION: resources/$migration"
    errors=$((errors + 1))
  fi
done

# 4. Check for common Clojure errors
if grep -rn 'defn.*defn' src/referral/*.clj 2>/dev/null; then
  echo "⚠️  WARNING: nested defn detected"
fi

if [ $errors -gt 0 ]; then
  echo ""
  echo "🛑 $errors error(s) found. Fix before deploying."
  exit 1
fi

echo "✅ All checks passed"
echo ""
echo "🚀 Deploying..."
fly deploy

echo ""
echo "⏳ Waiting 35s for JVM startup..."
sleep 35

status=$(curl -s -o /dev/null -w "%{http_code}" https://karma-rent.fly.dev/admin)
if [ "$status" = "200" ]; then
  echo "✅ Server healthy (200 OK)"
else
  echo "❌ Server returned $status — check logs: fly logs -a karma-rent"
  exit 1
fi
