# Docker Native + OIDC (lokales Keycloak) — QOC

Diese Variante startet die QOC-Engine als **Native**-Container hinter einem
lokalen **Keycloak** (Port 8180). Login läuft über Authorization Code + PKCE;
der Login-Button erscheint automatisch, weil `/config.js` `oidcEnabled:true`
liefert.

## 1. Keycloak starten (einmalig)

```bash
cd deploy/keycloak
docker compose up -d            # Keycloak auf http://localhost:8180 (Container keycloak-local)
```

## 2. Realm/Clients/User anlegen (einmalig, idempotent)

```bash
cd /home/torsten/dev/my-projects/2oc/qoc
KEYCLOAK_URL=http://localhost:8180 ./deploy/keycloak/setup-keycloak.sh
```

Erzeugt: Realm `qoc`, Clients `qoc-engine` (confidential), `qoc-ui` (public PKCE),
`qoc-cli` (Direct-Grant; nur für curl-Tests), User:

| User  | Passwort        | Rolle          |
|-------|-----------------|----------------|
| alice | `alice-password`| normal         |
| bob   | `bob-password`  | admin          |

## 3. QOC native starten

```bash
cd deploy/docker-oidc-native
docker compose up -d
```

Wichtig: `qoc-oidc-native` muss im Keycloak-Netzwerk hängen (im Compose bereits
als externes Netzwerk `qoc-keycloak_default` eingetragen).

Aufruf: **http://localhost:8081** → „Login" → `alice` / `alice-password`.

## Konfiguration (bereits gesetzt)

Backend (Token-Validierung):
```
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/qoc
QUARKUS_OIDC_TOKEN_ISSUER=http://localhost:8180/realms/qoc   # Browser-Issuer
QUARKUS_OIDC_JWKS_PATH=http://keycloak:8080/realms/qoc/protocol/openid-connect/certs
QUARKUS_OIDC_TOKEN_AUDIENCE=qoc-engine
```

UI (`/config.js`, vom Browser gelesen):
```
ENGINE_UI_OIDC_ENABLED=true
ENGINE_UI_OIDC_ISSUER=http://localhost:8180/realms/qoc
ENGINE_UI_OIDC_CLIENT_ID=qoc-ui
ENGINE_UI_OIDC_AUDIENCE=qoc-engine
```

### Warum die JWKS-URL explizit gesetzt ist

Keycloak läuft mit `KC_HOSTNAME=http://localhost:8180`. Dadurch enthält das
Discovery-Dokument `jwks_uri=http://localhost:8180/...` — im Container zeigt
`localhost` aber auf den Container selbst (nicht auf den Host). Deshalb lädt das
Backend die JWKS über den internen Namen `keycloak:8080`. Browser und Backend
sehen denselben Issuer (`localhost:8180`), holen die Schlüssel aber intern.

## Native-Build

Das Native-Binary **muss im Container** gebaut werden (der Host hat eine neuere
glibc als das Container-Image). `DEV_NATIVE_REBUILD=never` sorgt dafür, dass
Container-Restarts sofort sind und nicht jedes Mal neu bauen.

Wenn sich Quellcode/UI geändert hat, einmal neu bauen (speicherbegrenzt, sonst
killt der Host-OOM-Killer den Build mit Exit 137):

```bash
cd /home/torsten/dev/my-projects/2oc/qoc
# UI (falls geändert):
(cd reactflow && npm run build:qoc)
# Native-Binary im Container:
docker run --rm \
  -v "$PWD":/app -v "$HOME/.m2:/home/qoc/.m2" \
  -e HOME=/tmp -e MAVEN_CONFIG=/home/qoc/.m2 \
  -e ENGINE_DB_KIND=postgresql -e ENGINE_DB_DRIVER=org.postgresql.Driver \
  -e ENGINE_FLYWAY_LOCATIONS=classpath:db/migration \
  -e QUARKUS_OIDC_ENABLED=true -u 1000:1000 \
  --entrypoint /bin/bash qoc-engine:dev-native \
  -lc 'cd /app && mvn -B -pl quarkus -am package -DskipTests -Djacoco.skip=true \
        -Dmaven.repo.local=/home/qoc/.m2/repository \
        -Dquarkus.package.type=native -Dquarkus.native.container-build=false \
        -Dquarkus.native.native-image-xmx=8g \
        -Dquarkus.arc.exclude-types=org.a2aproject.sdk.**'
# danach:
cd deploy/docker-oidc-native && docker compose restart
```

## Schnelltest (ohne Browser)

```bash
# Token über den Test-Client holen (aud enthält qoc-engine)
curl -s -X POST http://localhost:8180/realms/qoc/protocol/openid-connect/token \
  -d grant_type=password -d client_id=qoc-cli \
  -d username=alice -d password=alice-password \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
# -> als Bearer gegen http://localhost:8081/api/health verwenden (200)
```

## Aufräumen

Keycloak samt Daten entfernen:
```bash
cd deploy/keycloak && docker compose down -v   # -v löscht das Realm-Volume
```
