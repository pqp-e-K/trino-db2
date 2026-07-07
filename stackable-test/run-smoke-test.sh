#!/usr/bin/env bash
# Smoke test for the db2 connector inside a real (Stackable) Trino instance.
# Runs queries via the Trino REST API — no CLI required.
set -euo pipefail

TRINO_URL="${TRINO_URL:-http://localhost:8080}"

run_sql() {
    local query="$1"
    echo
    echo "trino> ${query}"
    python3 - "$TRINO_URL" "$query" <<'EOF'
import json
import sys
import time
import urllib.request

url, query = sys.argv[1], sys.argv[2]
# PARAMETRIC_DATETIME: without it Trino downgrades timestamps to millisecond precision for legacy clients
headers = {"X-Trino-User": "smoke-test", "X-Trino-Client-Capabilities": "PARAMETRIC_DATETIME"}

req = urllib.request.Request(url + "/v1/statement", data=query.encode(), headers=headers)
resp = json.load(urllib.request.urlopen(req))

rows, columns = [], None
while True:
    if "error" in resp:
        failure = resp["error"]
        print("QUERY FAILED: %s" % failure.get("message"), file=sys.stderr)
        sys.exit(1)
    columns = resp.get("columns") or columns
    rows.extend(resp.get("data") or [])
    next_uri = resp.get("nextUri")
    if not next_uri:
        break
    time.sleep(0.2)
    resp = json.load(urllib.request.urlopen(urllib.request.Request(next_uri, headers=headers)))

if columns:
    print(" | ".join(c["name"] for c in columns))
    print("-" * 40)
for row in rows:
    print(" | ".join(str(v) for v in row))
EOF
}

echo "Waiting for Trino at ${TRINO_URL} to become ready..."
for _ in $(seq 1 120); do
    if curl -sf "${TRINO_URL}/v1/info" 2>/dev/null | grep -q '"starting":false'; then
        break
    fi
    sleep 2
done
curl -sf "${TRINO_URL}/v1/info" | grep -q '"starting":false' || { echo "Trino did not become ready"; exit 1; }
echo "Trino is ready."

run_sql "SHOW CATALOGS"
run_sql "SHOW SCHEMAS FROM db2"
run_sql "CREATE SCHEMA IF NOT EXISTS db2.smoke"
run_sql "DROP TABLE IF EXISTS db2.smoke.smoke_test"
run_sql "CREATE TABLE db2.smoke.smoke_test (id bigint, name varchar(32), ts timestamp(6))"
run_sql "INSERT INTO db2.smoke.smoke_test VALUES (1, 'eins', TIMESTAMP '2026-07-07 12:00:00.123456'), (2, 'zwei', TIMESTAMP '2026-07-07 13:00:00.654321')"
run_sql "SELECT * FROM db2.smoke.smoke_test ORDER BY id"
run_sql "SELECT count(*) AS row_count FROM db2.smoke.smoke_test"
run_sql "CREATE TABLE db2.smoke.smoke_ctas AS SELECT id, name FROM db2.smoke.smoke_test WHERE id = 2"
run_sql "SELECT * FROM db2.smoke.smoke_ctas"
run_sql "DROP TABLE db2.smoke.smoke_ctas"
run_sql "DROP TABLE db2.smoke.smoke_test"

echo
echo "Smoke test PASSED — db2 connector works inside the Stackable Trino image."
