# GCP VM mit Auth0 OIDC

> **Status:** partial
> **Quelle:** `deploy/gcp-vm/gcp-auth0.sh`, `deploy/gcp-vm/gcp-auth0.env.example`, `deploy/gcp-vm/gcp-ssl.sh`

## Auth0-Ressourcen

- SPA Application `QOC UI` (Authorization Code + PKCE, public client)
- API/Resource Server mit Identifier `qoc-engine`
- SPA autorisiert für `qoc-engine` (Scope `qoc:use`)
- Callback/Logout: `https://test.augmentia.de/`, `http://localhost:8073/` (E2E-Server), `http://localhost:8071/` (lokal)

## Konfiguration

```bash
cd qoc
cp deploy/gcp-vm/gcp-auth0.env.example deploy/gcp-vm/gcp-auth0.env
set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh check
./deploy/gcp-vm/gcp-auth0.sh apply
```

`gcp-auth0.sh` schreibt öffentliche OIDC-Werte nach `/etc/qoc/env`,
erstellt `/etc/qoc/ui-config.js`, prüft dass `/api/session` ohne Token 401
liefert, und ruft `gcp-ssl.sh` mit `QOC_AUTH_MODE=oidc` auf.

## Native Binary mit OIDC bauen

```bash
cd qoc
export QUARKUS_OIDC_ENABLED=true
export ENGINE_DB_KIND=postgresql
export ENGINE_DB_DRIVER=org.postgresql.Driver
mvn -o -pl quarkus -am clean package -Dnative -DskipTests
```

## Offen

- Claim-Mapping in `OidcPrincipalProducer` für Auth0-Rollen (Keycloak-Claims
  werden derzeit gelesen).
- `isOwner`-Autorisierung implementieren.
- Native Image muss OIDC-Klassen korrekt initialisieren.
