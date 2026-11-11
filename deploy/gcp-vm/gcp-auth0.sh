#!/usr/bin/env bash
#
# Configure the GCP VM for Auth0 (OIDC resource-server mode).
#
# This script writes the public OIDC settings to /etc/qoc/env, creates the
# public UI runtime config /etc/qoc/ui-config.js, verifies that QOC rejects
# unauthenticated API calls, and then runs gcp-ssl.sh with QOC_AUTH_MODE=oidc
# so nginx is configured without HTTP Basic Auth.
#
# The native QOC binary must have been built with QUARKUS_OIDC_ENABLED=true.
# See deploy/gcp-vm/vm.md for the build command.
#
# Usage:
#   ./deploy/gcp-vm/gcp-auth0.sh check
#   ./deploy/gcp-vm/gcp-auth0.sh apply
#
# Environment overrides:
#   GCP_PROJECT, GCP_ZONE, GCP_VM
#   QOC_DOMAIN, QOC_ENGINE_PORT
#   AUTH0_ISSUER, AUTH0_CLIENT_ID, AUTH0_AUDIENCE, AUTH0_API_SCOPE
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PROJECT="${GCP_PROJECT:-gen-lang-client-0960862878}"
ZONE="${GCP_ZONE:-us-central1-a}"
VM="${GCP_VM:-quad-e2micro}"
DOMAIN="${QOC_DOMAIN:-test.augmentia.de}"
ENGINE_PORT="${QOC_ENGINE_PORT:-8081}"

AUTH0_ISSUER="${AUTH0_ISSUER:-https://dev-1kxf4iy7txwbmdks.us.auth0.com/}"
AUTH0_CLIENT_ID="${AUTH0_CLIENT_ID:-urr7kMsdGQgCu4PyOF5llxqNCUGjH1F2}"
AUTH0_AUDIENCE="${AUTH0_AUDIENCE:-qoc-engine}"
AUTH0_API_SCOPE="${AUTH0_API_SCOPE:-qoc:use}"

ISSUER_BASE="${AUTH0_ISSUER%/}"
ISSUER="${ISSUER_BASE}/"

REMOTE_SCRIPT=""

usage() {
  cat <<EOF
Usage: $0 [check|apply]

  check   Validate configuration and Auth0 discovery locally.
  apply   Write OIDC config on the VM and re-run the TLS/nginx setup with
          QOC_AUTH_MODE=oidc (no HTTP Basic Auth).

Environment:
  GCP_PROJECT=$PROJECT
  GCP_ZONE=$ZONE
  GCP_VM=$VM
  QOC_DOMAIN=$DOMAIN
  QOC_ENGINE_PORT=$ENGINE_PORT
  AUTH0_ISSUER=$ISSUER
  AUTH0_CLIENT_ID=$AUTH0_CLIENT_ID
  AUTH0_AUDIENCE=$AUTH0_AUDIENCE
  AUTH0_API_SCOPE=$AUTH0_API_SCOPE
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "$REMOTE_SCRIPT" ]]; then
    rm -f "$REMOTE_SCRIPT"
  fi
}
trap cleanup EXIT

