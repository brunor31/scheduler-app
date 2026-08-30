#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: poc0-burst.sh [count] [concurrency] [lead_seconds]' \
    '' \
    'Environment variables:' \
    '  BASE_URL           API base URL (default: http://localhost:8080)' \
    '  TENANT_ID          Synthetic tenant used by the run (default: poc0-benchmark)' \
    '  POSTGRES_CONTAINER PostgreSQL container name (default: scheduler-postgres)' \
    '  POSTGRES_DB        Database name (default: scheduler)' \
    '  POSTGRES_USER      Database user (default: scheduler)' \
    '  WAIT_TIMEOUT       Seconds to wait after dueAt (default: 300)' \
    '  RESULT_ROOT        Result directory (default: target/experiments/poc0)' \
    '  RUN_NOTES          Free-text note recorded with the run (default: empty)' \
    '' \
    'Example:' \
    '  ./scripts/experiments/poc0-burst.sh 1000 32 120'
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid %s: %s (expected a positive integer)\n' "$name" "$value" >&2
    exit 2
  fi
}

utc_instant_from_epoch() {
  local epoch="$1"
  if date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ' >/dev/null 2>&1; then
    date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  else
    date -u -d "@$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  fi
}

capture_runtime_snapshots() {
  local phase="$1"
  curl --silent --show-error "$BASE_URL/actuator/metrics/process.cpu.usage" \
    >"$RESULT_DIR/process-cpu-$phase.json" || true
  curl --silent --show-error "$BASE_URL/actuator/metrics/jvm.memory.used" \
    >"$RESULT_DIR/jvm-memory-used-$phase.json" || true
  docker stats --no-stream --format \
    'name={{.Name}},cpu={{.CPUPerc}},memory={{.MemUsage}},net_io={{.NetIO}},block_io={{.BlockIO}}' \
    "$POSTGRES_CONTAINER" >"$RESULT_DIR/postgres-stats-$phase.txt" || true
}

