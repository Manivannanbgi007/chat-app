# Java 21 → Java 25 Migration Report

Date: 2026-11-25 · Branch: `java-25-migration`

## 1. Summary

All 10 microservices now build, test and run on **Java 25**.

- **What changed:** only Java version numbers (Maven, Docker, CI, docs) plus one JVM option for the gateway.
  No Java code, no tests, no dependency versions and no application configuration were changed.
  Afterwards, one test-only setting was added to fix a test failure that already existed (section 5, issue 4).
- **Why it was small:** the services already used Spring Boot 4.0.9. Every library version it manages
  (Lombok, Mockito, Hibernate, Kafka, Netty, Testcontainers, the Maven plugins) already supports Java 25.
- **Result:** same 923 tests, all passing, identical coverage. The full stack in Docker passes
  the smoke test, the security check and the browser tests on Java 25, exactly as on Java 21.

| Check | Java 21 (before) | Java 25 (after) |
|---|---|---|
| Unit + integration tests (10 services) | 923 run, 923 passed | 923 run, 923 passed (same test cases) |
| Line coverage | 92.79 % (6388 / 6884) | 92.79 % (6388 / 6884) |
| Branch coverage | 81.70 % (1808 / 2213) | 81.70 % (1808 / 2213) |
| Docker images build | 10 / 10 | 10 / 10 |
| Full stack containers running | 18 / 18 (all 14 with a health check healthy) | 18 / 18 (all 14 with a health check healthy) |
| Smoke test (`infra/scripts/smoke-test.py`) | 249 / 249 | 249 / 249 |
| Security check (`infra/scripts/security-check.py`) | 103 / 103 | 103 / 103 |
| Browser tests (Playwright) | 11 / 11 | 11 / 11 |
| JVM warnings in container logs | 0 | 0 (after the gateway fix in section 5) |
| ERROR lines in container logs | 0 | 0 |

## 2. Services migrated

Each service was migrated, tested and committed on its own (one commit per service).

| Service | Tests | Line coverage | Branch coverage | Before = after? |
|---|---|---|---|---|
| presence-service | 56 | 91.67 % | 75.93 % | ✅ identical |
| api-gateway | 38 | 88.49 % | 66.13 % | ✅ identical |
| auth-service | 58 | 92.94 % | 79.46 % | ✅ identical |
| user-service | 167 | 96.35 % | 88.48 % | ✅ identical |
| conversation-service | 124 | 94.98 % | 78.04 % | ✅ identical |
| message-service | 139 | 91.78 % | 81.82 % | ✅ identical |
| media-service | 130 | 90.99 % | 85.75 % | ✅ identical |
| notification-service | 95 | 92.88 % | 80.34 % | ✅ identical |
| call-signaling-service | 49 | 93.03 % | 82.56 % | ✅ identical |
| status-service | 67 | 91.82 % | 82.76 % | ✅ identical |
| **Total** | **923** | **92.79 %** | **81.70 %** | ✅ |

"Identical" means: the same list of test cases ran (compared name by name), all passed, and the coverage numbers are equal.

## 3. Modified files

| File(s) | Change |
|---|---|
| `services/*/pom.xml` (10) | `<java.version>21</java.version>` → `25` |
| `services/*/Dockerfile` (10) | build image `maven:3.9-eclipse-temurin-21` → `-25`; jlink image `eclipse-temurin:21-jdk-alpine` → `25-jdk-alpine`; one comment |
| `services/api-gateway/Dockerfile` | `JAVA_OPTS` gets `--enable-native-access=ALL-UNNAMED` (see section 5) |
| `.github/workflows/ci.yml` | backend jobs use `java-version: "25"` |
| `services/auth-service/src/test/resources/application-test.properties` | `management.health.mail.enabled=false` (test only, see section 5, issue 4) |
| `README.md`, `ARCHITECTURE.md`, `DEVELOPMENT_GUIDE.md`, `DOCKER_SETUP.md` | "Java 21" → "Java 25" |
| `JAVA_25_MIGRATION_REPORT.md` | this report |

Not changed on purpose: `IMPLEMENTATION_CHECKLIST.md` still says "Java 21" because it records what Phase 1 pinned.

**Dependencies: none changed.** Versions used (managed by Spring Boot 4.0.9 unless noted):

| Library | Version | Java 25 |
|---|---|---|
| Spring Boot / Spring Framework / Spring Cloud | 4.0.9 / 7.0.10 / 2025.1.4 | ✅ |
| Lombok | 1.18.48 | ✅ (Java 25 needs 1.18.40+) |
| MapStruct (own version) | 1.6.3 | ✅ |
| Mockito / ByteBuddy | 5.20.0 / 1.17.8 | ✅ |
| Hibernate / Flyway / MySQL Connector/J | 7.2.25 / 11.14.1 / 9.7.0 | ✅ |
| Kafka clients | 4.1.2 | ✅ |
| Lettuce (Redis) / Netty | 6.8.2 / 4.2.19 | ✅ |
| Testcontainers | 2.0.5 | ✅ |
| AWS SDK (own version, media-service) / springdoc | 2.57.8 / 3.0.3 | ✅ |
| maven-compiler / surefire / failsafe plugins | 3.14.1 / 3.5.6 / 3.5.6 | ✅ |

