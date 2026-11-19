# 04 – OIDC with Auth0 (spec-only)

> **Status:** spec-only
> **Purpose:** Auth0 as OIDC provider for QOC (web-app mode with session cookie).

## 1. Auth0 Resources

1. **Application** (Regular Web Application)
   - Callback URLs: local + production QOC URLs + callback path
   - Logout URLs: local + production QOC URLs
   - Web Origins: local + production QOC URLs
2. **API / Resource Server**
   - Identifier/Audience, e.g. `https://qoc-api` or `qoc-engine`
   - Scopes: `openid`, `profile`, `email`, optional `qoc:read`, `qoc:edit`
3. **Roles**: `admin`, `user`
4. **Action (Post-Login)**: insert roles into ID/access token

## 2. QOC Configuration

```properties
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
quarkus.oidc.auth-server-url=https://<tenant>.auth0.com/
quarkus.oidc.client-id=<client-id>
quarkus.oidc.credentials.secret=<client-secret>
quarkus.oidc.token.issuer=https://<tenant>.auth0.com/
quarkus.oidc.token.audience=<api-identifier>
quarkus.oidc.application-type=web-app
```

Note: `quarkus.oidc.enabled` is a build-time property.

## 3. Code Adjustments

- `OidcPrincipalProducer`: claim mapping for Auth0 (e.g. `https://qoc/roles`, `permissions`)
- `SecurityConfig.mode()`: already supports `ENGINE_SECURITY_MODE=oidc`
- Auth0 roles → QOC roles/agent permissions
- implement `isOwner`/authorization

## 4. Test Plan

1. Create Auth0 test users with `admin` and `user`
2. Build and start QOC with OIDC env
3. Browser login
4. verify `/api/health`, `/api/config`, file operations
5. verify token claims and role mapping
6. Logout, session expiry, re-login

## 5. Risks

- Quarkus callback path and session cookie behavior
- Auth0 role claim names must be aligned with the code
- Native image must initialize OIDC classes
- Basic Auth in parallel: test AuthMode resolution
