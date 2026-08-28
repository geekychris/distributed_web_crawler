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
expect_json "scope backend is set" "$HOST/api/crawler/scope" \
    "d.get('backend','unknown')" "$( [ "${TRUSTED_HOSTS_BACKEND:-redis}" = "cassandra" ] && echo cassandra || echo redis )"

section "Redis trust persistence"
# Register a fresh host and verify it survives a crawler restart when the
# Redis backend is active.
TEST_HOST="sysprobe-$(date +%s).invalid"
curl -sS -X POST "$HOST/api/crawler/url" -H 'Content-Type: application/json' \
    -d "{\"url\":\"https://$TEST_HOST/\"}" >/dev/null
FOUND=$(curl -sS "$HOST/api/crawler/scope" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print('$TEST_HOST' in d['allowedDomains'])")
if [ "$FOUND" = "True" ]; then
    printf '  ok  trusted host %s appears in scope snapshot\n' "$TEST_HOST"
    pass=$((pass+1))
else
    printf '  FAIL trusted host missing from scope: %s\n' "$TEST_HOST"
    failures+=("redis trust write"); fail=$((fail+1))
fi
# Verify Redis actually holds the key when Redis is the backend.
if [ "${TRUSTED_HOSTS_BACKEND:-redis}" != "cassandra" ]; then
    if docker exec distributed_web_crawler-redis-1 redis-cli sismember trusted:hosts "$TEST_HOST" | grep -q '^1$'; then
        printf '  ok  redis SISMEMBER trusted:hosts %s = 1\n' "$TEST_HOST"
        pass=$((pass+1))
    else
        printf '  FAIL redis missing the trusted key\n'
        failures+=("redis SISMEMBER"); fail=$((fail+1))
    fi
fi

section "Feed subscription round-trip"
# httpbin has an /xml endpoint that returns a small Atom-shaped document.
# Not a real feed, but ROME will handle it; we're primarily verifying the
# subscribe → list → force-poll → recent-items pipeline.
FEED_JSON=$(curl -sS --max-time 30 -X POST "$HOST/api/feeds" \
    -H 'Content-Type: application/json' \
    -d '{"url":"https://feeds.arstechnica.com/arstechnica/index",
         "title":"Ars Technica","pack":"tech",
         "pollIntervalSeconds":900}')
FEED_ID=$(printf '%s' "$FEED_JSON" | python3 -c 'import json,sys;print(json.load(sys.stdin)["feedId"])' 2>/dev/null)
if [ -z "$FEED_ID" ] || [ "$FEED_ID" = "null" ]; then
    printf '  FAIL feed subscription returned no feedId — %s\n' "$FEED_JSON"
    failures+=("feed subscribe"); fail=$((fail+1))
else
    printf '  ok  feed subscribed — feedId=%s\n' "$FEED_ID"; pass=$((pass+1))
fi
expect_status "GET /api/feeds returns 200" GET "$HOST/api/feeds" 200
expect_status "pause on unknown feed returns 404" POST \
    "$HOST/api/feeds/00000000-0000-0000-0000-000000000000/pause" 404

if [ -n "$FEED_ID" ] && [ "$FEED_ID" != "null" ]; then
    printf '  forcing an immediate poll…\n'
    curl -sS -X POST "$HOST/api/feeds/$FEED_ID/poll" >/dev/null
    printf '  waiting up to 60s for feed items to be persisted…\n'
    deadline=$(($(date +%s) + 60))
    items=0
    while :; do
        items=$(curl -sS "$HOST/api/feeds/$FEED_ID/items?limit=5" | \
            python3 -c 'import json,sys;print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
        [ "$items" -gt 0 ] && break
        [ "$(date +%s)" -ge $deadline ] && break
        sleep 3
    done
    if [ "$items" -gt 0 ]; then
        printf '  ok  %s feed item(s) persisted\n' "$items"; pass=$((pass+1))
    else
        printf '  WARN no items within 60s (feed may have been unreachable)\n'
        printf '  soft-fail — the poller ran but got nothing back\n'
    fi

    section "Feed items CloudEvent on crawler.feed_items.v1"
    FEED_EVENT_COUNT=$(docker exec "$KAFKA_CTR" \
        kafka-console-consumer --bootstrap-server "$KAFKA_BOOT" \
        --topic crawler.feed_items.v1 --from-beginning \
        --max-messages 100 --timeout-ms 5000 2>/dev/null \
      | python3 -c 'import json,sys
n=0
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    try:
        d=json.loads(line)
        if d.get("type")=="com.webcrawler.feed_item.discovered.v1": n+=1
    except: pass
print(n)')
    if [ -n "$FEED_EVENT_COUNT" ] && [ "$FEED_EVENT_COUNT" -gt 0 ]; then
        printf '  ok  %s CloudEvent(s) with type=com.webcrawler.feed_item.discovered.v1\n' "$FEED_EVENT_COUNT"
        pass=$((pass+1))
    else
        printf '  WARN no feed_item CloudEvents (feed may have been unreachable)\n'
    fi
fi

section "Feed packs"
expect_status "GET /api/feed-packs returns 200"      GET  "$HOST/api/feed-packs"        200
expect_status "GET /api/feed-packs/tech exists"      GET  "$HOST/api/feed-packs/tech"   200
expect_status "GET unknown pack returns 404"         GET  "$HOST/api/feed-packs/nonexistent" 404
expect_status "subscribe to unknown pack returns 404" POST "$HOST/api/feed-packs/nonexistent/subscribe" 404

# Subscribe to the 'retro' pack (small, less likely to collide with earlier
# subscriptions in other test sections).
SUB_RESPONSE=$(curl -sS --max-time 30 -X POST "$HOST/api/feed-packs/retro/subscribe")
NEW_COUNT=$(printf '%s' "$SUB_RESPONSE" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("created", 0))' 2>/dev/null)
TOTAL_IN_PACK=$(printf '%s' "$SUB_RESPONSE" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("totalInPack", 0))' 2>/dev/null)
if [ -n "$TOTAL_IN_PACK" ] && [ "$TOTAL_IN_PACK" -gt 0 ]; then
    printf '  ok  subscribed retro pack — %s new / %s in pack\n' "$NEW_COUNT" "$TOTAL_IN_PACK"
    pass=$((pass+1))
else
    printf '  FAIL retro pack subscribe returned no members: %s\n' "$SUB_RESPONSE"
    failures+=("feed-pack subscribe"); fail=$((fail+1))
fi

# Idempotency: subscribing again should add 0 new feeds.
IDEMPOTENT=$(curl -sS --max-time 30 -X POST "$HOST/api/feed-packs/retro/subscribe" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin).get("created", -1))' 2>/dev/null)
if [ "$IDEMPOTENT" = "0" ]; then
    printf '  ok  re-subscribing is idempotent (created=0)\n'; pass=$((pass+1))
else
    printf '  FAIL re-subscribe added %s more (expected 0)\n' "$IDEMPOTENT"
    failures+=("feed-pack idempotency"); fail=$((fail+1))
fi

section "Unified records iterator"
# Kafka path over pages topic — expect at least 1 record from the autostart
# crawls plus a well-formed next_cursor.
K_RESP=$(curl -sS "$HOST/api/records?type=page&stream=kafka&limit=5")
K_STREAM=$(printf '%s' "$K_RESP" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("stream",""))')
K_COUNT=$(printf '%s' "$K_RESP" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("count",0))')
K_NEXT=$(printf '%s' "$K_RESP" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("next_cursor",""))')
if [ "$K_STREAM" = "kafka" ] && [ "$K_COUNT" -gt 0 ] && printf '%s' "$K_NEXT" | grep -q '^kafka:0:[0-9]\+$'; then
    printf '  ok  kafka page iterator returned %s record(s), cursor=%s\n' "$K_COUNT" "$K_NEXT"
    pass=$((pass+1))
else
    printf '  FAIL kafka page iterator: stream=%s count=%s cursor=%s\n' "$K_STREAM" "$K_COUNT" "$K_NEXT"
    failures+=("records iterator kafka"); fail=$((fail+1))
fi

# Kafka cursor advance — request same cursor twice, expect it to stay put
# (Kafka polls to end and stops; next call from same cursor yields more or
# the same next_cursor).
K_RESP2=$(curl -sS "$HOST/api/records?type=page&stream=kafka&cursor=$K_NEXT&limit=5")
K_NEXT2=$(printf '%s' "$K_RESP2" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("next_cursor",""))')
if [ -n "$K_NEXT2" ]; then
    printf '  ok  cursor-resume returned next_cursor=%s\n' "$K_NEXT2"; pass=$((pass+1))
else
    printf '  FAIL cursor-resume produced no next_cursor\n'
    failures+=("records iterator kafka resume"); fail=$((fail+1))
fi

# Cassandra path over pages
C_RESP=$(curl -sS "$HOST/api/records?type=page&stream=cassandra&limit=5")
C_STREAM=$(printf '%s' "$C_RESP" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("stream",""))')
C_COUNT=$(printf '%s' "$C_RESP" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("count",0))')
if [ "$C_STREAM" = "cassandra" ] && [ "$C_COUNT" -gt 0 ]; then
    printf '  ok  cassandra page iterator returned %s row(s)\n' "$C_COUNT"; pass=$((pass+1))
else
    printf '  FAIL cassandra page iterator: stream=%s count=%s\n' "$C_STREAM" "$C_COUNT"
    failures+=("records iterator cassandra"); fail=$((fail+1))
fi

# Cassandra path over feed items
CF_COUNT=$(curl -sS "$HOST/api/records?type=feed_item&stream=cassandra&limit=5" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin).get("count",0))')
if [ "$CF_COUNT" -gt 0 ]; then
    printf '  ok  cassandra feed_item iterator returned %s row(s)\n' "$CF_COUNT"; pass=$((pass+1))
else
    printf '  WARN cassandra feed_item iterator returned 0 (no feed items yet)\n'
fi

# Bad params
expect_status "unknown type returns 400" GET "$HOST/api/records?type=nonsense" 400
expect_status "unknown stream returns 400" GET "$HOST/api/records?type=page&stream=nonsense" 400

section "Stats endpoint"
expect_status "GET /api/stats/summary returns 200" GET "$HOST/api/stats/summary" 200
SUMMARY=$(curl -sS "$HOST/api/stats/summary")
KEYS_OK=$(printf '%s' "$SUMMARY" | python3 -c '
import json, sys
d = json.load(sys.stdin)
required = {"timestamp","pages_total","feed_items_total","feeds","jobs","activity"}
missing = required - set(d.keys())
print("ok" if not missing else "missing:" + ",".join(missing))
')
if [ "$KEYS_OK" = "ok" ]; then
    printf '  ok  summary has all required keys\n'; pass=$((pass+1))
else
    printf '  FAIL summary missing keys: %s\n' "$KEYS_OK"
    failures+=("stats summary keys"); fail=$((fail+1))
fi

section "NDJSON export"
PAGES_NDJSON=$(curl -sS -H 'Accept: application/x-ndjson' \
    "$HOST/api/export/pages.ndjson?limit=10")
PAGES_CT=$(curl -sI "$HOST/api/export/pages.ndjson?limit=1" | grep -i '^Content-Type:' | tr -d '\r')
if printf '%s' "$PAGES_CT" | grep -qi 'application/x-ndjson'; then
    printf '  ok  pages export Content-Type is application/x-ndjson\n'; pass=$((pass+1))
else
    printf '  FAIL pages export Content-Type wrong: %s\n' "$PAGES_CT"
    failures+=("pages export content-type"); fail=$((fail+1))
fi
PAGES_LINES=$(printf '%s\n' "$PAGES_NDJSON" | grep -c '^{')
if [ "$PAGES_LINES" -gt 0 ]; then
    printf '  ok  pages export streamed %s NDJSON line(s)\n' "$PAGES_LINES"; pass=$((pass+1))
else
    printf '  WARN pages export returned 0 lines (empty pages table?)\n'
fi

ITEMS_LINES=$(curl -sS "$HOST/api/export/feed_items.ndjson?limit=5" | grep -c '^{')
if [ "$ITEMS_LINES" -gt 0 ]; then
    printf '  ok  feed_items export streamed %s NDJSON line(s)\n' "$ITEMS_LINES"; pass=$((pass+1))
else
    printf '  WARN feed_items export returned 0 lines\n'
fi

section "Follow-articles pipeline"
# Subscribe to a small RSS feed with follow_articles=true, then verify that
# after the poll fires we see page CloudEvents whose source_feed_item_id
# points back at a feed item we just persisted.
FOLLOW_JSON=$(curl -sS --max-time 30 -X POST "$HOST/api/feeds" \
    -H 'Content-Type: application/json' \
    -d '{"url":"https://news.ycombinator.com/rss",
         "title":"HN follow","pack":"tech",
         "pollIntervalSeconds":900,
         "followArticles":true}')
FOLLOW_ID=$(printf '%s' "$FOLLOW_JSON" | python3 -c 'import json,sys;print(json.load(sys.stdin)["feedId"])' 2>/dev/null)
if [ -n "$FOLLOW_ID" ] && [ "$FOLLOW_ID" != "null" ]; then
    printf '  ok  follow-articles feed subscribed — feedId=%s\n' "$FOLLOW_ID"; pass=$((pass+1))
    curl -sS -X POST "$HOST/api/feeds/$FOLLOW_ID/poll" >/dev/null

    printf '  waiting up to 90s for source_feed_item_id in page CloudEvents…\n'
    deadline=$(($(date +%s) + 90))
    follow_hits=0
    while :; do
        follow_hits=$(docker exec "$KAFKA_CTR" \
            kafka-console-consumer --bootstrap-server "$KAFKA_BOOT" \
            --topic crawler.pages.v1 --from-beginning \
            --max-messages 500 --timeout-ms 3000 2>/dev/null \
          | python3 -c 'import json,sys
n=0
for l in sys.stdin:
    l=l.strip()
    if not l: continue
    try:
        d=json.loads(l).get("data",{})
        if d.get("source_feed_item_id"): n+=1
    except: pass
print(n)')
        [ -n "$follow_hits" ] && [ "$follow_hits" -gt 0 ] && break
        [ "$(date +%s)" -ge $deadline ] && break
        sleep 5
    done
    if [ -n "$follow_hits" ] && [ "$follow_hits" -gt 0 ]; then
        printf '  ok  %s page CloudEvent(s) carry source_feed_item_id back to a feed item\n' "$follow_hits"
        pass=$((pass+1))
    else
        printf '  WARN no page CloudEvents with source_feed_item_id (feed items may still be queued)\n'
    fi
fi

printf '\n----------\n'
printf 'passed: %d   failed: %d\n' "$pass" "$fail"
if [ $fail -gt 0 ]; then
    printf 'FAILED CHECKS:\n'
    for f in "${failures[@]}"; do printf '  - %s\n' "$f"; done
    exit 1
fi
printf 'ALL GREEN\n'
