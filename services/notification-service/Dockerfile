# syntax=docker/dockerfile:1
# Three stages: (1) build the jar and split it into layers, (2) make a small Java runtime with only the Java modules
# the app needs (jlink), (3) put both on a minimal Alpine image that runs as a normal user, not root.

# ---- Stage 1: build the jar (Maven + JDK 21) and split it into layers ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Download dependencies first so Docker can cache this layer until pom.xml changes.
# The cache mount keeps the Maven download folder between builds (BuildKit, the default in current Docker).
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package
# Split the jar: libraries (change rarely) and our own classes (change on every build) become separate layers,
# so a new version only has to ship the small "application" layer.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --destination /build/layers \
 && mkdir -p /build/layers/dependencies /build/layers/snapshot-dependencies /build/layers/application \
 && mv /build/layers/application/*.jar /build/layers/application/app.jar

# ---- Stage 2: a Java runtime with only the modules we need ----
FROM eclipse-temurin:21-jdk-alpine AS jre
RUN jlink --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre \
    --add-modules java.base,java.compiler,java.sql.rowset,jdk.charsets,jdk.jfr,jdk.httpserver,java.logging,java.sql,java.naming,java.management,java.xml,java.desktop,java.instrument,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.transaction.xa,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.net,jdk.naming.dns,jdk.zipfs

# ---- Stage 3: runtime image ----
FROM alpine:3
# apk upgrade = security fixes of the Alpine packages that were released after the base image was built
RUN apk upgrade --no-cache \
 && addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
COPY --from=jre /jre /opt/jre
ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:$PATH
WORKDIR /app
# One COPY per layer (least-changing first) so Docker reuses the unchanged ones.
COPY --from=build --chown=app:app /build/layers/dependencies/ ./
COPY --from=build --chown=app:app /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/layers/application/ ./
USER app
EXPOSE 8206
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8206/actuator/health/readiness | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
