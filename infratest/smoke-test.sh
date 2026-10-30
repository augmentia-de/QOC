#!/usr/bin/env bash
# End-to-end CRUD smoke test for the infratest REST API.
#
# Usage:
#   ./smoke-test.sh                                  # deployed Cloud Run service
#   BASE_URL=http://127.0.0.1:8090 ./smoke-test.sh   # local service
#   ./smoke-test.sh https://my-service.run.app       # explicit URL
#
# The script creates an entry, reads it by id, updates it, reads it again,
# deletes it and verifies that it is gone. A failed run cleans up the entry
# it created (best effort).
set -euo pipefail

BASE_URL="${BASE_URL:-${1:-https://infratest-6npdzlxhhq-uc.a.run.app}}"
BASE_URL="${BASE_URL%/}"

for cmd in curl jq; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "missing required command: $cmd" >&2
        exit 1
    fi
done

BODY_FILE="$(mktemp)"
ID=""

cleanup() {
    if [[ -n "${ID:-}" ]]; then
        echo "Cleanup: deleting entry $ID" >&2
        curl -sS -o /dev/null -X DELETE "$BASE_URL/infratest/$ID" || true
    fi
    rm -f "$BODY_FILE"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

call() {
    local method="$1"
    local path="$2"
    local data="${3-}"

    if [[ -n "$data" ]]; then
        HTTP_STATUS="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
            -X "$method" \
            -H 'Content-Type: application/json' \
            -d "$data" \
            "$BASE_URL$path")"
    else
        HTTP_STATUS="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
            -X "$method" \
            "$BASE_URL$path")"
    fi
    HTTP_BODY="$(cat "$BODY_FILE")"
}

assert_status() {
    local expected="$1"
    [[ "$HTTP_STATUS" == "$expected" ]] \
        || fail "expected HTTP $expected, got $HTTP_STATUS; body: $HTTP_BODY"
}

assert_field() {
    local field="$1"
    local expected="$2"
    local actual
    actual="$(jq -r --arg field "$field" '.[$field]' <<<"$HTTP_BODY")"
    [[ "$actual" == "$expected" ]] \
        || fail "expected $field='$expected', got '$actual'; body: $HTTP_BODY"
}

echo "Target: $BASE_URL"

NAME="smoke-$(date +%Y%m%d-%H%M%S)-$$"
CONTENT="created"
payload="$(jq -nc --arg name "$NAME" --arg content "$CONTENT" \
    '{name:$name, content:$content}')"

echo "1/6 CREATE"
call POST /infratest "$payload"
assert_status 201
ID="$(jq -r '.id // empty' <<<"$HTTP_BODY")"
[[ "$ID" =~ ^[0-9]+$ ]] || fail "response has no numeric id: $HTTP_BODY"
assert_field name "$NAME"
assert_field content "$CONTENT"
echo "     id=$ID"

echo "2/6 READ by id"
call GET "/infratest/$ID"
assert_status 200
assert_field id "$ID"
assert_field name "$NAME"
assert_field content "$CONTENT"

echo "3/6 READ list"
call GET /infratest
assert_status 200
printf '%s' "$HTTP_BODY" | jq -e --argjson id "$ID" 'any(.[]; .id == $id)' >/dev/null \
    || fail "entry $ID not found in list: $HTTP_BODY"

echo "4/6 UPDATE"
UPDATED_NAME="${NAME}-updated"
UPDATED_CONTENT="updated"
payload="$(jq -nc --arg name "$UPDATED_NAME" --arg content "$UPDATED_CONTENT" \
    '{name:$name, content:$content}')"
call PUT "/infratest/$ID" "$payload"
assert_status 200
assert_field id "$ID"
assert_field name "$UPDATED_NAME"
assert_field content "$UPDATED_CONTENT"

echo "5/6 READ updated entry"
call GET "/infratest/$ID"
assert_status 200
assert_field name "$UPDATED_NAME"
assert_field content "$UPDATED_CONTENT"

echo "6/6 DELETE and verify 404"
DELETED_ID="$ID"
call DELETE "/infratest/$DELETED_ID"
assert_status 204
call GET "/infratest/$DELETED_ID"
assert_status 404
ID=""

echo "OK: create, read, update, read and delete passed"
