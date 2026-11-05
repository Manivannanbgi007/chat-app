# Deployment guide

How to run chat-app on a real server, keep it healthy, back it up, update it and undo an update. Written for one
server running Docker Compose (the setup this project is built and tested for). Everything in this guide was tried on a
real stack; the "Try it on your own computer" section lets you rehearse the whole production setup before you rent a server.

## 1. What you get

```
 Internet ──443──> Caddy (HTTPS, certificates by itself)
                     ├─ /api/*  /ws ──> api-gateway ──> 9 services ──> MySQL · Redis · Kafka · object storage
                     └─ everything else ──> frontend (nginx, the web app)
 Internet ──3478──> Coturn (calls through firewalls)
 You (SSH tunnel) ──> Grafana :3390 · Prometheus :9190   (never open to the internet)
```

Only ports **80/443** (Caddy) and **3478 + 49160-49200/udp** (Coturn) are open to the internet. MySQL, Redis, Kafka, the
object storage, every service, MailHog and Kafka UI are **not published** at all.

| File | What it is |
|---|---|
| `infra/docker-compose.yml` | the stack (also used for development) |
| `infra/docker-compose.prod.yml` | turns it into production: registry images, Caddy, nothing else published, Swagger off, strict Coturn |
| `infra/docker-compose.clamav.yml` | virus scanning of uploads (ClamAV); `deploy.sh` always includes it |
| `infra/docker-compose.monitoring.yml` | Prometheus, Grafana, Loki, Promtail |
| `infra/.env.prod.example` | every production setting, explained |
| `infra/deploy.sh` | backup, pull, start, wait for healthy, check the site |
| `infra/backup/` | `backup.sh`, `restore.sh`, `verify-backup.sh` |
| `.github/workflows/` | `ci.yml` (tests, scans), `release.yml` (build, scan, push images, optional deploy) |

## 2. Before you start (checklist)

- [ ] A Linux server with Docker + Docker Compose v2.24 or newer, **4 vCPU and 8 GB RAM** as a start (ClamAV alone needs
      about 1.5 GB; the measurements in section 9 show what the services need).
- [ ] A domain. DNS records **`chat.example.com`** and **`files.chat.example.com`** both point to the server's public IP.
- [ ] Firewall: allow 22 (SSH, ideally only your IP), 80, 443, **3478 tcp+udp** and **49160-49200 udp**. Nothing else.
- [ ] An SMTP account for e-mail (verification codes and password reset), for example Amazon SES, Mailgun, Postmark.
- [ ] A place for backups **outside** the server (another machine or a cloud bucket).
- [ ] Docker's own log rotation (otherwise container logs can fill the disk). `/etc/docker/daemon.json`:
      `{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "5" } }`, then `systemctl restart docker`.

## 3. First installation

```bash
# 1. get the code (or only the infra/ folder: the images come from the registry)
git clone <your repository> /opt/chat-app && cd /opt/chat-app

# 2. create the production settings with random secrets, then edit the rest
bash infra/init-env.sh prod
nano infra/.env.prod          # DOMAIN, TURN_EXTERNAL_IP, IMAGE_PREFIX, IMAGE_TAG, PUBLIC_*, S3_PUBLIC_ENDPOINT, MAIL_*, CADDY_GLOBAL

# 3. log in to the image registry if the images are private
docker login ghcr.io

# 4. first start (nothing to back up yet)
bash infra/deploy.sh --no-backup
```

`deploy.sh` ends with `Deployed v0.1.0.` after it checked `https://<domain>/` and `/api/auth/ping`. Open the site, register,
read the verification e-mail. Then do the two first-day jobs in sections 6 and 7 (backup, monitoring).

**Keep `infra/.env.prod` and `BACKUP_PASSPHRASE` in a password manager.** Lose the passphrase and encrypted backups are useless.

### What each setting means

| Setting | Meaning |
|---|---|
| `DOMAIN`, `CADDY_GLOBAL` | the site name; `email you@example.com` is the Let's Encrypt contact |
| `TURN_EXTERNAL_IP`, `TURN_PUBLIC_HOST` | public IP of the server and the name browsers use for the call relay |
| `PUBLIC_BASE_URL`, `PUBLIC_ORIGINS` | `https://<domain>`: links in e-mails and the browser origin the gateway accepts (CORS) |
| `S3_PUBLIC_ENDPOINT` | where browsers load photos and files from (`https://files.<domain>`, served by Caddy from the storage container) |
| `IMAGE_PREFIX`, `IMAGE_TAG` | which images to run, for example `ghcr.io/your-account/chat-app` and `v1.2.0` |
| `SWAGGER_ENABLED=false` | API documentation pages are off in production |
| `MAIL_*` | your SMTP server (`MAIL_SMTP_AUTH=true`, `MAIL_SMTP_STARTTLS=true` on port 587) |
| the `CHANGE_ME` secrets | database passwords, `JWT_SECRET`, `INTERNAL_SERVICE_TOKEN`, `TURN_SECRET`, ... random, made by `init-env.sh` |

