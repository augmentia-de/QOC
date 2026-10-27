#!/usr/bin/env bash
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8180}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASS:-admin}"
REALM="${KC_REALM:-demo}"
CLIENT_ID="${KC_CLIENT_ID:-test-client}"
TEST_USER="${KC_TEST_USER:-testuser}"
TEST_PASS="${KC_TEST_PASS:-testpassword}"

echo "Waiting for Keycloak at $KC_URL ..."
for i in $(seq 1 30); do
  if curl -sf "$KC_URL/realms/master" >/dev/null 2>&1; then break; fi
  sleep 2
done

ADMIN_TOKEN=$(curl -s -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" \
  -d 'grant_type=password' -d 'client_id=admin-cli' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

auth() { curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$@"; }

# Ensure client exists and has direct access grants enabled
CLIENT_UUID=$(auth "$KC_URL/admin/realms/$REALM/clients" | python3 -c '
import sys,json
clients=json.load(sys.stdin)
for c in clients:
    if c.get("clientId")=="'"$CLIENT_ID"'":
        print(c["id"]); break
')

if [ -z "$CLIENT_UUID" ]; then
  echo "Creating client $CLIENT_ID in realm $REALM..."
  auth -X POST "$KC_URL/admin/realms/$REALM/clients" -H 'Content-Type: application/json' \
    -d "{\"clientId\":\"$CLIENT_ID\",\"enabled\":true,\"publicClient\":true,\"directAccessGrantsEnabled\":true,\"standardFlowEnabled\":true}"
  CLIENT_UUID=$(auth "$KC_URL/admin/realms/$REALM/clients" | python3 -c '
import sys,json
clients=json.load(sys.stdin)
for c in clients:
    if c.get("clientId")=="'"$CLIENT_ID"'":
        print(c["id"]); break
')
fi

echo "Enabling direct access grants for $CLIENT_ID..."
auth -X PUT "$KC_URL/admin/realms/$REALM/clients/$CLIENT_UUID" -H 'Content-Type: application/json' \
  -d '{"enabled":true,"publicClient":true,"directAccessGrantsEnabled":true,"standardFlowEnabled":true}'

# Ensure user exists
USER_UUID=$(auth "$KC_URL/admin/realms/$REALM/users?username=$TEST_USER" | python3 -c '
import sys,json
users=json.load(sys.stdin)
if users: print(users[0]["id"])
')

if [ -z "$USER_UUID" ]; then
  echo "Creating user $TEST_USER in realm $REALM..."
  auth -X POST "$KC_URL/admin/realms/$REALM/users" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$TEST_USER\",\"enabled\":true,\"emailVerified\":true,\"email\":\"$TEST_USER@example.com\",\"firstName\":\"Test\",\"lastName\":\"User\"}"
  USER_UUID=$(auth "$KC_URL/admin/realms/$REALM/users?username=$TEST_USER" | python3 -c '
import sys,json
users=json.load(sys.stdin)
if users: print(users[0]["id"])
')
fi

echo "Resetting password for $TEST_USER..."
auth -X PUT "$KC_URL/admin/realms/$REALM/users/$USER_UUID" -H 'Content-Type: application/json' \
  -d '{"enabled":true,"emailVerified":true,"requiredActions":[]}'
auth -X PUT "$KC_URL/admin/realms/$REALM/users/$USER_UUID/reset-password" -H 'Content-Type: application/json' \
  -d "{\"type\":\"password\",\"value\":\"$TEST_PASS\",\"temporary\":false}"

echo "Verifying token..."
TOKEN_TEST=$(curl -s -X POST "$KC_URL/realms/$REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "username=$TEST_USER" -d "password=$TEST_PASS" \
  -d 'grant_type=password' -d "client_id=$CLIENT_ID" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))')

if [ -z "$TOKEN_TEST" ]; then
  echo "ERROR: could not obtain token for $TEST_USER"
  exit 1
fi
echo "OK: obtained access token (len ${#TOKEN_TEST})"
echo "Issuer: $KC_URL/realms/$REALM"
