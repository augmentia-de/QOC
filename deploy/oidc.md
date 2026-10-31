# Lokales OIDC mit Keycloak (Docker)

> **Status:** 2026-10-31 · lokale Entwicklungsumgebung

## Komponenten

| Komponente | Container | Port |
|---|---|---|
| QOC | `qoc-engine` | `8099` (Host) → `8080` |
| Keycloak | `keycloak-local` | `8180` (Host) → `8080` |

Keycloak läuft im bestehenden Container `keycloak-local` (Volume
`keycloak-local_keycloak-data`, Netzwerk `keycloak-local_default`).
QOC wird über das externe Compose-Netzwerk mit Keycloak verbunden.

## Keycloak starten

```bash
docker run -d --name keycloak-local \
  --network keycloak-local_default --network-alias keycloak \
  -p 8180:8080 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HOSTNAME=http://keycloak:8080 \
  -e KC_HOSTNAME_STRICT=false -e KC_HTTP_ENABLED=true \
  -v keycloak-local_keycloak-data:/opt/keycloak/data \
  quay.io/keycloak/keycloak:latest start-dev
```

Wichtig: `KC_HOSTNAME` muss auf den **internen** Namen `http://keycloak:8080`
zeigen, damit der Issuer im Token von QOC (im Docker-Netz) validiert werden
kann. Der Host erreicht Keycloak weiterhin über `http://localhost:8180`.

## Realm und Client

```bash
docker exec keycloak-local /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin

docker exec keycloak-local /opt/keycloak/bin/kcadm.sh create realms \
  -s realm=qoc -s enabled=true

docker exec keycloak-local /opt/keycloak/bin/kcadm.sh create clients -r qoc \
  -s clientId=qoc-engine -s enabled=true -s publicClient=false \
  -s serviceAccountsEnabled=true -s directAccessGrantsEnabled=true \
  -s secret=<client-secret> -s 'redirectUris=["*"]'
```

## Benutzer und Rollen

```bash
# Rollen
docker exec keycloak-local /opt/keycloak/bin/kcadm.sh create roles -r qoc \
  -s name=admin -s description="QOC admin role"
docker exec keycloak-local /opt/keycloak/bin/kcadm.sh create roles -r qoc \
  -s name=user -s description="QOC user role"

# Benutzer
for u in admin user; do
  docker exec keycloak-local /opt/keycloak/bin/kcadm.sh create users -r qoc \
    -s username=$u -s enabled=true -s email=$u@example.com \
    -s firstName=${u^} -s lastName=QOC -s emailVerified=true
  docker exec keycloak-local /opt/keycloak/bin/kcadm.sh set-password -r qoc \
    --username $u --new-password ${u}-password
  docker exec keycloak-local /opt/keycloak/bin/kcadm.sh add-roles -r qoc \
    --uusername $u --rolename $u
done
```

| Benutzer | Passwort | Rolle |
|---|---|---|
| `admin` | `<admin-password>` | `admin` |
| `user` | `<user-password>` | `user` |

Die Rollen landen im Principal (`realm_access.roles`), werden aber derzeit
nicht für die Autorisierung durchgesetzt.

## Globale Settings statt Workspace-Settings

Damit Agenten und Rechte **nur** aus dem Benutzerverzeichnis kommen:

```properties
ENGINE_SETTINGS_WORKSPACE_ENABLED=false
```

Die globale Datei liegt im Container unter
`$HOME/.config/opencode/settings.json`, also `/data/home/.config/opencode/settings.json`
(Host: `qoc/data/home/.config/opencode/settings.json`). Workspace-
`settings.json` wird dann ignoriert.

## QOC-Konfiguration (`deploy/.env`)

```properties
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/qoc
QUARKUS_OIDC_TOKEN_ISSUER=http://keycloak:8080/realms/qoc
QUARKUS_OIDC_TOKEN_AUDIENCE=account
QUARKUS_OIDC_CLIENT_ID=qoc-engine
QUARKUS_OIDC_CREDENTIALS_SECRET=<client-secret>
QUARKUS_HTTP_AUTH_PERMISSION_AUTHENTICATED_PATHS=/*
QUARKUS_HTTP_AUTH_PERMISSION_AUTHENTICATED_POLICY=authenticated
```

`quarkus.oidc.enabled` ist eine **Buildzeit**-Eigenschaft. Das Image muss mit
`QUARKUS_OIDC_ENABLED=true` gebaut werden:

```bash
cd qoc
QUARKUS_OIDC_ENABLED=true ./deploy/build-image.sh
```

## Token holen und testen

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/qoc/protocol/openid-connect/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=qoc-engine&client_secret=<client-secret>' \
  | jq -r .access_token)

curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN" http://localhost:8099/api/config
# 200

curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8099/api/config
# 401
```

## Hinweise

- Der Client-Credentials-Token hat `aud=account` (Keycloak-Default). Deshalb
  `QUARKUS_OIDC_TOKEN_AUDIENCE=account`.
- Der Principal wird aus `sub`/`preferred_username` gesetzt; Rollen kommen aus
  `realm_access.roles`, Gruppen aus `groups`.
- Rollen werden derzeit **nicht** für die Autorisierung durchgesetzt
  (`isOwner` ist ein Stub). Siehe `docs/Enterprise.md`.
- Die Web-UI sendet keinen Bearer-Token; für die UI wäre ein Login-Flow
  (Authorization Code) nötig. Die API ist per Token nutzbar.