Using Amazon S3 instead of the storage container: remove the `s3` service and the `files.` block of `infra/prod/Caddyfile`,
and set `S3_ENDPOINT` (empty or the regional endpoint), `S3_PUBLIC_ENDPOINT` (the same), `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`
and `S3_BUCKET` for media-service. Turn on bucket versioning and encryption on the bucket.

## 4. Release and update

1. Merge to `main`. `ci.yml` runs every service's tests, the frontend tests, scans dependencies and images for known
   vulnerabilities, and (on `main`) starts the whole stack and runs the smoke test, the security check and the browser tests.
2. Tag a version: `git tag v1.2.0 && git push origin v1.2.0`. `release.yml` builds the 11 images, **scans each one (a fixable
   CRITICAL vulnerability stops the release)** and pushes them to `ghcr.io/<owner>/<repo>/<service>:v1.2.0` with provenance and an SBOM.
3. Update the server: either by hand
   ```bash
   cd /opt/chat-app && nano infra/.env.prod     # IMAGE_TAG=v1.2.0
   bash infra/deploy.sh
   ```
   or automatically: set the repository variable `DEPLOY_ENABLED=true` and the secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`.
   The workflow job uses the GitHub environment **production**: add "required reviewers" to it so a person approves every deployment.

`deploy.sh` always starts with a **backup** (so a bad release can be undone with its database changes), then pulls, starts,
waits until every container is healthy (5 minutes at most) and checks the site. Services restart one after another by
Compose; users may see a few seconds of reconnecting. The database schema is changed by Flyway when a service starts.

**Rule for database changes** (so that going back is possible): a release may only *add* tables and columns. Remove or rename
something in a *later* release, once no running version needs it any more.

### Roll back

```bash
nano infra/.env.prod          # IMAGE_TAG=v1.1.0   (the previous version)
bash infra/deploy.sh --no-backup
```
Or run the workflow **Release** by hand with the tag to deploy again. If the bad release already changed the database in a
way the old version cannot read, restore the backup that `deploy.sh` made first (section 6, "Restore").

## 5. Operating it

| Task | Command |
|---|---|
| State of everything | `docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml -f infra/docker-compose.clamav.yml -f infra/docker-compose.monitoring.yml --env-file infra/.env.prod ps` |
| Logs of one service | the same line with `logs -f --tail 200 message-service`, or Grafana > Explore > Loki |
| Restart one service | the same line with `restart user-service` |
| Follow one request through all services | Grafana > Explore > Loki: `{service=~".+"} \|= "<correlation id>"` (the id is in the `X-Correlation-Id` response header) |

(Shorten the long line with a shell alias, for example `alias chat='docker compose -f ... --env-file infra/.env.prod'`.)

## 6. Backup and recovery

**What is saved** (`infra/backup/backup.sh`, into `infra/backups/<time>/`): one consistent dump of each of the 8 service
databases (no downtime), the Redis snapshot, the uploaded files (local storage container), row counts, and checksums.
Kafka is **not** saved: every event is first written to a database table (outbox), so the databases are the truth.

```bash
export BACKUP_PASSPHRASE=...              # from .env.prod; encrypts everything (the dumps contain private messages)
bash infra/backup/backup.sh               # about 30 seconds for the test data
bash infra/backup/verify-backup.sh        # restores into scratch databases, compares row counts, deletes them
```

**Schedule it** (every night 03:00, keep 14 days) and **copy it off the server** (cron):
```
0 3 * * *  cd /opt/chat-app && set -a && . infra/.env.prod && set +a && ENV_FILE=infra/.env.prod bash infra/backup/backup.sh >> /var/log/chat-backup.log 2>&1 && rclone sync infra/backups remote:chat-backups
```
**A backup that was never restored is not a backup:** run `verify-backup.sh` weekly (put it in cron too and alert on exit code 1).

**Objectives.** *Recovery point (data you can lose):* up to one backup interval, so 24 hours with a nightly backup. Run it more
often (every hour is cheap: it takes seconds on a small database) or add MySQL binary logs (`mysqlbinlog`) if that is too much.
*Recovery time (how long to be back):* restoring the test data took **39 seconds** for all 8 databases; real time grows with data
size, plus the time to start containers (about 2 minutes). Measure it yourself with `verify-backup.sh` (it prints the time).

### Restore (disaster recovery)

```bash
# 1. stop the applications (keep the databases running)
docker compose ... stop api-gateway frontend caddy auth-service user-service conversation-service message-service \
                        media-service presence-service notification-service call-signaling-service status-service
