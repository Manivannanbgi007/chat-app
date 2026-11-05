#!/bin/bash
# Backup of everything that cannot be recreated (Phase 9). Run it from anywhere:
#   bash infra/backup/backup.sh
#
# What is saved, into infra/backups/<date-time>/ :
#   <name>_db.sql.gz   one consistent dump of each service database (mysqldump --single-transaction: no downtime)
#   redis.rdb          Redis snapshot (login-session revocations; presence and rate limits are rebuilt by themselves)
#   s3-data.tar.gz     the uploaded files (only for the local RustFS container; with AWS S3 use bucket versioning
#                      and replication instead, see DEPLOYMENT_GUIDE.md)
#   rowcounts.txt      rows per table at backup time (verify-backup.sh compares against it)
#   SHA256SUMS         checksums, so a damaged copy is noticed
# Kafka is NOT saved: every event is written to a database table (outbox) first, so the databases are the truth.
#
# Settings (environment variables, all optional):
#   KEEP_DAYS=14            delete backups older than this many days (0 = keep everything)
#   BACKUP_DIR=...          where to write (default infra/backups)
#   BACKUP_PASSPHRASE=...   if set, every file is encrypted with AES-256 (file name gets ".enc"). The dumps contain
#                           private messages, so ALWAYS set it when the backup leaves the server.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows must not rewrite /data/... paths

# ENV_FILE may be given relative to where you are now: make it absolute before we change folder
if [ -n "${ENV_FILE:-}" ]; then ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"; fi
cd "$(dirname "$0")/.."
set -a; . "${ENV_FILE:-./.env}"; set +a

KEEP_DAYS="${KEEP_DAYS:-14}"
BACKUP_DIR="${BACKUP_DIR:-$(pwd)/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="$BACKUP_DIR/$STAMP"
DATABASES="auth_db user_db conversation_db message_db media_db notification_db call_db status_db"

mkdir -p "$TARGET"
echo "Backup $STAMP -> $TARGET"

# Optionally encrypt what comes in on stdin (so unencrypted data never touches the disk)
finish() {   # finish <file-name>   reads stdin, writes <file-name> or <file-name>.enc
  if [ -n "${BACKUP_PASSPHRASE:-}" ]; then
    openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_PASSPHRASE > "$TARGET/$1.enc"
  else
    cat > "$TARGET/$1"
  fi
}

mysql_exec() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" chat-mysql "$@"; }

# 1. MySQL: one dump per database (root user, because only root can read all of them)
: > "$TARGET/rowcounts.txt"
for db in $DATABASES; do
  mysql_exec mysqldump -uroot --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF "$db" \
    | gzip -9 | finish "${db}.sql.gz"
  # exact row count of every table (information_schema only estimates)
  tables=$(mysql_exec mysql -uroot -N -B -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' ORDER BY 1")
  for table in $tables; do
    count=$(mysql_exec mysql -uroot -N -B -e "SELECT COUNT(*) FROM \`$db\`.\`$table\`")
    echo "$db $table $count" >> "$TARGET/rowcounts.txt"
  done
  echo "  mysql  $db ok"
done

# 2. Redis: ask for a snapshot, then copy it out of the container
docker exec chat-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning SAVE > /dev/null
docker exec chat-redis cat /data/dump.rdb | finish redis.rdb
echo "  redis  ok"

# 3. Uploaded files (local RustFS volume)
if docker volume inspect chat-app_s3-data > /dev/null 2>&1; then
  docker run --rm -v chat-app_s3-data:/data:ro alpine tar czf - -C /data . | finish s3-data.tar.gz
  echo "  files  ok"
else
  echo "  files  skipped (no local volume chat-app_s3-data: back up your S3 bucket instead)"
fi

# 4. Checksums
( cd "$TARGET" && sha256sum * > SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS )
echo "  sha256 ok"

# 5. Delete old backups
if [ "$KEEP_DAYS" -gt 0 ]; then
  find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime "+$KEEP_DAYS" -exec rm -rf {} + 2>/dev/null || true
fi

SIZE=$(du -sh "$TARGET" | cut -f1)
echo "Backup finished: $TARGET ($SIZE)"
echo "$TARGET" > "$BACKUP_DIR/LATEST"
