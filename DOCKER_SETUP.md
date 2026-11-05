# DOCKER SETUP

Two ways to run chat-app locally. Both use the same `infra/.env` file.

## 0. One-time setup
1. Install Docker Desktop, Java 21, Maven 3.9+, Node 24+.
2. Create the environment file (random passwords are generated for you):
   ```bash
   bash infra/init-env.sh
   ```
   This creates `infra/.env` (git-ignored). Never commit it. `infra/.env.example` lists every variable.

## 1. IDE mode (daily development)
Only the infrastructure runs in Docker; you start the Spring Boot services and the frontend yourself.

```bash
docker compose -f infra/docker-compose.infra.yml --env-file infra/.env up -d
```

| Component | Host port | Notes |
|---|---|---|
| MySQL | 3330 | 7 databases and 7 users, created on first start |
| Redis | 6391 | password from `.env` |
| Kafka | 9094 | for apps on your computer; containers use `kafka:29092` |
| Kafka UI | http://localhost:8290 | |
| S3 storage (RustFS) | 9010 API, http://localhost:9011 console | access key / secret in `.env` |
| MailHog | SMTP 1025, http://localhost:8025 | catches OTP and verification emails |
| Coturn (TURN / STUN for calls, Phase 7) | 3478 udp + tcp, relay ports 49160-49200 udp | credentials are made per user from `TURN_SECRET` in `.env`; nothing to log in to |

Then run each service from its own folder in its own terminal (it reads passwords and the JWT secret from `infra/.env` automatically):
```bash
cd services/auth-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/user-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/conversation-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/message-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/media-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/presence-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/notification-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd services/api-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
In IDE mode media-service uploads to the storage on `localhost:9010` and signs links for the same address (the defaults), and creates the bucket `chat-media` itself.
Start the gateway last (or any time: routes to a service that is not up yet simply answer 503 until it is).
```bash
cd frontend
npm run dev
```
Open http://localhost:5210 (the Vite dev server forwards `/api` and `/ws` to the gateway). Ports of the services: gateway 8200, auth 8201, user 8202, conversation 8203, message 8204, media 8205, notification 8206, presence 8207, call-signaling 8208, status 8209. Swagger UI: http://localhost:8200/swagger-ui.html (gateway, lists every service) or per service: http://localhost:8201/swagger-ui.html (auth), http://localhost:8202/swagger-ui.html (user). Emails (verification codes, reset links): http://localhost:8025.

Check that everything works together:
```bash
python infra/scripts/smoke-test.py
```

> Run services from their own folder. The relative path `../../infra/.env` in `application.yml` depends on it. In an IDE set the "working directory" of the run configuration to the service folder.

## 2. Full containerized mode (integration testing / deployment rehearsal)
Everything runs in containers, including the frontend:
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build
```

| Entry | URL |
|---|---|
| Frontend | http://localhost:5280 |
| Gateway | http://localhost:8200 |
| Gateway health | http://localhost:8200/actuator/health |

Only the gateway and frontend are published. Microservices are reachable only inside the Docker network (`chat-net`) and talk to each other by service name (`auth-service`, `mysql`, `redis`, `kafka`), never `localhost`.

Useful commands:
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env ps
```
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env logs -f auth-service
```
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env down
```
Add `-v` to `down` only if you want to delete all data (databases, Kafka, S3 files).

### Where the files go
Uploaded files are stored in the S3-compatible container (RustFS) in the private bucket `chat-media`; you can look at it in the console http://localhost:9011 (log in with `S3_ACCESS_KEY` / `S3_SECRET_KEY` from `infra/.env`). Download links are signed for `S3_PUBLIC_ENDPOINT` (`http://localhost:9010`), because the **browser** opens them: it must be an address your browser can reach. Uploads are limited by media-service (10 MB images, 100 MB video, 25 MB the rest) and by nginx (`client_max_body_size 110m`). If uploads over 1 MB fail with 413 at nginx, the frontend image was not rebuilt.