validate() {
  [[ "$ISSUER_BASE" =~ ^https://[A-Za-z0-9._:/-]+$ ]] \
    || die "AUTH0_ISSUER muss eine https-URL sein: $AUTH0_ISSUER"
  [[ "$AUTH0_CLIENT_ID" =~ ^[A-Za-z0-9]+$ ]] \
    || die "AUTH0_CLIENT_ID ist ungültig: $AUTH0_CLIENT_ID"
  [[ "$AUTH0_AUDIENCE" =~ ^[A-Za-z0-9._:/-]+$ ]] \
    || die "AUTH0_AUDIENCE ist ungültig: $AUTH0_AUDIENCE"
  [[ -z "$AUTH0_API_SCOPE" || "$AUTH0_API_SCOPE" =~ ^[A-Za-z0-9._:/-]+$ ]] \
    || die "AUTH0_API_SCOPE ist ungültig: $AUTH0_API_SCOPE"
}

check_discovery() {
  echo "Prüfe OIDC-Discovery: $ISSUER_BASE/.well-known/openid-configuration"
  local body
  body="$(curl -fsS --max-time 15 "$ISSUER_BASE/.well-known/openid-configuration")" \
    || die "Auth0-Discovery nicht erreichbar"
  grep -q '"issuer"' <<<"$body" || die "Discovery-Antwort enthält keinen issuer"
  grep -q '"authorization_endpoint"' <<<"$body" || die "Discovery-Antwort enthält keinen authorization_endpoint"
  echo "Discovery OK"
}

write_remote_script() {
  REMOTE_SCRIPT="$(mktemp)"
  cat >"$REMOTE_SCRIPT" <<'REMOTE'
#!/usr/bin/env bash
set -Eeuo pipefail

ISSUER_BASE="$1"
ISSUER="$2"
CLIENT_ID="$3"
AUDIENCE="$4"
API_SCOPE="$5"
ENGINE_PORT="$6"
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="/root/qoc-auth0-backup-$TS"

log() { printf '\n== %s ==\n' "$*"; }

mkdir -p "$BACKUP_DIR"
cp -a /etc/qoc/env "$BACKUP_DIR/env"

restore() {
  local code=$?
  set +e
  echo "FEHLER (exit $code) - Rollback /etc/qoc/env ..."
  cp -a "$BACKUP_DIR/env" /etc/qoc/env
  systemctl restart qoc
  echo "Rollback abgeschlossen. Backup: $BACKUP_DIR"
  exit "$code"
}
trap restore ERR

set_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" /etc/qoc/env; then
    sed -i "s|^${key}=.*|${key}=${value}|" /etc/qoc/env
  else
    printf '%s=%s\n' "$key" "$value" >>/etc/qoc/env
  fi
}

log "Schreibe OIDC-Konfiguration nach /etc/qoc/env"
set_env ENGINE_SECURITY_MODE oidc
set_env QUARKUS_OIDC_ENABLED true
set_env QUARKUS_OIDC_AUTH_SERVER_URL "$ISSUER_BASE"
set_env QUARKUS_OIDC_TOKEN_ISSUER "$ISSUER"
set_env QUARKUS_OIDC_TOKEN_AUDIENCE "$AUDIENCE"
set_env QUARKUS_HTTP_AUTH_PERMISSION_AUTHENTICATED_PATHS '/api/*,/mcp/*,/mcp'
set_env QUARKUS_HTTP_AUTH_PERMISSION_AUTHENTICATED_POLICY authenticated

# These values belong to the local Docker/Keycloak setup and must not be used
# against Auth0. QOC is a pure resource server and needs no client secret.
sed -i -E \
  '/^QUARKUS_OIDC_CLIENT_ID=/d; /^QUARKUS_OIDC_CREDENTIALS_SECRET=/d; /^QUARKUS_OIDC_JWKS_PATH=/d' \
  /etc/qoc/env

log "Schreibe öffentliche UI-Konfiguration /etc/qoc/ui-config.js"
install -d -m 755 /etc/qoc
cat >/etc/qoc/ui-config.js <<EOF
window.QOC_AUTH = {
  oidcEnabled: true,
  issuer: "$ISSUER",
  clientId: "$CLIENT_ID",
  audience: "$AUDIENCE",
  apiScope: "$API_SCOPE"
};
EOF
chmod 644 /etc/qoc/ui-config.js

log "Starte QOC neu"
systemctl restart qoc

log "Prüfe, dass die UI öffentlich und die API durch OIDC geschützt ist"
ui_code=000
api_code=000
for _ in $(seq 1 30); do
  ui_code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$ENGINE_PORT/" || true)"
  api_code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$ENGINE_PORT/api/session" || true)"
  [[ "$ui_code" == "200" && "$api_code" == "401" ]] && break
  sleep 1
done

if [[ "$ui_code" != "200" ]]; then
  echo "UI auf 127.0.0.1:$ENGINE_PORT antwortet mit HTTP $ui_code"
  journalctl -u qoc -n 40 --no-pager 2>/dev/null || true
  false
fi
if [[ "$api_code" != "401" ]]; then
  echo "API ohne Token antwortet mit HTTP $api_code (erwartet: 401)."
  echo "Wurde die native Binary mit QUARKUS_OIDC_ENABLED=true gebaut?"
  journalctl -u qoc -n 60 --no-pager 2>/dev/null || true
  false
fi

trap - ERR
log "OIDC-Konfiguration auf der VM ist aktiv"
echo "Backup: $BACKUP_DIR"
REMOTE
  chmod 700 "$REMOTE_SCRIPT"
}

apply_remote() {
  validate
  command -v gcloud >/dev/null || die "gcloud fehlt"
  [[ -x "$SCRIPT_DIR/gcp-ssl.sh" ]] \
    || die "$SCRIPT_DIR/gcp-ssl.sh fehlt oder ist nicht ausführbar"
  check_discovery
  write_remote_script
  echo "Lade Remote-Skript hoch ..."
  gcloud compute scp "$REMOTE_SCRIPT" "$VM:/tmp/qoc-auth0-remote.sh" \
    --project "$PROJECT" --zone "$ZONE" --quiet

  echo "Schreibe Auth0-Konfiguration auf $VM ..."
  gcloud compute ssh "$VM" --project "$PROJECT" --zone "$ZONE" --quiet \
    --command="sudo -n bash /tmp/qoc-auth0-remote.sh '$ISSUER_BASE' '$ISSUER' '$AUTH0_CLIENT_ID' '$AUTH0_AUDIENCE' '$AUTH0_API_SCOPE' '$ENGINE_PORT'"

  echo
  echo "Aktualisiere nginx ohne HTTP Basic Auth ..."
  QOC_AUTH_MODE=oidc "$SCRIPT_DIR/gcp-ssl.sh" apply

  echo
  echo "Fertig. UI: https://$DOMAIN/"
  echo "Der Login läuft über Auth0: $ISSUER"
}

case "${1:-apply}" in
  check)
    validate
    check_discovery
    ;;
  apply)
    apply_remote
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 2
    ;;
esac
