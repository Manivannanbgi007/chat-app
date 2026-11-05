#!/bin/bash
# Restores a backup made by backup.sh. THIS REPLACES THE CURRENT DATA.
#   bash infra/backup/restore.sh infra/backups/20261105T101500Z --yes
#   bash infra/backup/restore.sh infra/backups/20261105T101500Z --yes --only status_db     (one database)
#
# Before you run it: stop the application containers so nobody writes during the restore:
#   docker compose -f infra/docker-compose.yml --env-file infra/.env stop \
#     api-gateway frontend auth-service user-service conversation-service message-service media-service \
#     presence-service notification-service call-signaling-service status-service
# After it: start them again (docker compose ... up -d). The databases keep their Flyway history, so the
# services start normally. Set BACKUP_PASSPHRASE if the backup was encrypted.
set -euo pipefail
export MSYS_NO_PATHCONV=1

SOURCE="${1:-}"
if [ -z "$SOURCE" ] || [ ! -d "$SOURCE" ]; then
  echo "Usage: restore.sh <backup-folder> --yes [--only <database>]"; exit 2
fi
shift
CONFIRMED=no; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --yes) CONFIRMED=yes ;;
    --only) shift; ONLY="$1" ;;
    *) echo "Unknown option $1"; exit 2 ;;
  esac
  shift
done
if [ "$CONFIRMED" != yes ]; then
  echo "This deletes the current data of the databases in $SOURCE and replaces it. Add --yes to continue."; exit 2
fi

# ENV_FILE may be given relative to where you are now: make it absolute before we change folder
if [ -n "${ENV_FILE:-}" ]; then ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"; fi
cd "$(dirname "$0")/.."
set -a; . "${ENV_FILE:-./.env}"; set +a
SOURCE="$(cd "$SOURCE" && pwd)"

echo "Checking checksums ..."
( cd "$SOURCE" && sha256sum -c SHA256SUMS --quiet ) || { echo "A file is damaged. Nothing was restored."; exit 1; }

# print a backup file (decrypting if needed)
readfile() {   # readfile <name-without-.enc>
  if [ -f "$SOURCE/$1.enc" ]; then
    openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_PASSPHRASE < "$SOURCE/$1.enc"
  else
    cat "$SOURCE/$1"
  fi
}

for file in "$SOURCE"/*_db.sql.gz "$SOURCE"/*_db.sql.gz.enc; do
  [ -e "$file" ] || continue
  name="$(basename "$file")"; db="${name%%.sql.gz*}"
  if [ -n "$ONLY" ] && [ "$ONLY" != "$db" ]; then continue; fi
  echo "Restoring $db ..."
  docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" chat-mysql mysql -uroot \
    -e "DROP DATABASE IF EXISTS \`$db\`; CREATE DATABASE \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  readfile "${db}.sql.gz" | gunzip | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" chat-mysql mysql -uroot "$db"
done

if [ -z "$ONLY" ] && { [ -f "$SOURCE/redis.rdb" ] || [ -f "$SOURCE/redis.rdb.enc" ]; }; then
  echo "Restoring Redis ..."
  docker stop chat-redis > /dev/null
  readfile redis.rdb | docker run --rm -i -v chat-app_redis-data:/data alpine sh -c 'cat > /data/dump.rdb'
  docker start chat-redis > /dev/null
fi

if [ -z "$ONLY" ] && { [ -f "$SOURCE/s3-data.tar.gz" ] || [ -f "$SOURCE/s3-data.tar.gz.enc" ]; }; then
  echo "Restoring uploaded files ..."
  docker stop chat-s3 > /dev/null
  readfile s3-data.tar.gz | docker run --rm -i -v chat-app_s3-data:/data alpine sh -c 'rm -rf /data/* /data/.[!.]* 2>/dev/null; tar xzf - -C /data'
  docker start chat-s3 > /dev/null
fi

echo "Restore finished. Start the application containers again."
