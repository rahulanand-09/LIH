#!/usr/bin/env bash
# Run the L1 worker against a local Temporal, a local Mongo and WireMock stubs for Core and Axis.
#
#   dev/l1-local/l1-local.sh infra          # Temporal dev server (UI :8233) + Mongo on :27018
#   dev/l1-local/l1-local.sh stubs          # WireMock on :8089 serving dev/l1-local/wiremock
#   dev/l1-local/l1-local.sh app            # LIH app with l1.enabled and the L1Probe workflow
#                                           # (L1_REGISTRY=mongo: run published versions from lih_config)
#   dev/l1-local/l1-local.sh publish        # validate + publish src/main/resources/l1-config to local lih_config
#   dev/l1-local/l1-local.sh config ARGS    # any config CLI command, e.g. config status
#   dev/l1-local/l1-local.sh probe LR-OK-1 [runNo] [recipeVersion]  # RunTask(axis.precheck.dedupe)
#   dev/l1-local/l1-local.sh precheck LR-OK-1 [Y|N] [pollSeconds]  # whole Axis precheck in ONE workflow + timeline
#   dev/l1-local/l1-local.sh timeline <workflowId>                  # readable history of any workflow
#   dev/l1-local/l1-local.sh run-recipe axis.precheck.timble '{"loanRequestId":"LR-OK-1","runNo":1,"loanProcess":"fresh"}'
#
# Scenarios are picked by the loanRequestId prefix: LR-OK, LR-REJECT, LR-503, LR-500, LR-NOCORE
# (see docs/l1-local-testing.md). Point CORE_BASEURL / AXIS_PRECHECK_BASEURL and the two secrets at real
# endpoints to skip the stubs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STUB_PORT="${STUB_PORT:-8089}"
L1_REGISTRY="${L1_REGISTRY:-classpath}"
export L1_CONFIG_MONGO_URI="${L1_CONFIG_MONGO_URI:-mongodb://localhost:27018}"
WIREMOCK_JAR="${WIREMOCK_JAR:-$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar}"

export CORE_BASEURL="${CORE_BASEURL:-http://localhost:$STUB_PORT}"
export CORE_TOKEN="${CORE_TOKEN:-stub-core-token}"
export AXIS_PRECHECK_BASEURL="${AXIS_PRECHECK_BASEURL:-http://localhost:$STUB_PORT}"
export AXIS_PRECHECK_API_KEY="${AXIS_PRECHECK_API_KEY:-stub-axis-key}"
export RUPEEKWEB_BASEURL="${RUPEEKWEB_BASEURL:-http://localhost:$STUB_PORT}"
export RUPEEKWEB_ADMIN_TOKEN="${RUPEEKWEB_ADMIN_TOKEN:-stub-rupeekweb-token}"

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

use_java() {
  java_args=()
  if JAVA_HOME="$(/usr/libexec/java_home -v 21 --failfast 2>/dev/null)"; then
    export JAVA_HOME
  else
    # JDK 21 not installed yet: build on 17 (the L1 code uses no 21-only features).
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    java_args+=("-Djava.version=17")
  fi
}

app() {
  use_java
  cd "$ROOT"
  exec mvn -B ${java_args[@]+"${java_args[@]}"} spring-boot:run \
    -Dspring-boot.run.arguments="--l1.enabled=true --l1.env=local --l1.dev.probe-workflow=true --l1.dev.reference-workflow=true --l1.registry.mode=$L1_REGISTRY"
}

config() {
  use_java
  cd "$ROOT"
  local quoted="" a
  for a in "$@"; do quoted+=" \"$a\""; done   # keep arguments with spaces (e.g. --reason) together
  mvn -B -q ${java_args[@]+"${java_args[@]}"} compile exec:java -Dexec.args="$quoted"
}

probe() {
  local lr="${1:?usage: probe <loanRequestId> [runNo] [recipeVersion]}" run="${2:-1}" ver="${3:-1}"
  temporal workflow execute --type L1Probe --task-queue lih-l1 \
    --workflow-id "l1-probe-$lr-r$run-$(date +%s)" \
    --input "{\"recipeRef\":\"axis.precheck.dedupe\",\"recipeVersion\":$ver,\"caseId\":\"l1-probe-$lr\",\"runNo\":$run,\"inputs\":{\"loanRequestId\":\"$lr\",\"runNo\":$run,\"loanProcess\":\"fresh\"}}"
}

# The whole Axis precheck in ONE workflow (the dev reference L2): precheck LR-OK-1 [Y|N name_match] [pollSeconds] [runNo]
precheck() {
  local lr="${1:?usage: precheck <loanRequestId> [Y|N] [pollSeconds] [runNo]}" match="${2:-Y}" poll="${3:-10}" run="${4:-1}"
  local prefix customer wf
  prefix="$(echo "$lr" | sed -E 's/^LR-([A-Z0-9]+).*/\1/')"; customer="CUST-$prefix"   # matches the Core stubs
  wf="axis-precheck-$lr-r$run"
  curl -s -X POST "localhost:$STUB_PORT/__admin/scenarios/reset" >/dev/null || true      # first status poll: WIP
  echo "▶ starting workflow $wf (queue l2-reference); watch the app terminal for [L2 …] and [L1 …] logs"
  temporal workflow start --type ReferenceAxisPrecheckWorkflow --task-queue l2-reference --workflow-id "$wf" \
    --input "{\"loanRequestId\":\"$lr\",\"loanProcess\":\"fresh\",\"runNo\":$run,\"pollEverySeconds\":$poll}" >/dev/null
  sleep 3
  echo "✉ sending the Timble callback (customer $customer, name_match=$match), as L2's callback endpoint would"
  temporal workflow signal --workflow-id "$wf" --name timbleCallback \
    --input "{\"product_id\":\"P1\",\"customer_id\":\"$customer\",\"timble_status\":\"COMPLETED\",\"name_match\":\"$match\",\"aadhar_match\":\"Y\"}" >/dev/null
  echo "… waiting for the result (status is polled every ${poll}s)"
  temporal workflow result --workflow-id "$wf" | grep -E "Status|Result " || true
  echo
  python3 "$ROOT/dev/l1-local/timeline.py" "$wf"
  echo
  echo "UI: http://localhost:8233/namespaces/default/workflows/$wf"
}

timeline() {
  python3 "$ROOT/dev/l1-local/timeline.py" "${1:?usage: timeline <workflowId>}"
}

# Any recipe: run-recipe axis.precheck.status '{"loanRequestId":"LR-OK-1"}' [recipeVersion]
run_recipe() {
  local recipe="${1:?usage: run-recipe <recipe> '<inputs json>' [recipeVersion]}" inputs="${2:?inputs json}" ver="${3:-1}"
  temporal workflow execute --type L1Probe --task-queue lih-l1 \
    --workflow-id "l1-probe-$recipe-$(date +%s)" \
    --input "{\"recipeRef\":\"$recipe\",\"recipeVersion\":$ver,\"caseId\":\"l1-probe-$recipe\",\"runNo\":1,\"inputs\":$inputs}"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  infra) infra ;;
  stubs) stubs ;;
  app) app ;;
  publish) config publish --env local --reason "${REASON:-local publish}" ;;
  config) config "$@" ;;
  probe) probe "$@" ;;
  run-recipe) run_recipe "$@" ;;
  precheck) precheck "$@" ;;
  timeline) timeline "$@" ;;
  *) sed -n '2,18p' "$0"; exit 1 ;;
esac
