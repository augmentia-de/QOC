# Local Keycloak for QOC

This directory starts a locally reproducible Keycloak for the
Authorization Code + PKCE flow described in `docs/security.md` §9.

## Quick start

From the repository root (`qoc/`):

```bash
# once: create the local runtime env and set OPENAI_API_KEY
cp deploy/.env.example deploy/.env

./deploy/start-oidc.sh
```

The script:

1. Starts Keycloak on `http://localhost:8180` (container `keycloak-local`,
   network `qoc-keycloak_default`).
2. Creates/updates the realm `qoc`, the clients, the audience mapper and the
   test users (idempotent).
3. Builds the QOC image with `QUARKUS_OIDC_ENABLED=true`.
4. Starts QOC with `deploy/docker-compose.oidc.yml` on
   `http://localhost:8071`.

Login as `alice` / `alice-password` (role `qoc-user`).
Admin test user: `bob` / `bob-password` (role `qoc-admin`).
Keycloak admin console: `http://localhost:8180/admin` (`admin` / `admin`).

If the image is already built:

```bash
SKIP_BUILD=true ./deploy/start-oidc.sh
```

## Components

| Component | URL / container | Notes |
|---|---|---|
| Keycloak | `http://localhost:8180` / `keycloak-local` | `start-dev`, H2 dev file in named volume |
| Realm | `qoc` | `sslRequired=none` for local HTTP |
| QOC | `http://localhost:8071` / `qoc-local` | erreicht Keycloak über `host.docker.internal:8180` |
| SPA client | `qoc-ui` | public, Authorization Code + PKCE (S256) |
| CLI client | `qoc-cli` | password grant, local curl tests only |
| Engine client | `qoc-engine` | confidential, service account, future token exchange |
| MCP clients | `mcp-server`, `qoc-mcp` | reserved for the token-exchange target |

## What the SPA does

- Authorization Code + PKCE (S256); state and verifier live in `sessionStorage`.
- Access/refresh tokens live in `sessionStorage` and are cleared on logout or
  when a refresh fails.
- All `/api/*` calls carry `Authorization: Bearer …`; a `401` triggers one
  refresh attempt.
- `EventSource` cannot send an `Authorization` header, so `/api/event` is read
  with `fetch` + `AbortController` and the SSE frames are parsed in the page.
- The static UI at `/` is public; only `/api/*`, `/mcp/*` and `/mcp` require a
  token.

## Known gaps

- The BFF/session-cookie step is not implemented yet, so tokens are exposed to
  XSS in `sessionStorage`. See `docs/security.md` §9.
- Per-user session isolation is still a stub: `HttpApi.isOwner()` accepts every
  authenticated principal. Do not treat the current local setup as a
  multi-tenant deployment.
- Token exchange for MCP (`aud=mcp-server`, `scope=mcp:run`) is designed in
  `docs/security.md` §9 but not implemented.

## Issuer / hostname split

The browser must see `http://localhost:8180` in the issuer, while the QOC
container must reach the same Keycloak from inside Docker. The setup uses a
split-horizon configuration:

- Keycloak: `KC_HOSTNAME=http://localhost:8180`
- QOC: `QUARKUS_OIDC_TOKEN_ISSUER=http://localhost:8180/realms/qoc`
- QOC: `QUARKUS_OIDC_AUTH_SERVER_URL=http://host.docker.internal:8180/realms/qoc`
- QOC: `QUARKUS_OIDC_JWKS_PATH=/protocol/openid-connect/certs`

`host.docker.internal` is mapped to the Docker host gateway in
`deploy/docker-compose.yml` (`extra_hosts`).

## Token check

```bash
curl -s -X POST 'http://localhost:8180/realms/qoc/protocol/openid-connect/token' \
  -d grant_type=password -d client_id=qoc-cli \
  -d username=alice -d password=alice-password \
  | jq -r .access_token \
  | cut -d. -f2 | base64 -d 2>/dev/null | jq '{aud, azp, preferred_username, realm_access}'
```

The access token must contain `"aud": ["qoc-engine", ...]`.

## Notes

- The audience mapper is attached directly to `qoc-ui`/`qoc-cli` to keep the
  bootstrap script short. The target architecture in `docs/security.md` uses a
  reusable `qoc-api` client scope; either representation produces the same
  access token audience.
- `qoc-cli` exists only for local automation. Do not enable the password grant
  on `qoc-ui`.
- The realm uses `sslRequired=none` because the local issuer is HTTP. Any
  non-local deployment must use HTTPS and a real hostname.

## Troubleshooting

- **Login button does nothing / PKCE error:** `crypto.subtle` is only available
  in a secure context. Use `http://localhost:8071` or HTTPS. A LAN IP over plain
  HTTP is not a secure context.
- **`401` on `/api/health` or `/api/session`:** check that the token contains
  `aud` with `qoc-engine` and that QOC uses
  `QUARKUS_OIDC_TOKEN_AUDIENCE=qoc-engine`. The issuer must be exactly
  `http://localhost:8180/realms/qoc` (no trailing slash, no container
  hostname).
- **UI shows `SSE getrennt`:** open DevTools → Network and check
  `/api/event?...&live=true`. It must be a `fetch` request with an
  `Authorization` header and status `200`. A `401` means the token is missing
  or expired; reload after logging in.
- **Keycloak redirects to `localhost` from another host:** this is intentional
  (`KC_HOSTNAME=http://localhost:8180`). Use a browser on the Docker host.
- **Re-running the setup:** `./deploy/keycloak/setup-keycloak.sh` is
  idempotent. It resets `qoc-ui` redirect URIs to the `localhost` variants.