### Two real browsers, one conversation
Open the app in a normal window and in a private/incognito window (separate sessions), register two users (codes arrive in MailHog), add each other from **Contacts -> Find people**, press **Start a conversation** and chat. The automated version of this is `npm run e2e` in `frontend/`.

### Monitoring and logs (Phase 9)
```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.monitoring.yml --env-file infra/.env up -d
```
| What | URL | Login |
|---|---|---|
| Grafana (dashboard "chat-app overview", logs from Loki) | http://localhost:3390 | user `admin`, password `GRAFANA_ADMIN_PASSWORD` in `infra/.env` |
| Prometheus (metrics, alert rules under "Alerts") | http://localhost:9190 | none |

Both listen on `127.0.0.1` only. `GRAFANA_ADMIN_PASSWORD` is new in Phase 9: `init-env.sh` only creates a new `.env`, so add a line `GRAFANA_ADMIN_PASSWORD=<a random value>` to an existing one (Compose stops with a clear message if it is missing). The four containers need about 1 GB. Metrics come from `/actuator/prometheus` of each service (the gateway's actuator is on the internal port 8290 inside Docker).

### Virus scanning (Phase 9)
```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.clamav.yml --env-file infra/.env up -d
```
Starts ClamAV (about 1 GB of memory, downloads its virus signatures on the first start) and switches media-service to `VIRUS_SCANNER=clamav`: infected uploads are rejected, and uploads are refused while ClamAV is down. Without this file nothing is scanned (development).

### Production
`docker-compose.prod.yml`, `.env.prod.example`, `deploy.sh`, backups and the whole procedure are in `DEPLOYMENT_GUIDE.md`. The frontend container now listens on port **8080** inside (published as 5280, unchanged for you) and runs as a normal user.

## 3. Independent services
Each service folder has its own `Dockerfile`:
```bash
docker build -t chat-auth-service services/auth-service
```
A service needs only its own database and the infrastructure it uses; you can start just MySQL (`docker compose -f infra/docker-compose.infra.yml --env-file infra/.env up -d mysql`) and run one service.

## 4. Things to know
- The database init script (`infra/mysql/init/`) runs **only when the MySQL volume is first created**. If you change it, run `down -v` first (this deletes data).
- Health checks: MySQL, Redis and Kafka report `healthy`; services expose `/actuator/health/readiness`.
- Resource limits are set per container; if Docker is slow, check Docker Desktop memory (8 GB recommended).
- MinIO no longer publishes Docker images, so local storage uses RustFS (same S3 API). Services use the AWS S3 SDK, so production simply points at real S3.
- Host ports are non-default on purpose (other projects on the same machine use the defaults).
- **Status (Phase 8) on an existing MySQL volume.** The init script that creates the databases only runs on a brand-new volume, so an existing one has no `status_db` yet. Add `STATUS_DB_PASSWORD=<a long random value>` to `infra/.env` and create the database once:
  ```bash
  set -a; . infra/.env; set +a
  docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" chat-mysql mysql -uroot -e "CREATE DATABASE IF NOT EXISTS status_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'status_user'@'%' IDENTIFIED BY '$STATUS_DB_PASSWORD'; GRANT ALL PRIVILEGES ON status_db.* TO 'status_user'@'%'; FLUSH PRIVILEGES;"
  ```
- **Calls (Phase 7).** `infra/.env` needs `TURN_SECRET` (new in Phase 7: `init-env.sh` only creates a new `.env`, so add a line `TURN_SECRET=<a long random value>` to an existing one; Compose stops with a clear message if it is missing). Two browsers on this computer connect directly or through Coturn's container address. To call from another device on your network, add `--external-ip=<your LAN IP>` to the Coturn command in `docker-compose.infra.yml` and set `TURN_PUBLIC_HOST` of call-signaling-service to the same address. Coturn prints `ERROR: CONFIG: Unknown argument:` (empty) once at start-up: its own start script causes it, it is harmless. Debug switch: `localStorage.setItem('chatapp.iceTransportPolicy', 'relay')` in the browser forces calls through the TURN server.
