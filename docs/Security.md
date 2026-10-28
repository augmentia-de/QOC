# Security.md – QOC Production Security

> **Status:** Specification · 2026-10-27
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)
> **Companion:** `docs/guides/OS-based-security.md`,
> `docs/spec/permissions/*`,
> `docs/spec/betrieb/*`,
> `docs/webhook.md`

---

## 1. Purpose

This document describes the security model of QOC and the production-ready
features that must be enabled for a secure deployment.

Authentication is **optional**. If no authentication is configured, QOC grants
all rights to every caller. This is intentional for local development and
trusted environments, but it is **not** a production configuration.

---

## 2. Security model

### 2.1 Core principles

1. **Defense in depth.** OS permissions, engine policy, tool boundaries and
   transport security are separate layers.
2. **Fail closed for sensitive actions.** Permission checks deny by default.
3. **No sandbox claim.** QOC does not sandbox the agent. Permissions are a
   usability and policy feature, not a security boundary.
4. **Least privilege.** Tools, agents and users receive only the rights they
   need.
5. **Explicit identity.** Every production action should be attributable to a
   user, agent or service identity.
6. **Auditable decisions.** Permission, tool and guardrail decisions are
   observable.

### 2.2 Authentication modes

| Mode | Description | Production use |
|---|---|---|
| `none` | No authentication; all callers are treated as `anonymous` | Development only |
| `basic` | HTTP Basic Auth with username `opencode` | Small trusted deployments |
| `oidc` | OpenID Connect via Keycloak or another IdP | Recommended for multi-user |
| `proxy` | Trusted reverse proxy sets identity headers | Enterprise SSO integration |
| `mcp-oauth` | OAuth 2.1 for MCP clients | MCP server mode |

If authentication is disabled, QOC behaves as if every caller is treated as
`anonymous` unless Basic Auth is configured.

---

## 3. Optional Keycloak integration

Keycloak can run separately, for example in Docker on port `8180`.

```bash
docker run -d \
  --name keycloak \
  -p 8180:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:latest \
  start-dev
```

QOC can optionally use Keycloak as an OIDC provider.

Example configuration:

```json
{
  "security": {
    "mode": "oidc",
    "oidc": {
      "issuer": "http://localhost:8180/realms/qoc",
      "clientId": "qoc-engine",
      "clientSecret": "${QOC_OIDC_CLIENT_SECRET}",
      "scopes": ["openid", "profile", "email", "qoc:agent:run"]
    }
  }
}
```

When OIDC is enabled:

- QOC validates the ID/access token (JWKS + issuer + audience).
- Claims are mapped to the QOC `Principal` (`sub`/`preferred_username` -> id/name, `groups`/`roles` -> roles/groups).
- The token subject becomes the QOC principal.
- Sessions are owned by the principal.
- Permissions and guardrails can be user- and role-aware.

When OIDC is disabled, QOC falls back to `none` or `basic` mode.

---

## 4. Production-ready security features

### 4.1 Transport security

- TLS terminates at Nginx or another reverse proxy.
- HTTP is redirected to HTTPS.
- The engine listens on loopback only (`127.0.0.1`).
- Large request bodies are limited by Nginx (`client_max_body_size`).
- Long-running requests use extended read timeouts.

### 4.2 Authentication

- Basic Auth is available for simple deployments.
- OIDC/Keycloak is available for multi-user deployments.
- Trusted proxy headers are supported only from configured CIDRs.
- MCP clients use OAuth 2.1 when MCP server mode is enabled.
- Local development may disable authentication.

#### 4.2.1 Trusted Proxy
When `security.mode = "proxy"`:

```json
{
  "security": {
    "mode": "proxy",
    "trustedProxies": ["10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"]
  }
}
```

