# chat-app

A WhatsApp-like real-time messaging platform built as independent microservices.

**Status:** Phases 1-9 complete: users register, find each other, chat one-to-one or in groups in real time with delivered/read ticks, unread counts, typing, offline catch-up, group admins, mute / archive / pin, photos, videos, documents, audio and voice notes, and now **online / last seen** (several devices, privacy aware) and a **notification bell** (missed-message and group notifications, preferences, local desktop notifications), and **one-to-one voice and video calls** (WebRTC, Coturn relay, call history, missed-call notifications). **Status / stories** (text, photo and video updates that disappear after 24 hours, viewer lists, status privacy) are in as well. Phase 9 added the **production package**: HTTPS reverse proxy, monitoring and logs (Prometheus, Grafana, Loki), real virus scanning, hardened and smaller Docker images, backup with a tested restore, CI/CD and a load test (see [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)). See [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md).

## Quick start
```bash
bash infra/init-env.sh
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build
python infra/scripts/smoke-test.py
(cd frontend && npm run e2e)    # two real browsers chatting
```
Open http://localhost:5280 (frontend), http://localhost:8200/swagger-ui.html (API docs) and http://localhost:8025 (MailHog: verification codes and reset links arrive here). IDE mode and details: [DOCKER_SETUP.md](DOCKER_SETUP.md). Coding rules: [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md). Tests: [TESTING_GUIDE.md](TESTING_GUIDE.md).

## Stack
Java 21, Spring Boot, Spring Cloud Gateway, MySQL, Redis, Kafka, S3-compatible storage (RustFS locally), WebSocket/STOMP, React + TypeScript + Vite + Tailwind.

## Design documents
| Document | Content |
|---|---|
| [REQUIREMENTS.md](REQUIREMENTS.md) | what we build and scope decisions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | diagram, technology, decisions, patterns, ports |
| [DATABASE_DESIGN.md](DATABASE_DESIGN.md) | tables, indexes, Redis keys |
| [MICROSERVICE_DESIGN.md](MICROSERVICE_DESIGN.md) | each service's job and dependencies |
| [API_DOCUMENTATION.md](API_DOCUMENTATION.md) | REST contract |
| [WEBSOCKET_DESIGN.md](WEBSOCKET_DESIGN.md) | STOMP destinations, auth, multi-instance delivery |
| [KAFKA_EVENT_DESIGN.md](KAFKA_EVENT_DESIGN.md) | event envelope, topics, outbox, retry/DLT |
| [SECURITY_DESIGN.md](SECURITY_DESIGN.md) | auth, authorization, rate limits, encryption statement |
| [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md) | progress by phase |

Also: [DOCKER_SETUP.md](DOCKER_SETUP.md), [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md), [TESTING_GUIDE.md](TESTING_GUIDE.md), [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) (production, backup, monitoring, release), `infra/load-test/README.md`.

## Repository layout (planned)
```
infra/      Docker Compose (infra-only and full), .env.example
services/   one independent Maven project per microservice
frontend/   React app
```

## Rules we follow
Independent services (own pom, DB, Dockerfile); REST for sync, Kafka for async events, WebSocket for live delivery, Redis for presence/cache; no secrets in code; simple readable code; one phase at a time, tested before the next.

## Security note
Traffic uses TLS and storage is encrypted at rest, but this is **not** end-to-end encrypted.