## 4. How it was tested

1. **Baseline on Java 21** before any change: `mvn clean verify` for every service, with JaCoCo coverage;
   then all 10 Docker images built, the full stack started, smoke test, security check and Playwright run.
2. **Dry run on Java 25** without editing files: `mvn clean verify -Djava.version=25` with JDK 25
   (the `-D` overrides the pom value). All services compiled and passed, so no code fix was needed.
3. **One service at a time:** edit `pom.xml` + `Dockerfile` → `mvn clean verify` on JDK 25 →
   compare test names and coverage with the baseline → build the Docker image and check it runs Java 25 → commit.
4. **Full stack on Java 25:** all images rebuilt, stack started on fresh volumes, smoke test, security check,
   Playwright, container logs checked for warnings and errors.
5. **Gateway option re-check:** after adding `--enable-native-access=ALL-UNNAMED` (section 5, issue 1), the gateway
   image was rebuilt and restarted, and the smoke test (249/249), security check (103/103) and Playwright (11/11)
   were run again. The gateway log has no warnings and no errors, and Netty still uses its native transport.

Coverage was measured with JaCoCo 0.8.16 from the command line, so the poms did not need a JaCoCo plugin:

```bash
mvn clean org.jacoco:jacoco-maven-plugin:0.8.16:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.16:report
```

## 5. Issues found and what was done

| # | Issue | Caused by the migration? | What was done |
|---|---|---|---|
| 1 | **api-gateway prints a warning at startup on Java 25**: Netty loads its native Linux network library, and Java 24+ warns about native code (JEP 472). Later Java versions will block it unless it is allowed. | Yes (new in Java 24+) | Added `--enable-native-access=ALL-UNNAMED` to the gateway `JAVA_OPTS`. No behavior change: Java 25 already allows it, the option only makes it explicit. The warning is gone. |
| 2 | Build log warning: Lombok calls `sun.misc.Unsafe::objectFieldOffset` while compiling (JEP 498). | Yes, but only at compile time | No action. It is in the compiler, not in the application. It will go away with a future Lombok release. |
| 3 | Test log warning: JNA (used by Testcontainers to talk to Docker on Windows) loads native code. | Yes, but only in tests | No action. Not part of the application. |
| 4 | `auth-service` test `SecurityRulesTest.publicEndpointsNeedNoToken` fails when nothing listens on `localhost:1025`. Spring's mail health check tries to reach MailHog, so `/actuator/health` is `DOWN`. | **No** – fails the same way on Java 21 | **Fixed in a separate commit.** The tests never send real email (they use `FakeEmailService`), so the mail health check was only probing a server the tests do not need. Added `management.health.mail.enabled=false` to `auth-service/src/test/resources/application-test.properties`. Test profile only: production still checks the mail server. The test itself was not changed. Result without MailHog: 58 / 58 passed on Java 25 (before: 57 / 58). GitHub Actions has no MailHog, so this also fixes the CI job. |
| 5 | `media-service` test `UploadFlowTest.programsAndScriptsAreRefusedWhateverTheyAreCalled` counts temp files in the shared system temp folder, so it fails if two media-service test runs happen at the same time on one machine. | **No** | Not changed. Passes when run alone (as in CI). |
| 6 | Local machine only: `infra/mysql/init/01-create-databases.sh` had Windows (CRLF) line endings in the working copy, so MySQL could not run it (`/bin/bash^M: bad interpreter`) and every service got "Access denied". | **No** | Restored the file from git (git stores it with LF). Not a repository change. |
| 7 | MapStruct warning "options not recognized: mapstruct.defaultComponentModel" in 4 services. | **No** – same on Java 21 | Not changed. |

## 6. Not applicable to this repository

- **Jenkins:** there is no Jenkinsfile. CI/CD is GitHub Actions (`ci.yml` updated; `release.yml` builds from the Dockerfiles, so it needed no change).
- **SonarQube:** no SonarQube configuration exists.
- **JaCoCo:** not configured in the poms; measured from the command line for this report (section 4).
- **Elasticsearch:** not used by any service.

## 7. Things to know after merging

- **Developers need JDK 25** to build locally (Java 25 class files do not run on Java 21). Set your IDE's project JDK to 25.
- **Production mail server:** Java 24+ disabled old `TLS_RSA_*` cipher suites and Java 25 refuses SHA-1 signatures in TLS 1.2.
  Modern mail servers are not affected; after the first production deploy, send one OTP / verification email to confirm.
- **Rollback:** revert the commits on this branch. Each Docker image carries its own Java runtime, so jar and runtime always match.