- Header-based identity (`X-Forwarded-User`, `X-Remote-User`, `Remote-User`, `X-Forwarded-Groups`, `X-Forwarded-Email`) is **only** read from requests whose client IP matches `trustedProxies` (CIDR or exact IP).
- `X-Forwarded-For` is inspected to determine the original client IP; trust is evaluated against configured ranges.
- If the request does not come from a trusted proxy, proxy headers are ignored.
- A `Principal` is created and stored in the request-scoped `PrincipalContext`.

#### 4.2.2 OIDC
When `security.mode = "oidc"`:

- Bearer tokens are validated against the configured issuer/JWKS.
- Issuer and audience must match.
- Clock skew is applied.
- Claims are mapped to `Principal` (id/name/roles/groups).
- Token validation failures result in `401 Unauthorized`.

### 4.3 Authorization

- Rule-based permission chain.
- `deny` overrides saved approvals.
- `ask` dominates `allow`.
- No matching rule means `ask`.
- Empty rule set means deny all.
- Agent-specific rules.
- Session-specific rules.
- Policy rules can deny tool use even when normal rules allow it.
- Permission hooks may change effect and message.

### 4.4 Tool security

- Tool snapshots are frozen per request.
- Tool names are resolved in the snapshot, not in the live registry.
- Agent-specific `tools.allow` and `tools.deny`.
- MCP tools are namespaced.
- Tool output is bounded and spilled to artifacts.
- Tool calls are logged as events.
- Tool execution has timeouts.

### 4.5 Filesystem security

- Workspace boundary via `FileAccess`.
- `external_directory` permission for paths outside the workspace.
- Read/write distinction.
- Artifact URI resolution.
- OS-level ownership and permissions.

### 4.6 Shell security

- Command scanning before execution.
- Subcommand extraction.
- Directory extraction from commands.
- HITL approval for dangerous commands.
- Background process registry.
- Process timeouts.
- OS-level process isolation.

### 4.7 Session security

- Sessions are identified by opaque IDs.
- **Session ownership**: Sessions are associated with the authenticated principal (or `anonymous` in dev mode). Cross-owner access is denied.
- **Claim-based execution**: Only the session owner can claim/run an active session; concurrent runs are prevented.
- **Recovery**: Orphaned tool calls are failed/marked `ORPHANED` on restart (at-least-once, never exactly-once).
- Approvals can have deadlines.
- Idle and suspended states are tracked.
- Identity is propagated via `PrincipalContext` and mirrored into `ToolContext.metadata()` for auditability.

### 4.8 MCP security

- MCP servers are namespaced.
- Remote MCP uses HTTP transport.
- OAuth 2.1 is supported for remote MCP clients.
- Tokens are stored per integration.
- Local MCP servers run as child processes.
- MCP resources are read-only.
- MCP tool calls go through the normal tool chain.

#### 4.8a A2A client and server

- **Client egress**: configured A2A endpoints (`engine.a2a.urls`, `engine.a2a.endpoints`) go through the same host/IP allowlists and TLS checks as other outbound HTTP. Remote agent responses are untrusted tool output and pass through the normal guardrail chain.
- **Server opt-in**: `engine.a2a.server.enabled=false` by default. While disabled, `/.well-known/agent-card.json`, tenant cards and `POST /` return `404`; the agent is not discoverable.
- **Public URL**: `engine.a2a.server.base-url` must match the externally reachable URL. It is published in the agent card and used by clients for JSON-RPC calls.
- **Authentication**: the A2A transport uses Vert.x routes, not JAX-RS. `A2AAuthenticationMechanism` supplies a Quarkus identity for these routes: `none` for local mode, HTTP Basic against `engine.auth.password`, or trusted-proxy headers from configured CIDRs. In `oidc` mode the Quarkus OIDC extension performs authentication.
- **Authorization**: `A2ATaskAuthorizationProvider` is fail-closed. Task reads/writes are restricted to the creating principal; `none` mode is intentionally single-user. Missing or anonymous identities are rejected when authentication is required.
- **Task isolation**: every A2A context (or task without a context) maps to its own QOC session. The session then runs through the regular permission, guardrail and audit pipeline, so A2A does not bypass QOC controls.
- **Agent card**: contains only name, description, version, capabilities and skills; no secrets, tokens or internal paths.
- **Limits**: executor pool, blocking-agent timeout and task store retention come from the A2A SDK configuration (`a2a.*`). Keep them bounded in production.