# 2. restore everything, or one database:  --only status_db
export BACKUP_PASSPHRASE=...
ENV_FILE=infra/.env.prod bash infra/backup/restore.sh infra/backups/20261105T030000Z --yes
# 3. start again
bash infra/deploy.sh --no-backup
```
`restore.sh` checks the checksums first and refuses to touch anything if a file is damaged. **Drill (done for this project):** the
database `status_db` was dropped on the running stack, restored with `restore.sh --only status_db`, and the service came back healthy
with all 34 rows.

Complete server loss: new server, steps of section 3, copy `.env.prod` back, copy a backup folder to `infra/backups/`, run
`restore.sh`, then `deploy.sh --no-backup`. Redis only holds revoked login sessions, presence and rate-limit counters: losing it
means a few revoked tokens work for up to 15 more minutes, nothing else.

## 7. Monitoring, logs, alerts

`deploy.sh` starts Prometheus, Grafana, Loki and Promtail. They listen on **127.0.0.1 only**; open them through an SSH tunnel:
```bash
ssh -L 3390:localhost:3390 -L 9190:localhost:9190 you@your-server      # then http://localhost:3390
```
Log in to Grafana as `admin` with `GRAFANA_ADMIN_PASSWORD`. The dashboard **chat-app overview** shows: services up, requests per
second, 5xx errors, response time p95 per service, gateway answers by status (401 / 429 / 503), Java heap, database connections,
Kafka consumer lag, live WebSocket connections, and the warnings / errors of all services from Loki.

**What the numbers mean for you**

| Look at | Healthy | Act when |
|---|---|---|
| Services up | 100 % | below 100 %: `docker ps`, `logs <service>` |
| Gateway 503 | none | any: a service is down or slow; the log line says which route and why (`Answering 503 for route ...`) |
| Response time p95 | below 100 ms | over 1 s for 10 minutes (alert `SlowRequests`) |
| Database connections | far below 10 per service | at the limit for 5 minutes (alert `DatabasePoolExhausted`) |
| Kafka lag | goes back to 0 | stays above 1000 (alert `KafkaConsumerLag`) |
| WebSocket connections | grows with users | plan a second message-service when one holds thousands |

**Alerts.** The rules are in `infra/monitoring/prometheus/alerts.yml` and are visible in Prometheus under *Alerts*. To get an e-mail or a
chat message, add an Alertmanager container and point `prometheus.yml` at it (`alerting:` section); the rules need no change.
Until then, look at the dashboard daily, or use an external uptime check on `https://<domain>/api/auth/ping`.

**Logs.** Services log one line per event with the correlation id. Loki keeps 7 days (`loki.yml`), Prometheus 15 days. Docker keeps
5 x 10 MB per container (daemon setting above). Message text is never logged.

## 8. Security settings you must keep

- `SWAGGER_ENABLED=false` (production file). Management endpoints are never published: the gateway's actuator listens on the
  internal port 8290 and Prometheus reads the services over the Docker network.
- Run `python3 infra/scripts/security-check.py --gateway https://<domain> --frontend https://<domain> --origin https://<domain>`
  after every change of the proxy or gateway. It must say `103/103 checks passed`.
- **Virus scanning** is on in production (`VIRUS_SCANNER=clamav`, container `clamav`). If ClamAV is down, uploads are refused with 503
  (fail closed). It updates its virus signatures by itself. Check with `docker logs chat-clamav`.
- **Coturn** refuses to relay to private networks (`--denied-peer-ip`) and announces the public IP (`--external-ip`). Calls between
  two browsers on the same private network may therefore not use the relay; they connect directly. Do not remove the deny list:
  without it anybody with a TURN password could reach your database through the relay. TURN over TLS (`turns:` on port 5349) is not
  enabled: media is already encrypted (DTLS-SRTP) between the browsers; TLS would only help users behind firewalls that allow nothing
  but port 443. To add it: a certificate file for Coturn (`--cert`, `--pkey`, `--tls-listening-port=5349`), open 5349, and add
  `turns:` URLs in `TurnCredentialService`.
- Keep Docker, the host system and the base images up to date. The nightly CI run scans the dependencies; Dependabot opens update pull requests.
- Read `SECURITY_DESIGN.md` section 7i for the open points (known limits) before you go live.

## 9. How much does it need? (measured)

Measured on a laptop (Windows 11, Docker Desktop, 25 containers, nothing tuned), with the k6 load test in `infra/load-test/`:

