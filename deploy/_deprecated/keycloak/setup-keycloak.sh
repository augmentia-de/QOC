#!/usr/bin/env bash
# Create/update the local Keycloak realm for QOC.
#
# The script is idempotent: it can be re-run after the realm already exists.
# It expects the container from deploy/keycloak/docker-compose.yml
# (container name: keycloak-local).
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_CONTAINER="${KEYCLOAK_CONTAINER:-keycloak-local}"
REALM="${QOC_REALM:-qoc}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
ENGINE_CLIENT_ID="${QOC_ENGINE_CLIENT_ID:-qoc-engine}"
ENGINE_SECRET="${QOC_ENGINE_SECRET:-qoc-engine-local-secret}"
UI_CLIENT_ID="${QOC_UI_CLIENT_ID:-qoc-ui}"
CLI_CLIENT_ID="${QOC_CLI_CLIENT_ID:-qoc-cli}"
UI_USER="${QOC_UI_USER:-alice}"
UI_PASSWORD="${QOC_UI_PASSWORD:-alice-password}"
ADMIN_TEST_USER="${QOC_ADMIN_USER:-bob}"
ADMIN_TEST_PASSWORD="${QOC_ADMIN_PASSWORD:-bob-password}"

command -v jq >/dev/null 2>&1 || {
  echo "jq is required on the host (apt install jq / brew install jq)." >&2
  exit 1
}
docker inspect "$KEYCLOAK_CONTAINER" >/dev/null 2>&1 || {
  echo "Keycloak container '$KEYCLOAK_CONTAINER' not found. Start deploy/keycloak/docker-compose.yml first." >&2
  exit 1
}

