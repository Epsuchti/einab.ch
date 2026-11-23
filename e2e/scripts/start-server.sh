#!/usr/bin/env bash
# Starts the Spring Boot app (API + bundled Angular client) for the e2e tests: fresh in-memory H2 database, demo login on,
# every outbound channel off. Needs no secrets and never contacts foodsharing.network.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$E2E_DIR/../server"
PORT="${E2E_SERVER_PORT:-8181}"

if [[ "${E2E_SKIP_SERVER_BUILD:-false}" != "true" ]]; then
  (cd "$SERVER_DIR" && ./mvnw -q -DskipTests package)
fi
JAR="$(ls "$SERVER_DIR"/target/foodsharing-einab-tabelle-*.jar | grep -v plain | head -n 1)"

# Run outside server/ so the gitignored server/local-secrets.properties of a developer is never loaded.
RUN_DIR="$E2E_DIR/.server-run"
mkdir -p "$RUN_DIR"
cd "$RUN_DIR"

exec java -jar "$JAR" \
  --spring.profiles.active=local \
  --server.port="$PORT" \
  --spring.datasource.url="jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
  --spring.h2.console.enabled=false \
  --app.auth.demo-login.enabled=true \
  --app.auth.demo-login.bezirk-slug=zuerich \
  --app.auth.demo-login.shared-foodsharing-id=1308 \
  --app.frontend.base-url="http://localhost:${PORT}" \
  --app.outbound.restricted=true \
  --app.foodsharing.send-messages=false \
  --app.foodsharing.automation.enabled=false \
  --app.foodsharing.base-url=http://127.0.0.1:9 \
  --app.foodsharing.phone-number-api-base-url=http://127.0.0.1:9 \
  --app.foodsharing.admin-user=e2e-unused \
  --app.foodsharing.admin-password=e2e-unused \
  --app.push.firebase-credentials-json= \
  --spring.mail.host=127.0.0.1 \
  --spring.mail.port=9
