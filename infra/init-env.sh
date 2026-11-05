#!/bin/bash
# Creates infra/.env (development) or infra/.env.prod (production) from the matching example file and replaces
# every CHANGE_ME with a random value. Safe to run once; it refuses to overwrite an existing file.
#   bash infra/init-env.sh          -> infra/.env
#   bash infra/init-env.sh prod     -> infra/.env.prod   (then edit DOMAIN, IMAGE_PREFIX, SMTP, ... by hand)
set -e
cd "$(dirname "$0")"

if [ "${1:-}" = "prod" ]; then
  TARGET=.env.prod; EXAMPLE=.env.prod.example
else
  TARGET=.env; EXAMPLE=.env.example
fi

if [ -f "$TARGET" ]; then
  echo "$TARGET already exists - not overwriting."
  exit 0
fi

: > "$TARGET"
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  if [[ "$line" == *CHANGE_ME* ]] && [[ "$line" != MAIL_* ]]; then
    secret=$(openssl rand -hex 24)
    line="${line//CHANGE_ME/$secret}"
  fi
  printf '%s\n' "$line" >> "$TARGET"
done < "$EXAMPLE"

chmod 600 "$TARGET" 2>/dev/null || true
echo "Created infra/$TARGET with random passwords."
if [ "$TARGET" = ".env.prod" ]; then
  echo "Now edit it: DOMAIN, TURN_EXTERNAL_IP, IMAGE_PREFIX, the PUBLIC_* / S3_PUBLIC_ENDPOINT addresses and the MAIL_* lines."
fi
