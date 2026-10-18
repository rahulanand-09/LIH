#!/usr/bin/env bash
# Run the L1 worker against a local Temporal, a local Mongo and WireMock stubs for Core and Axis.
#
#   dev/l1-local/l1-local.sh infra          # Temporal dev server (UI :8233) + Mongo on :27018
#   dev/l1-local/l1-local.sh stubs          # WireMock on :8089 serving dev/l1-local/wiremock
#   dev/l1-local/l1-local.sh app            # LIH app with l1.enabled and the L1Probe workflow
#   dev/l1-local/l1-local.sh probe LR-OK-1  # start RunTask(axis.precheck.dedupe) for that loanRequestId
#
# Scenarios are picked by the loanRequestId prefix: LR-OK, LR-REJECT, LR-503, LR-500, LR-NOCORE
# (see docs/l1-local-testing.md). Point CORE_BASEURL / AXIS_PRECHECK_BASEURL and the two secrets at real
# endpoints to skip the stubs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STUB_PORT="${STUB_PORT:-8089}"
WIREMOCK_JAR="${WIREMOCK_JAR:-$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar}"

export CORE_BASEURL="${CORE_BASEURL:-http://localhost:$STUB_PORT}"
export CORE_TOKEN="${CORE_TOKEN:-stub-core-token}"
export AXIS_PRECHECK_BASEURL="${AXIS_PRECHECK_BASEURL:-http://localhost:$STUB_PORT}"
export AXIS_PRECHECK_API_KEY="${AXIS_PRECHECK_API_KEY:-stub-axis-key}"

infra() {
  if ! docker ps --format '{{.Names}}' | grep -qx l1-mongo; then
    docker rm -f l1-mongo >/dev/null 2>&1 || true
    docker run -d --name l1-mongo -p 27018:27017 mongo:7.0 >/dev/null
    echo "Mongo started on localhost:27018 (container l1-mongo)"
  fi
  echo "Starting Temporal dev server (gRPC :7233, UI http://localhost:8233); Ctrl-C to stop"
  exec temporal server start-dev --db-filename "$ROOT/target/l1-temporal.db"
}

stubs() {
  [ -f "$WIREMOCK_JAR" ] || { echo "WireMock jar not found; run 'mvn test-compile' once or set WIREMOCK_JAR"; exit 1; }
  exec java -jar "$WIREMOCK_JAR" --port "$STUB_PORT" --root-dir "$ROOT/dev/l1-local/wiremock" --verbose
}

app() {
  local java_args=()
  if JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    export JAVA_HOME
  else
    # JDK 21 not installed yet: build on 17 (the L1 code uses no 21-only features).
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    java_args+=("-Djava.version=17")
  fi
  cd "$ROOT"
  exec mvn -B ${java_args[@]+"${java_args[@]}"} spring-boot:run \
    -Dspring-boot.run.arguments="--l1.enabled=true --l1.env=local --l1.dev.probe-workflow=true"
}

probe() {
  local lr="${1:?usage: probe <loanRequestId> [runNo]}" run="${2:-1}"
  temporal workflow execute --type L1Probe --task-queue lih-l1 \
    --workflow-id "l1-probe-$lr-r$run-$(date +%s)" \
    --input "{\"recipeRef\":\"axis.precheck.dedupe\",\"recipeVersion\":1,\"caseId\":\"l1-probe-$lr\",\"runNo\":$run,\"inputs\":{\"loanRequestId\":\"$lr\",\"runNo\":$run}}"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  infra) infra ;;
  stubs) stubs ;;
  app) app ;;
  probe) probe "$@" ;;
  *) sed -n '2,11p' "$0"; exit 1 ;;
esac