kcadm() {
  docker exec -i "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

echo "Waiting for Keycloak at $KEYCLOAK_URL ..."
ready=0
for _ in $(seq 1 90); do
  if curl -fsS "$KEYCLOAK_URL/realms/master" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then
  echo "Keycloak did not become ready at $KEYCLOAK_URL." >&2
  exit 1
fi

echo "Logging in to Keycloak admin API ..."
kcadm config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$ADMIN_USER" \
  --password "$ADMIN_PASSWORD" >/dev/null

# --- realm -----------------------------------------------------------------
if kcadm get "realms/$REALM" >/dev/null 2>&1; then
  echo "Updating realm '$REALM' ..."
  kcadm update "realms/$REALM" \
    -s enabled=true \
    -s sslRequired=none \
    -s accessTokenLifespan=300 \
    -s ssoSessionIdleTimeout=1800 \
    -s ssoSessionMaxLifespan=36000 \
    -s revokeRefreshToken=true \
    -s refreshTokenMaxReuse=0 >/dev/null
else
  echo "Creating realm '$REALM' ..."
  kcadm create realms \
    -s realm="$REALM" \
    -s enabled=true \
    -s sslRequired=none \
    -s accessTokenLifespan=300 \
    -s ssoSessionIdleTimeout=1800 \
    -s ssoSessionMaxLifespan=36000 \
    -s revokeRefreshToken=true \
    -s refreshTokenMaxReuse=0 >/dev/null
fi

# --- roles -----------------------------------------------------------------
upsert_role() {
  local name="$1"
  if ! kcadm get "roles/$name" -r "$REALM" >/dev/null 2>&1; then
    kcadm create roles -r "$REALM" -s name="$name" >/dev/null
    echo "  role $name created"
  fi
}
upsert_role qoc-user
upsert_role qoc-admin
upsert_role mcp-user

# --- clients ---------------------------------------------------------------
client_id() {
  kcadm get clients -r "$REALM" -q clientId="$1" 2>/dev/null | jq -r '.[0].id // empty'
}

upsert_client() {
  local cid="$1"
  shift
  local id
  id="$(client_id "$cid")"
  if [ -z "$id" ]; then
    kcadm create clients -r "$REALM" -s clientId="$cid" -s protocol=openid-connect "$@" >/dev/null
    echo "  client $cid created" >&2
  else
    kcadm update "clients/$id" -r "$REALM" "$@" >/dev/null
    echo "  client $cid updated" >&2
  fi
}

# Public SPA client: Authorization Code + PKCE only.
upsert_client "$UI_CLIENT_ID" \
  -s enabled=true \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s implicitFlowEnabled=false \
  -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=false \
  -s 'redirectUris=["http://localhost:8070/*","http://localhost:8071/*","http://localhost:8073/*","http://localhost:8080/*","http://localhost:8081/*"]' \
  -s 'webOrigins=["http://localhost:8070","http://localhost:8071","http://localhost:8073","http://localhost:8080","http://localhost:8081"]' \
  -s 'attributes={"pkce.code.challenge.method":"S256","post.logout.redirect.uris":"http://localhost:8070/*##http://localhost:8071/*##http://localhost:8073/*##http://localhost:8080/*##http://localhost:8081/*"}'
UI_ID="$(client_id "$UI_CLIENT_ID")"

# CLI/test client: Resource Owner Password Grant only for local curl tests.
upsert_client "$CLI_CLIENT_ID" \
  -s enabled=true \
  -s publicClient=true \
  -s standardFlowEnabled=false \
  -s implicitFlowEnabled=false \
  -s directAccessGrantsEnabled=true \
  -s serviceAccountsEnabled=false \
  -s 'redirectUris=[]' \
  -s 'webOrigins=[]'
CLI_ID="$(client_id "$CLI_CLIENT_ID")"

# Confidential client used by the QOC engine (service account / future
# token exchange). Bearer-token validation itself does not need the secret.
upsert_client "$ENGINE_CLIENT_ID" \
  -s enabled=true \
  -s publicClient=false \
  -s standardFlowEnabled=false \
  -s implicitFlowEnabled=false \
  -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=true \
  -s clientAuthenticatorType=client-secret \
  -s secret="$ENGINE_SECRET" \
  -s 'redirectUris=[]' \
  -s 'webOrigins=[]'
# Resource-server clients reserved for the MCP delegation target described in
# docs/security.md. They are harmless if unused.
upsert_client mcp-server \
  -s enabled=true \
  -s publicClient=false \
  -s bearerOnly=true \
  -s standardFlowEnabled=false \
  -s directAccessGrantsEnabled=false \
  -s 'redirectUris=[]' \
  -s 'webOrigins=[]' >/dev/null
upsert_client qoc-mcp \
  -s enabled=true \
  -s publicClient=false \
  -s bearerOnly=true \
  -s standardFlowEnabled=false \
  -s directAccessGrantsEnabled=false \
  -s 'redirectUris=[]' \
  -s 'webOrigins=[]' >/dev/null

# --- audience mapper -------------------------------------------------------
# Adds aud=qoc-engine to access tokens so QOC can validate them as a resource
# server. The direct mapper is used instead of a client scope to keep this
# local bootstrap script short.
add_audience_mapper() {
  local cid="$1"
  [ -n "$cid" ] || return 0
  local existing
  existing="$(kcadm get "clients/$cid/protocol-mappers/models" -r "$REALM" 2>/dev/null \
    | jq -r '.[] | select(.name=="qoc-engine-audience") | .id' | head -1)"
  if [ -z "$existing" ]; then
    kcadm create "clients/$cid/protocol-mappers/models" -r "$REALM" \
      -s name=qoc-engine-audience \
      -s protocol=openid-connect \
      -s protocolMapper=oidc-audience-mapper \
      -s 'config={"included.custom.audience":"qoc-engine","access.token.claim":"true","id.token.claim":"false","userinfo.token.claim":"false","introspection.token.claim":"true"}' >/dev/null
    echo "  audience mapper added to $cid"
  fi
}
add_audience_mapper "$UI_ID"
add_audience_mapper "$CLI_ID"

# --- users -----------------------------------------------------------------
upsert_user() {
  local username="$1" password="$2"
  shift 2
  local uid
  uid="$(kcadm get users -r "$REALM" -q username="$username" 2>/dev/null | jq -r '.[0].id // empty')"
  if [ -z "$uid" ]; then
    kcadm create users -r "$REALM" \
      -s username="$username" \
      -s enabled=true \
      -s email="$username@example.test" \
      -s firstName="$username" \
      -s lastName=QOC \
      -s emailVerified=true >/dev/null
    echo "  user $username created"
  else
    kcadm update "users/$uid" -r "$REALM" -s enabled=true >/dev/null
  fi
  kcadm set-password -r "$REALM" --username "$username" --new-password "$password" >/dev/null
  for role in "$@"; do
    kcadm add-roles -r "$REALM" --uusername "$username" --rolename "$role" >/dev/null 2>&1 || true
  done
}
upsert_user "$UI_USER" "$UI_PASSWORD" qoc-user mcp-user
upsert_user "$ADMIN_TEST_USER" "$ADMIN_TEST_PASSWORD" qoc-admin mcp-user

echo
echo "Keycloak realm '$REALM' is ready."
echo "  Issuer:        $KEYCLOAK_URL/realms/$REALM"
echo "  SPA client:    $UI_CLIENT_ID (public, Authorization Code + PKCE)"
echo "  CLI client:    $CLI_CLIENT_ID (password grant, local tests only)"
echo "  Test user:     $UI_USER / $UI_PASSWORD"
echo "  Admin user:    $ADMIN_TEST_USER / $ADMIN_TEST_PASSWORD"
echo
echo "Check a token:"
echo "  curl -s -X POST '$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token' \\"
echo "    -d grant_type=password -d client_id=$CLI_CLIENT_ID \\"
echo "    -d username=$UI_USER -d password=$UI_PASSWORD | jq -r .access_token | cut -d. -f2 | base64 -d 2>/dev/null | jq .aud"