### 4.9 Guardrails

- Declarative guardrails in `settings.json`.
- Java SPI guardrails via CDI.
- Optional external webhook guardrails (see §4.9a).
- Pre-hooks may block or rewrite.
- Post-hooks may redact or replace.
- Permission hooks may change effect and message.
- Hooks are ordered and fail closed where appropriate.

#### 4.9a External webhook guardrails

- **Service**: External provider `qoc/webhook-guardrail/` (Quarkus) implementing the contract in [`docs/webhook.md`](webhook.md).
- **Activation**: `disabled` / `enabled` / `shadow`. QOC works normally when disabled. A webhook is only active if `guardrails.webhook.enabled = true` and `url` is set.
- **Events**: `tool.before`, `tool.after`, `permission.evaluate`.
- **PII**: Optional PII detection/redaction handled by the webhook service (categories: `email`, `phone`, `credit_card`, `iban`, `national_id`, `address`, `name`, `secret`). Categories are fixed. QOC transports and receives redacted results.
- **Security**: HTTPS required in production, HMAC-SHA256 signatures (`X-QOC-Signature`, `X-QOC-Timestamp`), optional bearer/mTLS, host allowlists, size limits.
- **Fail modes**: `closed-before-open-after` (default), `closed`, `open`, `shadow`. Timeouts apply per fail mode.
- **Idempotency**: Requests include `eventId` and `idempotencyKey`; decisions are not cached.
- **Chaining**: Multiple webhooks execute in order; the first `block`/`deny` stops the chain and redactions compose.
- **Logging**: Redacted audit logs, no secrets/PII in logs by default. Correlation via trace/session/event/idempotency keys.
- **Data minimization**: Only required data sent; large payloads can use `payloadMode: metadata-only` for `tool.after`.

#### 4.9b Webhook integration in QOC

- **Configuration**: Parsed by `EngineConfig.WebhookConfig` from `guardrails.webhook` (or `guardrails.webhooks[0]` for compatibility). Disabled if missing/invalid.
- **Registry**: `de.augmentia.oc.engine.guardrail.GuardrailRegistry` provides the active webhook config.
- **Client**: `de.augmentia.oc.engine.guardrail.WebhookClient` signs outgoing requests with HMAC-SHA256 when `authType = hmac-sha256` and signs with `X-QOC-Timestamp`, `X-QOC-Signature`, `X-QOC-Event`, `X-QOC-Event-Id`, `X-QOC-Idempotency-Key`.
- **Secret resolution**: `secretRef` resolved via environment variables (direct or uppercased/normalized). Secrets never stored in `settings.json`.
- **Egress protection**: Only `allowedHosts` (and planned `allowedIps`) should be contacted. TLS and hostname verification required in production.
- **Wiring**: Webhook hooks are applied in order `tool.before` -> execute -> `tool.after` and `permission.evaluate`, combined with built-in/declarative/SPI guardrails. Decisions follow fail modes on timeout/error/network failure.
- **No caching**: Every event is evaluated when it occurs.

### 4.10 Secrets and credentials

- Secrets come from environment variables.
- `settings.json` must not contain secrets.
- Webhook secrets are referenced via `secretRef` and resolved from the environment (e.g. `QOC_WEBHOOK_SECRET` or named env vars). Never log secrets.
- Credentials are stored in the database.
- MCP OAuth tokens should be encrypted at rest.
- Basic Auth passwords are configured via environment.
- API keys are never logged.
- Webhook request/response bodies are redacted in logs; only metadata is logged by default.

### 4.11 Observability and audit

