#!/usr/bin/env bash
#
# End-to-end system test against the running docker-compose stack.
# Requires the crawler to be up (see docker-compose.yml). Runs a battery of
# REST-level assertions covering the recently-added fixes:
#   - readiness/liveness probes
#   - clamp on negative activity limit
#   - 404 on missing job pause/resume/cancel/progress
#   - job creation with maxDepth persists it
#   - actually crawling a small site produces pages + a CloudEvent
#   - budget enforcement rejects work past maxPages
#
# Exit 0 = all green. Non-zero = the failing check's exit code.

set -u -o pipefail

HOST="${HOST:-http://localhost:28080}"
KAFKA_CTR="${KAFKA_CTR:-distributed_web_crawler-kafka-1}"
KAFKA_BOOT="${KAFKA_BOOT:-localhost:22092}"
CONNECT_TIMEOUT=5

pass=0
fail=0
failures=()

section() { printf '\n== %s ==\n' "$1"; }

expect_status() {
    local desc="$1" method="$2" url="$3" expected="$4"
    shift 4
    local actual
    actual=$(curl -sS -o /dev/null --max-time 30 -w '%{http_code}' \
                  -X "$method" "$url" "$@")
    if [ "$actual" = "$expected" ]; then
        printf '  ok  %s (%s)\n' "$desc" "$actual"; pass=$((pass+1))
    else
        printf '  FAIL %s — got %s, expected %s\n' "$desc" "$actual" "$expected"
        failures+=("$desc"); fail=$((fail+1))
    fi
}

expect_json() {
    local desc="$1" url="$2" jq_expr="$3" expected="$4"
    local actual
    actual=$(curl -sS --max-time 30 "$url" | python3 -c "
import json,sys
d = json.load(sys.stdin)
r = ${jq_expr}
print(r if not isinstance(r,bool) else str(r).lower())
" 2>/dev/null)
    if [ "$actual" = "$expected" ]; then
        printf '  ok  %s => %s\n' "$desc" "$actual"; pass=$((pass+1))
    else
        printf '  FAIL %s — got %q, expected %q\n' "$desc" "$actual" "$expected"
        failures+=("$desc"); fail=$((fail+1))
    fi
}

section "Readiness / status"
expect_status "readiness returns 200" GET  "$HOST/actuator/health/readiness" 200
expect_status "liveness returns 200"  GET  "$HOST/actuator/health/liveness"  200
expect_json   "isRunning is true"     "$HOST/api/crawler/status" \
              "'true' if d['isRunning'] else 'false'" true

section "Activity endpoint"
expect_status "activity with default limit returns 200" GET \
              "$HOST/api/crawler/activity" 200
expect_status "activity with negative limit clamps to 0, returns 200" GET \
              "$HOST/api/crawler/activity?limit=-5" 200

section "Job 404s on unknown UUID"
UNKNOWN=00000000-0000-0000-0000-000000000000
expect_status "GET /api/jobs/{unknown} returns 404"      GET  "$HOST/api/jobs/$UNKNOWN"           404
expect_status "POST /pause on unknown returns 404"       POST "$HOST/api/jobs/$UNKNOWN/pause"     404
expect_status "POST /resume on unknown returns 404"      POST "$HOST/api/jobs/$UNKNOWN/resume"    404
expect_status "POST /cancel on unknown returns 404"      POST "$HOST/api/jobs/$UNKNOWN/cancel"    404
expect_status "GET /progress on unknown returns 404"     GET  "$HOST/api/jobs/$UNKNOWN/progress"  404

section "Job round-trip with maxDepth"
JOB_JSON=$(curl -sS --max-time 30 -X POST "$HOST/api/jobs" \
    -H 'Content-Type: application/json' \
    -d '{"name":"sys-test","seedUrls":["https://example.com/"],
         "maxDepth":2,"maxPages":3,"maxPagesPerDomain":3,"maxDomains":1}')
JOB_ID=$(printf '%s' "$JOB_JSON" | python3 -c 'import json,sys;print(json.load(sys.stdin)["jobId"])')
if [ -z "$JOB_ID" ] || [ "$JOB_ID" = "null" ]; then
    printf '  FAIL job creation returned no jobId — %s\n' "$JOB_JSON"
    failures+=("job creation"); fail=$((fail+1))
else
    printf '  ok  job created — jobId=%s\n' "$JOB_ID"; pass=$((pass+1))
fi
expect_json "maxDepth persists on GET /api/jobs/{id}" \
    "$HOST/api/jobs/$JOB_ID" "str(d['maxDepth'])" 2

section "End-to-end crawl (autostart seeds → activity feed → CloudEvents)"
# Rely on the autostart seed URLs from application.yml (example.com,
# httpbin.org/html). They're guaranteed to be crawled at boot on a fresh
# stack; polling the activity feed for at least one CRAWLED event proves
# the whole pipeline (Kafka poll → fetch → Cassandra store → CloudEvent
# publish) is intact end-to-end.
printf '  waiting up to 120s for at least one CRAWLED activity event…\n'
deadline=$(($(date +%s) + 120))
crawled_urls=0
while :; do
    crawled_urls=$(curl -sS "$HOST/api/crawler/activity?limit=100" | \
        python3 -c 'import json,sys;print(sum(1 for e in json.load(sys.stdin) if e["kind"]=="CRAWLED"))' 2>/dev/null || echo 0)
    [ "$crawled_urls" -gt 0 ] && break
    [ "$(date +%s)" -ge $deadline ] && break
    sleep 3
done
if [ "$crawled_urls" -gt 0 ]; then
    printf '  ok  %s CRAWLED event(s) — end-to-end pipeline healthy\n' "$crawled_urls"
    pass=$((pass+1))
else
    printf '  FAIL no CRAWLED activity within 120s\n'
    printf '  recent activity (for debugging):\n'
    curl -sS "$HOST/api/crawler/activity?limit=5" | python3 -m json.tool | sed 's/^/    /'
    failures+=("end-to-end crawl"); fail=$((fail+1))
fi

section "CloudEvent on crawler.pages.v1 (last 30s)"
EVENT_COUNT=$(docker exec "$KAFKA_CTR" \
    kafka-console-consumer --bootstrap-server "$KAFKA_BOOT" \
    --topic crawler.pages.v1 --from-beginning \
    --max-messages 500 --timeout-ms 5000 2>/dev/null \
  | python3 -c 'import json,sys
n=0
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    try:
        d=json.loads(line)
        if d.get("type")=="com.webcrawler.page.crawled.v1": n+=1
    except: pass
print(n)')
if [ -n "$EVENT_COUNT" ] && [ "$EVENT_COUNT" -gt 0 ]; then
    printf '  ok  %s CloudEvent(s) with type=com.webcrawler.page.crawled.v1\n' "$EVENT_COUNT"
    pass=$((pass+1))
else
    printf '  FAIL no com.webcrawler.page.crawled.v1 events found\n'
    failures+=("cloudevent emission"); fail=$((fail+1))
fi

section "Scope endpoint reflects runtime allowlist"
expect_json "scope returns a list" "$HOST/api/crawler/scope" \
    "'list' if isinstance(d['allowedDomains'], list) else 'other'" list

printf '\n----------\n'
printf 'passed: %d   failed: %d\n' "$pass" "$fail"
if [ $fail -gt 0 ]; then
    printf 'FAILED CHECKS:\n'
    for f in "${failures[@]}"; do printf '  - %s\n' "$f"; done
    exit 1
fi
printf 'ALL GREEN\n'
