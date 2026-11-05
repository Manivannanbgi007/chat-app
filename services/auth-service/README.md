# auth-service

Registration, email verification (OTP), login, JWT access tokens, rotating refresh tokens, logout, password reset, active-device sessions, audit log. Publishes `UserRegisteredEvent` to Kafka through a transactional outbox. The API is described in `API_DOCUMENTATION.md` (section 2).

- Port: 8201 | Database: `auth_db` (user `auth_user`)
- Uses: MySQL, Redis (session-revocation keys for the gateway), Kafka (outbox publisher), SMTP (MailHog locally)
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL, Redis and Kafka)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Docker image: `docker build -t chat-auth-service .`
- Swagger UI: http://localhost:8201/swagger-ui.html
- Verification codes and reset links arrive in MailHog: http://localhost:8025

## How it is organised
```
controller/   AuthController (public endpoints), SessionController (needs a token)
service/      RegistrationService, AuthenticationService (login/refresh/logout), PasswordResetService,
              SessionService, OtpService, TokenService, AuditService, EmailService (+ SMTP impl)
kafka/        EventEnvelope, OutboxService (queue in the same transaction), OutboxPublisher (sends to Kafka)
config/       SecurityConfig (which URLs are public), JwtConfig (HS256 encoder/decoder), AuthProperties
```

## Environment variables
Defaults in `application.yml` work for IDE mode (infra from `infra/docker-compose.infra.yml`); the `prod` profile has no defaults.

| Variable | Meaning |
|---|---|
| `AUTH_DB_URL`, `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD` | MySQL |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka |
| `MAIL_HOST`, `MAIL_PORT` | SMTP server |
| `JWT_SECRET` | at least 32 characters, same value in gateway and user-service |
| `FRONTEND_BASE_URL` | used to build the password reset link |
| `SWAGGER_ENABLED` | `true` / `false` |

## Rules worth remembering
- Passwords are BCrypt hashes; refresh tokens, reset tokens and OTPs are stored only as hashes.
- Using an old refresh token a second time ends that whole login session (token theft protection). Clients must never send two refresh calls at once.
- Tests write times with the database clock (`UTC_TIMESTAMP`) because the app stores UTC and the JVM zone is not UTC.