- OpenTelemetry spans for prompts, steps and tools.
- Structured logs for permission decisions.
- Guardrail decision logging (webhook + SPI + declarative), redacted.
- Cost and token tracking.
- Session history and event replay.
- Error classification.
- `/api/metrics` and `/api/audit` are available for monitoring; auth requirements follow the configured auth mode but should remain reachable for health/monitoring where appropriate.
- Correlation via `traceId`, `spanId`, `sessionId`, `eventId`, `idempotencyKey`.

### 4.12 Backup and recovery

- SQLite/PostgreSQL persistence.
- Append-only event journal.
- Claim-based recovery.
- Flyway migrations.
- Snapshot/revert support.
- Retention policies.

---

## 5. Optional OS access rights and roles

This section is optional. It describes how OS-level access rights can be used
in addition to QOC's engine-level security.

### 5.1 Dedicated OS user

QOC should run as a dedicated `qoc` user:

```bash
useradd --system --home /var/lib/qoc --shell /usr/sbin/nologin qoc
```

Properties:

- no login shell,
- owns only engine data,
- cannot access other users' files,
- process isolation via systemd.

### 5.2 Directory model

```text
/var/lib/qoc/
├── data/                  # SQLite DB, snapshots, tool output
├── workspaces/            # project directories
├── log/                   # log files
└── .opencode/plan/        # plan files
```

Permissions:

- `data/`: `700`, qoc only
- `workspaces/`: `750`, qoc + group
- `log/`: `750`, qoc + group

### 5.3 OS groups for roles

OS groups can represent coarse roles:

| Group | Purpose |
|---|---|
| `qoc` | Engine process owner |
| `qoc-admin` | Operators with read access to logs/config |
| `qoc-developer` | Developers with workspace access |
| `qoc-guest` | Read-only workspace access |

Example:

```bash
groupadd qoc-admin
groupadd qoc-developer
groupadd qoc-guest

usermod -aG qoc-admin torsten
usermod -aG qoc-developer torsten
```

### 5.4 Filesystem ACLs

ACLs can grant fine-grained access without changing ownership:

```bash
setfacl -m u:torsten:rx /var/lib/qoc/workspaces/project-a
setfacl -m g:qoc-developer:rx /var/lib/qoc/workspaces/project-a
setfacl -m u:torsten:r /var/lib/qoc/log/qoc.log
```

Use ACLs for:

- operator log access,
- developer workspace access,
- read-only guest access,
- backup user access.

### 5.5 systemd hardening

```ini
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/qoc
PrivateTmp=true
User=qoc
Group=qoc
```

This prevents privilege escalation and restricts writes.

### 5.6 SELinux / AppArmor (optional)

On hardened systems, use SELinux or AppArmor profiles to restrict:

- network access,
- file access,
- process execution,
- capability use.

This is optional but recommended for high-security environments.

### 5.7 OS roles vs. application roles

OS roles and application roles are separate:

| Layer | Example |
|---|---|
| OS | `qoc-developer` can read workspace files |
| Application | `developer` agent can use `read`, `write`, `edit` |
| Application | `researcher` agent can use `read`, `websearch` |

OS roles protect the host. Application roles protect the agent. Both are needed.

---

## 6. Threat model

### 6.1 In scope

- unauthorized access to the engine,
- session hijacking,
- tool misuse,
- secret leakage,
- filesystem escape,
- shell abuse,
- MCP token theft,
- PII leakage,
- denial of service.

### 6.2 Out of scope

- physical access to the host,
- compromised OS kernel,
- malicious root user,
- side-channel attacks at hardware level.

---

## 7. Non-goals

- QOC does not sandbox the agent.
- QOC does not provide multi-tenant isolation beyond OS and engine policy.
- QOC does not guarantee exactly-once execution.
- QOC does not replace a WAF or network firewall.
- QOC does not provide its own user directory; use Keycloak or another IdP.

---

## 8. Configuration examples

### 8.1 Development (no auth)

```json
{
  "auth": {
    "password": ""
  }
}
```

All callers are treated as `anonymous` with all rights.

### 8.2 Basic Auth

