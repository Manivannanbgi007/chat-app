#!/bin/bash
# Deploys (or rolls back) the production stack on THIS server:
#   bash infra/deploy.sh              uses IMAGE_TAG from infra/.env.prod
#   bash infra/deploy.sh --no-backup  skip the safety backup (first install only)
#
# Steps: 1. backup (so a bad release can be undone together with its database changes)
#        2. pull the images of IMAGE_TAG   3. start / update everything   4. wait until all containers are healthy
#        5. check the site through HTTPS.
# Roll back: set IMAGE_TAG in infra/.env.prod to the previous version and run this script again. If the bad
# release already changed the database in a way the old version cannot read, restore the backup from step 1
# (infra/backup/restore.sh, see DEPLOYMENT_GUIDE.md).
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=.env.prod
[ -f "$ENV_FILE" ] || { echo "infra/$ENV_FILE is missing. Run: bash infra/init-env.sh prod"; exit 1; }
set -a; . "./$ENV_FILE"; set +a

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.clamav.yml -f docker-compose.monitoring.yml --env-file "$ENV_FILE")

if [ "${1:-}" != "--no-backup" ] && docker ps --format '{{.Names}}' | grep -qx chat-mysql; then
  echo "== 1/5 Backup before the update"
  ENV_FILE="./$ENV_FILE" bash backup/backup.sh
else
  echo "== 1/5 Backup skipped"
fi

echo "== 2/5 Pull images $IMAGE_PREFIX/*:$IMAGE_TAG"
"${COMPOSE[@]}" pull

echo "== 3/5 Start / update"
"${COMPOSE[@]}" up -d --remove-orphans

echo "== 4/5 Waiting for healthy containers (up to 5 minutes)"
deadline=$(( $(date +%s) + 300 ))
while true; do
  unhealthy=$("${COMPOSE[@]}" ps --format '{{.Name}} {{.Health}}' | grep -v ' healthy$' | grep ' unhealthy\| starting' || true)
  [ -z "$unhealthy" ] && break
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "Not healthy in time:"; echo "$unhealthy"
    echo "Look at: docker compose ... logs <service>. To go back: set the old IMAGE_TAG and run this script again."
    exit 1
  fi
  sleep 5
done

echo "== 5/5 Checking https://$DOMAIN"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$DOMAIN/" || true)
api=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$DOMAIN/api/auth/ping" || true)
echo "   web app: HTTP $code   api: HTTP $api"
[ "$code" = 200 ] && [ "$api" = 200 ] || { echo "The site does not answer correctly."; exit 1; }
echo "Deployed $IMAGE_TAG."
