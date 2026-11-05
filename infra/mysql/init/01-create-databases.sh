#!/bin/bash
# Runs once, the first time the MySQL data volume is created.
# Creates one database and one user per microservice.
# Each user can only access its own database (database-per-service rule).
set -e

create_database() {
  local db="$1"
  local user="$2"
  local password="$3"

  mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE DATABASE IF NOT EXISTS \`${db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${user}'@'%';
FLUSH PRIVILEGES;
EOSQL
}

create_database "auth_db"         "auth_user"         "${AUTH_DB_PASSWORD}"
create_database "user_db"         "user_user"         "${USER_DB_PASSWORD}"
create_database "conversation_db" "conversation_user" "${CONVERSATION_DB_PASSWORD}"
create_database "message_db"      "message_user"      "${MESSAGE_DB_PASSWORD}"
create_database "media_db"        "media_user"        "${MEDIA_DB_PASSWORD}"
create_database "notification_db" "notification_user" "${NOTIFICATION_DB_PASSWORD}"
create_database "call_db"         "call_user"         "${CALL_DB_PASSWORD}"
create_database "status_db"       "status_user"       "${STATUS_DB_PASSWORD}"

echo "chat-app databases created."
