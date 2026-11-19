# GCP VM with Auth0 OIDC

> **Status:** partial
> **Source:** `deploy/gcp-vm/gcp-auth0.sh`, `deploy/gcp-vm/gcp-auth0.env.example`, `deploy/gcp-vm/gcp-ssl.sh`

## Auth0 Resources

- SPA Application `QOC UI` (Authorization Code + PKCE, public client)
- API/Resource Server with identifier `qoc-engine`
- SPA authorized for `qoc-engine` (scope `qoc:use`)
- Callback/Logout: `https://test.augmentia.de/`, `http://localhost:8073/` (E2E server), `http://localhost:8071/` (local)

## Configuration

```bash
cd qoc
cp deploy/gcp-vm/gcp-auth0.env.example deploy/gcp-vm/gcp-auth0.env
set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh check
./deploy/gcp-vm/gcp-auth0.sh apply
```

`gcp-auth0.sh` writes public OIDC values to `/etc/qoc/env`,
creates `/etc/qoc/ui-config.js`, verifies that `/api/session` returns 401
without a token, and calls `gcp-ssl.sh` with `QOC_AUTH_MODE=oidc`.

## Building the Native Binary with OIDC

```bash
cd qoc
export QUARKUS_OIDC_ENABLED=true
export ENGINE_DB_KIND=postgresql
export ENGINE_DB_DRIVER=org.postgresql.Driver
mvn -o -pl quarkus -am clean package -Dnative -DskipTests
```

## Open Items

- Claim mapping in `OidcPrincipalProducer` for Auth0 roles (Keycloak claims
  are currently read).
- Implement `isOwner` authorization.
- Native Image must initialize OIDC classes correctly.