```json
{
  "auth": {
    "password": "change-me"
  }
}
```

### 8.3 Keycloak (OIDC)

```json
{
  "security": {
    "mode": "oidc",
    "oidc": {
      "issuer": "http://localhost:8180/realms/qoc",
      "clientId": "qoc-engine",
      "clientSecret": "${QOC_OIDC_CLIENT_SECRET}",
      "scopes": ["openid", "profile", "email", "qoc:agent:run"]
    }
  }
}
```

### 8.4 Trusted Proxy

```json
{
  "security": {
    "mode": "proxy",
    "trustedProxies": ["127.0.0.1", "::1", "10.0.0.0/8"]
  }
}
```

### 8.5 Hardened production

```json
{
  "auth": {
    "password": "${OPENCODE_SERVER_PASSWORD}"
  },
  "security": {
    "mode": "oidc",
    "oidc": {
      "issuer": "https://idp.example/realms/qoc",
      "clientId": "qoc-engine",
      "clientSecret": "${QOC_OIDC_CLIENT_SECRET}"
    },
    "mcp": {
      "server": {
        "oauth": {
          "enabled": true
        }
      }
    }
  },
  "guardrails": {
    "enabled": true,
    "webhook": {
      "enabled": true,
      "mode": "enforce",
      "url": "https://guardrail.example/qoc/guardrail",
      "timeoutMs": 1500,
      "failMode": "closed-before-open-after",
      "maxPayloadBytes": 65536,
      "payloadMode": "metadata-only",
      "pii": {
        "enabled": true,
        "defaultMode": "redact",
        "categories": {
          "email": "redact",
          "phone": "redact",
          "credit_card": "block",
          "secret": "block"
        },
        "minConfidence": 0.8
      },
      "security": {
        "tlsRequired": true,
        "authType": "hmac-sha256",
        "secretRef": "qoc-webhook-secret",
        "allowedHosts": ["guardrail.example"],
        "verifyHostname": true,
        "clockSkewSeconds": 60
      },
      "logging": {
        "enabled": true,
        "logRequests": true,
        "logResponses": "redacted",
        "logPii": false,
        "retentionDays": 30
      }
    }
  }
}
```

---

## 9. Acceptance criteria

A production deployment is acceptable when:

- [ ] TLS is enabled.
- [ ] Authentication is enabled.
- [ ] The engine runs as a dedicated OS user.
- [ ] Filesystem permissions are restrictive.
- [ ] Secrets are not in `settings.json`.
- [ ] Shell commands are scanned.
- [ ] Tool output is bounded.
- [ ] Sessions have owners and claiming is enforced.
- [ ] MCP OAuth is enabled when MCP is exposed.
- [ ] Guardrails are configured.
- [x] Webhook only active when configured; disabled without side effects.
- [x] Webhook TLS + hostname verification + HMAC-SHA256 configured if active.
- [x] `secretRef` used for webhook secrets; no secrets in `settings.json`.
- [x] Webhook fail-mode tested for `tool.before`/`permission.evaluate` (fail-closed by default).
- [ ] `payloadMode` set according to data minimization needs.
- [ ] Webhook logs are redacted (no secrets/PII).
- [x] Trusted proxy only accepts headers from configured CIDRs.
- [ ] OIDC tokens validated (JWKS/issuer/audience) when enabled.
- [x] Logs are redacted.
- [ ] Backups are tested.
- [ ] Recovery is tested.
- [x] Metrics and audit logs are available.

---

## 10. Open decisions

1. Should Keycloak be the default IdP or only one option?
2. Should OS groups be mapped to QOC roles automatically?
3. Should ACLs be managed by QOC or by deployment tooling?
4. Should SELinux/AppArmor profiles be shipped with QOC?
5. Should Basic Auth be discouraged/replaced by OIDC or trusted proxy in production?
6. Should MCP OAuth be mandatory when `/mcp` is public/exposed?
7. Should `secretRef` be resolved from a dedicated secret store (Vault/KMS) in addition to env vars?
