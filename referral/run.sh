#!/bin/sh
set -e

# Debug: print relevant env vars
echo "ENV CHECK: BUCKET_NAME='${BUCKET_NAME}' AWS_REGION='${AWS_REGION}'"
env | sort | head -30

# If Litestream env vars are not set, run app directly (no backup)
if [ -z "$BUCKET_NAME" ] || [ -z "$AWS_ACCESS_KEY_ID" ]; then
  echo "WARNING: Litestream env vars not set, running without backup"
  exec clj -M:run
fi

# Generate litestream config with env vars expanded
cat > /tmp/litestream.yml << EOF
dbs:
  - path: /data/karma_rent.db
    replicas:
      - type: s3
        bucket: ${BUCKET_NAME}
        path: backups/karma_rent.db
        endpoint: ${AWS_ENDPOINT_URL_S3}
        access-key-id: ${AWS_ACCESS_KEY_ID}
        secret-access-key: ${AWS_SECRET_ACCESS_KEY}
        region: ${AWS_REGION}
        sync-interval: 60s
EOF

echo "Litestream config generated (bucket: ${BUCKET_NAME})"

echo "Litestream: restoring DB from backup (if exists)..."
litestream restore -if-replica-exists -config /tmp/litestream.yml /data/karma_rent.db || true

echo "Litestream: starting replication + app..."
exec litestream replicate -exec "clj -M:run" -config /tmp/litestream.yml
