#!/bin/bash
# Proves that a backup can really be restored, WITHOUT touching the live data:
#   bash infra/backup/verify-backup.sh                       (checks the newest backup)
#   bash infra/backup/verify-backup.sh infra/backups/2026...  (checks a given one)
#
# For every database dump it: checks the checksums, restores the dump into a scratch database named
# restorecheck_<db>, lists the tables and row counts, compares them with rowcounts.txt (the numbers at backup
# time) and then deletes the scratch database. Also times the restore (that is your recovery time).
# Exit code 0 = every database came back with all its tables and the same row counts as at backup time.
set -euo pipefail
export MSYS_NO_PATHCONV=1

# ENV_FILE may be given relative to where you are now: make it absolute before we change folder
if [ -n "${ENV_FILE:-}" ]; then ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"; fi
cd "$(dirname "$0")/.."
set -a; . "${ENV_FILE:-./.env}"; set +a
BACKUP_DIR="${BACKUP_DIR:-$(pwd)/backups}"
SOURCE="${1:-$(cat "$BACKUP_DIR/LATEST" 2>/dev/null || true)}"
if [ -z "$SOURCE" ] || [ ! -d "$SOURCE" ]; then echo "No backup found. Run backup.sh first."; exit 2; fi
SOURCE="$(cd "$SOURCE" && pwd)"
echo "Verifying $SOURCE"

( cd "$SOURCE" && sha256sum -c SHA256SUMS --quiet ) || { echo "FAIL  checksums do not match"; exit 1; }
echo "  ok    checksums"

mysql_exec() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" chat-mysql "$@"; }
readfile() {
  if [ -f "$SOURCE/$1.enc" ]; then openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_PASSPHRASE < "$SOURCE/$1.enc"; else cat "$SOURCE/$1"; fi
}

FAILED=0
START=$(date +%s)
for file in "$SOURCE"/*_db.sql.gz "$SOURCE"/*_db.sql.gz.enc; do
  [ -e "$file" ] || continue
  name="$(basename "$file")"; db="${name%%.sql.gz*}"; scratch="restorecheck_$db"
  mysql_exec mysql -uroot -e "DROP DATABASE IF EXISTS \`$scratch\`; CREATE DATABASE \`$scratch\`;"
  if ! readfile "${db}.sql.gz" | gunzip | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" chat-mysql mysql -uroot "$scratch"; then
    echo "FAIL  $db: the dump could not be restored"; FAILED=1; continue
  fi
  bad=0; tables=0
  while read -r _ table expected; do
    tables=$((tables + 1))
    actual=$(mysql_exec mysql -uroot -N -B -e "SELECT COUNT(*) FROM \`$scratch\`.\`$table\`" 2>/dev/null || echo MISSING)
    if [ "$actual" != "$expected" ]; then
      echo "FAIL  $db.$table: backup had $expected rows, restore has $actual"; bad=1
    fi
  done < <(grep "^$db " "$SOURCE/rowcounts.txt")
  restored_tables=$(mysql_exec mysql -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$scratch'")
  if [ "$restored_tables" -ne "$tables" ]; then echo "FAIL  $db: $tables tables expected, $restored_tables restored"; bad=1; fi
  mysql_exec mysql -uroot -e "DROP DATABASE \`$scratch\`;"
  if [ "$bad" -eq 0 ]; then echo "  ok    $db: $tables tables, all row counts match"; else FAILED=1; fi
done
SECONDS_USED=$(( $(date +%s) - START ))

if [ -f "$SOURCE/redis.rdb" ] || [ -f "$SOURCE/redis.rdb.enc" ]; then
  header=$(readfile redis.rdb | head -c 5)
  if [ "$header" = "REDIS" ]; then echo "  ok    redis.rdb is a valid Redis snapshot"; else echo "FAIL  redis.rdb is not a Redis snapshot"; FAILED=1; fi
fi
if [ -f "$SOURCE/s3-data.tar.gz" ] || [ -f "$SOURCE/s3-data.tar.gz.enc" ]; then
  if readfile s3-data.tar.gz | tar tzf - > /dev/null; then echo "  ok    s3-data.tar.gz can be read"; else echo "FAIL  s3-data.tar.gz is damaged"; FAILED=1; fi
fi

echo "Restoring all databases into scratch databases took ${SECONDS_USED} s."
if [ "$FAILED" -eq 0 ]; then echo "BACKUP VERIFIED"; else echo "BACKUP HAS PROBLEMS"; exit 1; fi