metric_value() {
  local metric_name="$1"
  local response
  response="$(curl --silent --show-error "$BASE_URL/actuator/metrics/$metric_name" || true)"
  if [[ "$response" =~ \"value\":([-+0-9.eE]+) ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  else
    printf 'NA'
  fi
}

capture_runtime_series_row() {
  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    "$DELIVERED" \
    "$(metric_value scheduler.timer.queue.size)" \
    "$(metric_value scheduler.delivery.queue.size)" \
    "$(metric_value scheduler.delivery.in.progress)" \
    "$(metric_value scheduler.timer.active.keys)" \
    "$(metric_value hikaricp.connections.active)" \
    "$(metric_value hikaricp.connections.pending)" \
    >>"$RESULT_DIR/runtime-series.csv"
}

prevent_macos_idle_sleep() {
  if [[ "$(uname -s)" != "Darwin" ]] || ! command -v caffeinate >/dev/null 2>&1; then
    return
  fi
  caffeinate -i -w "$$" &
  CAFFEINATE_PID="$!"
  trap 'kill "$CAFFEINATE_PID" 2>/dev/null || true' EXIT
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

COUNT="${1:-100}"
CONCURRENCY="${2:-16}"
LEAD_SECONDS="${3:-120}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-poc0-benchmark}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-scheduler-postgres}"
POSTGRES_DB="${POSTGRES_DB:-scheduler}"
POSTGRES_USER="${POSTGRES_USER:-scheduler}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300}"
RESULT_ROOT="${RESULT_ROOT:-target/experiments/poc0}"
RUN_NOTES="${RUN_NOTES:-}"

require_positive_integer count "$COUNT"
require_positive_integer concurrency "$CONCURRENCY"
require_positive_integer lead_seconds "$LEAD_SECONDS"
require_positive_integer wait_timeout "$WAIT_TIMEOUT"

if [[ ! "$TENANT_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  printf 'TENANT_ID may contain only letters, digits, dot, underscore and hyphen\n' >&2
  exit 2
fi

for command_name in curl docker seq xargs awk sort date tee; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "$command_name" >&2
    exit 2
  fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SQL="$SCRIPT_DIR/poc0-burst-report.sql"
RUN_ID="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
RUN_PREFIX="poc0-$RUN_ID"
RESULT_DIR="$RESULT_ROOT/$RUN_ID"
DUE_EPOCH="$(( $(date +%s) + LEAD_SECONDS ))"
DUE_AT="$(utc_instant_from_epoch "$DUE_EPOCH")"

mkdir -p "$RESULT_DIR"
prevent_macos_idle_sleep

if ! curl --fail --silent --show-error "$BASE_URL/actuator/health" >"$RESULT_DIR/health-before.json"; then
  printf 'Application is not healthy at %s\n' "$BASE_URL" >&2
  exit 1
fi

if ! docker exec "$POSTGRES_CONTAINER" pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null; then
  printf 'PostgreSQL is not ready in container %s\n' "$POSTGRES_CONTAINER" >&2
  exit 1
fi

{
  printf 'run_id=%s\n' "$RUN_ID"
  printf 'run_prefix=%s\n' "$RUN_PREFIX"
  printf 'tenant_id=%s\n' "$TENANT_ID"
  printf 'count=%s\n' "$COUNT"
  printf 'concurrency=%s\n' "$CONCURRENCY"
  printf 'lead_seconds=%s\n' "$LEAD_SECONDS"
  printf 'due_at=%s\n' "$DUE_AT"
  printf 'base_url=%s\n' "$BASE_URL"
  printf 'postgres_container=%s\n' "$POSTGRES_CONTAINER"
  printf 'delivery_workers=%s\n' "${SCHEDULER_TIMER_DELIVERY_WORKERS:-unknown}"
  printf 'delivery_queue_capacity=%s\n' "${SCHEDULER_TIMER_DELIVERY_QUEUE_CAPACITY:-unknown}"
  printf 'run_notes=%s\n' "$RUN_NOTES"
} >"$RESULT_DIR/metadata.properties"

{
  printf 'captured_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  uname -a
  printf '%s\n' 'load_generator_shell_java:'
  java -version 2>&1 || true
  docker version || true
  docker inspect --format 'postgres_image={{.Config.Image}}' "$POSTGRES_CONTAINER" || true
} >"$RESULT_DIR/environment.txt"

printf 'Creating %s schedules with concurrency %s, all due at %s\n' "$COUNT" "$CONCURRENCY" "$DUE_AT"

export BASE_URL TENANT_ID RUN_PREFIX DUE_AT
seq 1 "$COUNT" | xargs -P "$CONCURRENCY" -I '{}' sh -c '
  index="$1"
  schedule_id="${RUN_PREFIX}-${index}"
  body="{\"scheduleId\":\"${schedule_id}\",\"dueAt\":\"${DUE_AT}\",\"payload\":{\"kind\":\"poc0-burst\",\"sequence\":${index}}}"
  http_code="$(curl --silent --show-error --output /dev/null --write-out "%{http_code}" \
    --connect-timeout 5 --max-time 30 \
    -X POST "${BASE_URL}/v1/tenants/${TENANT_ID}/schedules" \
    -H "Content-Type: application/json" \
    --data-binary "$body" || printf 000)"
  printf "%s,%s\n" "$index" "$http_code"
' sh '{}' >"$RESULT_DIR/responses-unsorted.csv"

sort -t, -k1,1n "$RESULT_DIR/responses-unsorted.csv" >"$RESULT_DIR/responses.csv"
ACCEPTED="$(awk -F, '$2 == 201 { count++ } END { print count + 0 }' "$RESULT_DIR/responses.csv")"
printf 'accepted=%s\n' "$ACCEPTED" >>"$RESULT_DIR/metadata.properties"

if [[ "$ACCEPTED" -ne "$COUNT" ]]; then
  printf 'Only %s of %s requests returned HTTP 201. See %s\n' "$ACCEPTED" "$COUNT" "$RESULT_DIR/responses.csv" >&2
  exit 1
fi

REMAINING_BEFORE_DUE="$(( DUE_EPOCH - $(date +%s) ))"
printf 'remaining_before_due_seconds=%s\n' "$REMAINING_BEFORE_DUE" >>"$RESULT_DIR/metadata.properties"
if [[ "$REMAINING_BEFORE_DUE" -lt 10 ]]; then
  printf 'Invalid run: load completed with only %s seconds before dueAt. Increase lead_seconds.\n' "$REMAINING_BEFORE_DUE" >&2
  exit 1
fi

curl --silent --show-error "$BASE_URL/actuator/metrics/scheduler.timer.queue.size" \
  >"$RESULT_DIR/timer-queue-after-load.json" || true
capture_runtime_snapshots after-load

printf 'Load complete with %s seconds remaining before dueAt. Waiting for delivery...\n' "$REMAINING_BEFORE_DUE"
DEADLINE_EPOCH="$(( DUE_EPOCH + WAIT_TIMEOUT ))"
DELIVERED=0
printf '%s\n' 'captured_at,delivered,timer_queue,delivery_queue,in_progress,active_keys,hikari_active,hikari_pending' \
  >"$RESULT_DIR/runtime-series.csv"

while [[ "$(date +%s)" -le "$DEADLINE_EPOCH" ]]; do
  DELIVERED="$(docker exec "$POSTGRES_CONTAINER" psql -Atq \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -c "SELECT count(*) FROM schedule WHERE tenant_id = '$TENANT_ID' AND schedule_id LIKE '$RUN_PREFIX-%' AND state = 'DELIVERED';")"
  if [[ "$(date +%s)" -ge "$(( DUE_EPOCH - 2 ))" ]]; then
    capture_runtime_series_row
  fi
  printf '\rDelivered %s/%s' "$DELIVERED" "$COUNT"
  if [[ "$DELIVERED" -eq "$COUNT" ]]; then
    break
  fi
  sleep 1
done
printf '\n'

docker exec -i "$POSTGRES_CONTAINER" psql --csv -q \
  -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v tenant="$TENANT_ID" -v run_prefix="$RUN_PREFIX-%" \
  -f - <"$REPORT_SQL" | tee "$RESULT_DIR/report.csv"

curl --silent --show-error "$BASE_URL/actuator/metrics/scheduler.schedule.lag" \
  >"$RESULT_DIR/schedule-lag-metric.json" || true
curl --silent --show-error "$BASE_URL/actuator/metrics/scheduler.delivery.duration" \
  >"$RESULT_DIR/delivery-duration-metric.json" || true
curl --silent --show-error "$BASE_URL/actuator/metrics/scheduler.schedules.delivered" \
  >"$RESULT_DIR/delivered-metric.json" || true
capture_runtime_snapshots after-delivery

if [[ "$DELIVERED" -ne "$COUNT" ]]; then
  printf 'Timed out with %s/%s delivered. Partial results are in %s\n' "$DELIVERED" "$COUNT" "$RESULT_DIR" >&2
  exit 1
fi

printf 'Experiment completed. Results: %s\n' "$RESULT_DIR"
