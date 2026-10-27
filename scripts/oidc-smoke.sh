#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
export KC_URL="${KC_URL:-http://localhost:8180}"
export KC_REALM="${KC_REALM:-demo}"
export KC_CLIENT_ID="${KC_CLIENT_ID:-test-client}"
export KC_TEST_USER="${KC_TEST_USER:-testuser}"
export KC_TEST_PASS="${KC_TEST_PASS:-testpassword}"

"$ROOT_DIR/scripts/keycloak-bootstrap.sh"

export OIDC_ISSUER="$KC_URL/realms/$KC_REALM"
export OIDC_CLIENT_ID="$KC_CLIENT_ID"
export OIDC_TEST_TOKEN="$(
  curl -s -X POST "$OIDC_ISSUER/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "username=$KC_TEST_USER" -d "password=$KC_TEST_PASS" \
    -d 'grant_type=password' -d "client_id=$KC_CLIENT_ID" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))'
)"

if [ -z "$OIDC_TEST_TOKEN" ]; then
  echo "ERROR: could not obtain OIDC test token"
  exit 1
fi

export ENGINE_SECURITY_MODE=oidc
cd "$ROOT_DIR"
mvn -pl quarkus test -Dtest='OidcSmokeTest'
