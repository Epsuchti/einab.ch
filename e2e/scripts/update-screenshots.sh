#!/usr/bin/env bash
# Regenerates the screenshots and copies them to docs/screenshots/ (committed), the same way locally and in CI
# (.github/workflows/screenshots.yml). Each Playwright run starts its own fresh test server (demo login, no outbound calls).
#
#   e2e/scripts/update-screenshots.sh                  # journeys + store screenshots, builds the jar first
#   e2e/scripts/update-screenshots.sh --skip-build     # reuse server/target/*.jar
#   e2e/scripts/update-screenshots.sh --only=journeys  # or --only=store
#
# From e2e/: npm run screenshots (passes extra arguments after --, e.g. npm run screenshots -- --only=store)
set -euo pipefail

E2E_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_DIR="$(cd "$E2E_DIR/.." && pwd)"
OUT_DIR="$ROOT_DIR/docs/screenshots"
ONLY=all
SKIP_BUILD=false

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --only=*) ONLY="${arg#--only=}" ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
case "$ONLY" in all|journeys|store) ;; *) echo "--only must be all, journeys or store" >&2; exit 2 ;; esac

cd "$E2E_DIR"
[[ -d node_modules/@playwright/test ]] || npm ci

if [[ "$SKIP_BUILD" != "true" ]]; then
  (cd "$ROOT_DIR/server" && ./mvnw -q -DskipTests package)
fi
export E2E_SKIP_SERVER_BUILD=true

# Start from an empty folder, so steps that were renamed or removed don't linger.
rm -rf screenshots

if [[ "$ONLY" == all || "$ONLY" == journeys ]]; then
  npx playwright test --reporter=list
fi
if [[ "$ONLY" == all || "$ONLY" == store ]]; then
  npx playwright test -c store-screenshots.config.ts
fi

sync() { # <from> <to>
  rm -rf "$2"
  mkdir -p "$2"
  cp -R "$1/." "$2/"
}
if [[ "$ONLY" == all || "$ONLY" == journeys ]]; then
  for project in desktop mobile; do
    sync "screenshots/$project" "$OUT_DIR/$project"
  done
fi
if [[ "$ONLY" == all || "$ONLY" == store ]]; then
  sync "screenshots/store" "$OUT_DIR/store"
fi

echo "Screenshots updated in ${OUT_DIR#"$ROOT_DIR"/}/"
git -C "$ROOT_DIR" status --short -- docs/screenshots | head -n 50 || true
