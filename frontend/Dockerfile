# ---- Stage 1: build the React app ----
FROM node:26-alpine AS build
WORKDIR /app

# Copy package files first so Docker can cache the npm install layer.
COPY package.json package-lock.json ./
# The cache mount keeps npm's download cache between builds (BuildKit, the default in current Docker).
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
RUN npm run build

# ---- Stage 2: serve the built files with nginx ----
# "unprivileged" = runs as the user "nginx", not root, and listens on port 8080 (a normal user may not use port 80).
FROM nginxinc/nginx-unprivileged:alpine
# apk upgrade = security fixes of the Alpine packages that were released after the base image was built
USER root
RUN apk upgrade --no-cache
USER nginx
# Files in /etc/nginx/templates are copied to /etc/nginx/conf.d when the container starts, with ${S3_PUBLIC_ORIGIN}
# replaced by the environment variable (the address browsers use for signed file links).
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY security-headers.conf.template /etc/nginx/templates/snippets/security-headers.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

ENV S3_PUBLIC_ORIGIN=http://localhost:9010
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