| Test | Load | Result |
|---|---|---|
| baseline | 1 chatting pair + 1 browsing user | sending a message 56 ms (p95), arrives on the other phone after 56 ms (p95) |
| load | 25 pairs chatting (6 messages/s) + 25 browsing users, 25 requests/s | send p95 44 ms, delivery p95 45 ms, history p95 12 ms, 0 failed requests, 100 % WebSocket connects |
| stress | 50 pairs sending a message every 0.3-0.6 s (**88 messages/s delivered**) + 10 browsing users, 104 requests/s, 294 sockets | send p95 48 ms, delivery p95 48 ms, 0 failed requests, 293 of 294 sockets connected (the one that did not is not explained: it did not affect any other number) |

Memory limits (Compose) are 512-768 MB per service; the Java services stay far below them at this load. The limit that stopped
the first stress run was a **library default** in the gateway (25 requests at the same time per route), now raised to 500
(`chatapp.gateway.max-concurrent-calls`). These numbers are a starting point, not a promise: repeat the test on your server
(`infra/load-test/README.md`) and watch the Grafana dashboard while it runs.

**Scaling out when one server is not enough:** the services are stateless except message-service, which keeps WebSocket
connections; several message-service instances already share live events through Redis (`TESTING_GUIDE.md` section 6). Put a load
balancer in front of the instances (it must support WebSocket), use managed MySQL / Redis / Kafka / S3, and run services on more
machines. Kubernetes is deliberately not part of this project.

## 10. Try it on your own computer (production rehearsal)

This starts exactly the production configuration (HTTPS through Caddy with its own certificate authority, nothing published but
80/443, Swagger off, strict Coturn) on your PC, using the images you built locally:

```bash
# 1. a settings file for the rehearsal: your dev .env plus these lines at the end
cp infra/.env infra/.env.prod
cat >> infra/.env.prod <<'EOF'
DOMAIN=localhost
CADDY_GLOBAL=local_certs
TURN_EXTERNAL_IP=127.0.0.1
TURN_PUBLIC_HOST=localhost
PUBLIC_BASE_URL=https://localhost
PUBLIC_ORIGINS=https://localhost
S3_PUBLIC_ENDPOINT=https://files.localhost
IMAGE_PREFIX=chat-app-local
IMAGE_TAG=test
SWAGGER_ENABLED=false
MAIL_HOST=mailhog
EOF
# 2. give your local images the names the production file expects
for s in api-gateway auth-service user-service conversation-service message-service media-service presence-service notification-service call-signaling-service status-service frontend; do docker tag chat-app-$s chat-app-local/$s:test; done
# 3. start it (COMPOSE_PROFILES=dev-only keeps MailHog so you can read verification codes)
COMPOSE_PROFILES=dev-only docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml --env-file infra/.env.prod up -d --pull never
```
Open https://localhost (the browser warns about the certificate: it comes from Caddy's own authority; to script against it copy
`/data/caddy/pki/authorities/local/root.crt` out of the `chat-caddy` container and use it as `SSL_CERT_FILE`). Run the checks with
`--gateway https://localhost --origin https://localhost`, and the browser tests with
`E2E_BASE_URL=https://localhost E2E_IGNORE_HTTPS_ERRORS=true npx playwright test`.
**Result when this was tried:** smoke test 249/249 through HTTPS, security check 103/103, browser tests 6 of 9 pass; the 3 that do not
pass are explained in IMPLEMENTATION_CHECKLIST.md (Coturn correctly refuses to relay between two browsers on one private machine, and the
test's Node HTTP client cannot resolve `files.localhost`). Go back to development with `docker compose -f infra/docker-compose.yml up -d`
and delete `infra/.env.prod`.

## 11. Troubleshooting

| Symptom | Look at |
|---|---|
| The site shows a certificate error | DNS must point to the server and ports 80/443 must be open (Let's Encrypt checks from outside); `docker logs chat-caddy` |
| Users get no verification e-mail | `MAIL_*` settings; `docker logs chat-auth-service` shows the SMTP error |
| Photos do not load | `S3_PUBLIC_ENDPOINT` must be `https://files.<domain>` and that DNS name must exist; the browser console shows blocked requests (Content-Security-Policy) |
| Calls connect only on the same network | `TURN_EXTERNAL_IP` wrong or ports 3478 / 49160-49200 udp closed in the firewall |
| Uploads fail with 503 | ClamAV is down or still downloading signatures (first start, about a minute): `docker logs chat-clamav` |
| Many 503 from the gateway | `docker logs chat-api-gateway | grep "Answering 503"` names the route and the reason |
| A container restarts again and again | `docker logs <name>`; a missing secret in `.env.prod` stops Compose at start with a clear message |
