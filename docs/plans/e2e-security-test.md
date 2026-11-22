# E2E Security Test Plan

> **Status:** Test plan (2026-11-22) — Not an implementation plan; describes E2E verification scenarios for security paths (auth, principal propagation, OIDC, etc.).

## Goal
End-to-end verification of all security paths of QOC:
Authentication, Principal propagation, Trusted proxy, OIDC (optional),
Webhook guardrail, Egress protection, Session ownership and MCP OAuth.

## Prerequisites
- QOC in test profile with PostgreSQL/SQLite
- Optional Keycloak container for OIDC
- Webhook guardrail service (local or Testcontainer)
- Test user: `opencode` / password from env
- Trusted-proxy CIDR: `127.0.0.1/32`

## Scenarios

### 1. Basic Auth
1. Request without credentials -> 401 + `WWW-Authenticate`
2. Wrong password -> 401
3. Correct credentials -> 200, Principal `opencode`

### 2. Principal Propagation
1. Session creation with Basic Auth -> session directory/owner matches principal
2. `ToolContext.metadata()` contains `principalId`
3. Webhook payload contains `principalId`/`principalName`

### 3. Trusted Proxy
1. Request from untrusted IP with `X-Forwarded-User` -> header ignored
2. Request from trusted IP (127.0.0.1) with `X-Forwarded-User: alice` -> Principal `alice`
3. CIDR match for `10.0.0.0/8`

### 4. OIDC (optional, profile `oidc`)
1. Invalid/expired token -> 401
2. Valid token -> Principal from claims (`sub`, `preferred_username`, `realm_access.roles`)
3. Issuer/audience mismatch -> 401

### 5. Webhook Guardrail
1. `guardrails.webhook` disabled -> no HTTP calls, normal execution
2. `tool.before` with secret -> block, tool not executed
3. `permission.evaluate` with secret -> deny
4. `tool.after` with PII -> redact/replace
5. Fail-mode `closed-before-open-after`: webhook down -> before blocks, after allows
6. Fail-mode `open`: webhook down -> allow
7. Egress: disallowed host -> block/fail-closed
8. Egress: disallowed IP -> block
9. TLS required: HTTP URL with `tlsRequired=true` -> block

### 6. Session Ownership
1. User A can list/read own sessions
2. User B receives 403 on sessions of User A

### 7. MCP OAuth
1. `/mcp` with `security.mode=mcp-oauth` without token -> 401
2. With valid token -> tool execution

### 8. A2A Server
1. `engine.a2a.server.enabled=false`: `GET /.well-known/agent-card.json` and `POST /` -> 404
2. `enabled=true`, `security.mode=none`: AgentCard retrievable, `message/send` returns a text artifact
3. `enabled=true`, `security.mode=basic`: without credentials -> 401, with correct credentials -> 200
4. Wrong password -> 401
5. Task ownership: User B cannot read/continue a task of User A
6. Same `contextId` of different users leads to separate QOC sessions
7. Trusted-proxy identity only accepted from configured CIDRs
8. `engine.a2a.server.base-url` points to the externally reachable URL
9. A2A tool calls go through the same permissions/guardrails as local tools

## Automation
- Quarkus integration tests with `@QuarkusTest` and test profiles
- Testcontainers for PostgreSQL + Keycloak + Webhook Guardrail
- RestAssured for HTTP assertions
- Compute HMAC signatures in tests (test secret)

## Acceptance criteria
- All scenarios green
- No secrets/PII in logs
- Webhook disabled => no network calls
- Egress violations fail-closed
" path">
/home/torsten/dev/my-projects/2oc/qoc/docs/plans/e2e-security-test.md