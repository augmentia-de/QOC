# Webhook Guardrail Service Specification

> **Status:** Specification draft · 2026-10-26
> **Scope:** Optional webhook-based guardrail service for QOC
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)
> **Related:** `docs/analysis/pi-learnings.md`,
> `docs/analysis/nessy-learnings.md`,
> `docs/issues/qoc-findings.md`

---

## 1. Purpose

This specification defines an optional external **Webhook Guardrail Service** for
QOC. The service is called during tool execution and permission evaluation and
can make or influence guardrail decisions without changing QOC code or
redeploying the engine.

The service is intentionally external and policy-oriented. It is **not** a
security sandbox and does not replace QOC's built-in permission chain, shell
scanner, file boundaries, or OS hardening.

The service has three explicit capabilities:

1. **Optional PII activation** – PII detection and redaction can be enabled or
   disabled per deployment, agent, session, or tool.
2. **Configurable security** – transport, authentication, signatures, allowlists,
   timeouts and fail modes are configurable.
3. **Special response logging** – guardrail decisions and redacted outcomes are
   logged in a privacy-preserving, auditable way.

---

## 2. Scope and non-goals

### In scope

- HTTP webhook contract for QOC guardrail events
- Optional PII detection/redaction
- Configurable security controls
- Structured request/response logging
- Timeout, retry and fail-mode behavior
- Idempotency and replay protection
- QOC integration points

### Out of scope

- Replacing QOC's built-in permission engine
- Arbitrary code execution inside QOC
- Storing full file contents or secrets in logs
- Exactly-once execution guarantees
- Model-provider integration

---

## 3. Activation and PII mode

### 3.1 Service activation

The webhook service is optional. QOC must work normally when it is disabled.

Activation levels:

| Level | Meaning |
|---|---|
| `disabled` | No webhook calls are made |
| `enabled` | Webhook is called according to event configuration |
| `shadow` | Webhook is called, but its decision is logged only and not enforced |

### 3.2 PII activation

PII handling is optional and independent from service activation.

Modes:

| Mode | Behavior |
|---|---|
| `off` | No PII processing |
| `detect` | PII is detected and reported, but not modified |
| `redact` | PII is replaced with placeholders |
| `block` | The tool result or request is blocked if PII is detected |

PII activation can be configured globally, per agent, per session, per tool, or
per event.

### 3.3 PII categories

The service should support at least these categories:

- `email`
- `phone`
- `credit_card`
- `iban`
- `national_id`
- `address`
- `name`
- `secret` (API keys, tokens, passwords)

PII categories are fixed by this specification. They are **not** freely
extensible by deployment in the current version.

Each category can have its own mode and confidence threshold.

---

## 4. Configuration

### 4.1 QOC configuration

QOC configuration example:

```json
{
  "guardrails": {
    "webhook": {
      "enabled": true,
      "mode": "enforce",
      "url": "https://policy.example/qoc/guardrail",
      "timeoutMs": 1500,
      "failMode": "closed-before-open-after",
      "maxPayloadBytes": 65536,
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
        "allowedHosts": ["policy.example"],
        "allowedIps": [],
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

### 4.2 Configuration fields

| Field | Type | Required | Description |
|---|---|---|---|
| `enabled` | boolean | yes | Enables webhook calls |
| `mode` | string | yes | `disabled`, `enabled`, `shadow` |
| `url` | string | yes | HTTPS endpoint |
| `timeoutMs` | integer | yes | Maximum wait time |
| `failMode` | string | yes | Failure behavior |
| `maxPayloadBytes` | integer | no | Maximum request size |
| `payloadMode` | string | no | `full` or `metadata-only` for `tool.after` |
| `pii.enabled` | boolean | no | Enables PII processing |
| `pii.defaultMode` | string | no | Default PII mode |
| `pii.categories` | object | no | Per-category mode |
| `pii.minConfidence` | number | no | Minimum confidence for action |
| `security.*` | object | yes | Security settings |
| `logging.*` | object | yes | Logging settings |

---

## 5. Endpoint contract

### 5.0 Sequential webhooks

Multiple webhook entries may be configured. They are executed in the
configured order. The first `block` or `deny` decision stops the chain. For
`tool.after`, redactions and replacements are composed in order.

### 5.1 Request

QOC sends an HTTP `POST` request with `Content-Type: application/json`.

Headers:

```http
Content-Type: application/json
X-QOC-Event: tool.before
X-QOC-Event-Id: evt_...
X-QOC-Timestamp: 2026-10-26T12:00:00Z
X-QOC-Signature: sha256=<hex>
X-QOC-Idempotency-Key: idem_...
```

Body:

```json
{
  "eventId": "evt_...",
  "eventType": "tool.before",
  "timestamp": "2026-10-26T12:00:00Z",
  "sessionId": "ses_...",
  "agentId": "agent_...",
  "toolName": "read",
  "callId": "call_...",
  "idempotencyKey": "idem_...",
  "input": {
    "path": "src/Foo.java"
  },
  "context": {
    "workspace": "/data/workspace",
    "external": false
  },
  "pii": {
    "enabled": true,
    "categories": ["email", "secret"]
  }
}
```

For `tool.after`, the body additionally contains:

```json
{
  "result": {
    "content": [
      {
        "type": "text",
        "text": "..."
      }
    ],
    "metadata": {
      "exitCode": 0
    }
  }
}
```

For `permission.evaluate`, the body contains:

```json
{
  "action": "read",
  "resources": ["/data/workspace/src"],
  "effect": "ask",
  "metadata": {
    "tool": "read",
    "path": "src/Foo.java"
  }
}
```

### 5.1a Payload granularity

For `tool.after`, `payloadMode` controls how much result data is sent:

| Mode | Behavior |
|---|---|
| `full` | Send redacted tool result content and metadata |
| `metadata-only` | Send only metadata, counts, hashes and PII categories |

### 5.2 Response

The service must respond with HTTP `200 OK` and a JSON body.

#### Tool before response

```json
{
  "decision": "allow",
  "reason": null,
  "input": null,
  "pii": {
    "detected": false,
    "categories": [],
    "action": "none"
  }
}
```

Allowed decisions:

| Decision | Meaning |
|---|---|
| `allow` | Execute the tool normally |
| `block` | Do not execute the tool |
| `rewrite` | Replace `input` and execute with the rewritten input |

#### Tool after response

```json
{
  "decision": "allow",
  "content": [
    {
      "type": "text",
      "text": "redacted output"
    }
  ],
  "metadata": {
    "redacted": true
  },
  "pii": {
    "detected": true,
    "categories": ["email"],
    "action": "redact"
  }
}
```

Allowed decisions:

| Decision | Meaning |
|---|---|
| `allow` | Keep the original result |
| `redact` | Replace content with redacted content |
| `replace` | Replace the whole result |
| `block` | Convert the result into a tool error |

#### Permission response

```json
{
  "decision": "deny",
  "effect": "deny",
  "message": "Denied by external policy.",
  "pii": {
    "detected": false,
    "categories": [],
    "action": "none"
  }
}
```

Allowed decisions:

| Decision | Meaning |
|---|---|
| `allow` | Allow the permission request |
| `ask` | Ask the user |
| `deny` | Deny the permission request |

### 5.3 Response rules

- The response must be valid JSON.
- Unknown fields must be ignored.
- Missing optional fields must use safe defaults.
- A `block` before execution becomes a model-visible tool error by default.
- A `block` after execution becomes a tool error.
- A timeout before execution is treated as `block`.
- A timeout after execution is treated as `allow` with the original result.
- The service must not return executable code or instructions to QOC.

---

## 6. Configurable security

### 6.1 Transport security

Requirements:

- HTTPS is required in production.
- TLS certificate validation is enabled by default.
- Hostname verification is enabled by default.
- HTTP is allowed only for local development and must be explicitly enabled.

### 6.2 Authentication

Supported authentication types:

| Type | Description |
|---|---|
| `none` | No authentication; development only |
| `bearer` | Bearer token in `Authorization` header |
| `hmac-sha256` | HMAC signature over timestamp and body |
| `mtls` | Mutual TLS |

### 6.3 HMAC signature

For `hmac-sha256`, QOC computes:

```text
signature = HMAC_SHA256(secret, timestamp + "." + requestBody)
```

The service must verify:

- timestamp is within `clockSkewSeconds`,
- signature matches exactly,
- request body was not modified.

QOC sends:

```http
X-QOC-Timestamp: 2026-10-26T12:00:00Z
X-QOC-Signature: sha256=<hex>
```

### 6.4 Network restrictions

QOC should support:

- `allowedHosts`
- `allowedIps`
- DNS resolution validation
- private-network restrictions where appropriate

The service should reject requests from unexpected hosts.

### 6.5 Fail modes

| Fail mode | Before execution | After execution | Permission |
|---|---|---|---|
| `closed-before-open-after` | block | allow original | deny |
| `closed` | block | block | deny |
| `open` | allow | allow original | allow |
| `shadow` | log only | log only | log only |

The default fail mode is `closed-before-open-after`.

### 6.6 Security invariants

- Secrets must never be logged.
- Request and response payloads must be size-limited.
- The service must not be able to execute commands or access the filesystem.
- The service must not receive QOC credentials unless explicitly configured.
- Guardrail decisions must be auditable.

---

## 7. Special response logging

### 7.1 Logging goals

Response logging must provide:

- auditability,
- debugging,
- privacy protection,
- performance visibility,
- compliance evidence.

### 7.2 Log levels

| Level | Content |
|---|---|
| `off` | No webhook logging |
| `minimal` | Event ID, decision, duration, status code |
| `standard` | Minimal plus tool name, agent, redacted categories |
| `full` | Standard plus redacted request/response payload |
| `debug` | Full payload without redaction; development only |

### 7.3 Required log fields

Every webhook log entry should include:

```json
{
  "timestamp": "2026-10-26T12:00:00Z",
  "eventId": "evt_...",
  "eventType": "tool.before",
  "sessionId": "ses_...",
  "agentId": "agent_...",
  "toolName": "read",
  "callId": "call_...",
  "idempotencyKey": "idem_...",
  "decision": "block",
  "reason": "PII detected",
  "durationMs": 42,
  "httpStatus": 200,
  "piiDetected": true,
  "piiCategories": ["email", "secret"],
  "piiAction": "redact",
  "failMode": "closed-before-open-after",
  "redacted": true
}
```

### 7.4 Redaction rules

Logs must never contain:

- API keys
- passwords
- bearer tokens
- credit card numbers
- IBANs
- raw secret values
- full file contents unless explicitly enabled for debugging

Redaction placeholders:

```text
[REDACTED:email]
[REDACTED:secret]
[REDACTED:credit_card]
```

### 7.5 Response logging modes

| Mode | Behavior |
|---|---|
| `none` | Do not log response body |
| `redacted` | Log response body with PII and secrets redacted |
| `full` | Log full response body; development only |

### 7.6 Correlation

Logs should be correlated with:

- OpenTelemetry trace ID
- span ID
- session ID
- event ID
- idempotency key

This allows reconstruction of a guardrail decision without storing sensitive content.

---

## 8. PII handling

### 8.1 Detection

The service should detect PII in:

- tool input
- tool result content
- permission metadata
- error messages if configured

### 8.2 Actions

| Action | Behavior |
|---|---|
| `none` | No action |
| `detect` | Report only |
| `redact` | Replace with placeholder |
| `block` | Block the operation |

PII redaction is performed by the webhook service, not by QOC. QOC transports
and receives redacted results.
| ~~`hash`~~ | Not used in the current version |

Redacted values are replaced by placeholders only. Hash-based correlation is
not used in the current version.
### 8.3 Redaction format

Examples:

```text
user@example.com -> [REDACTED:email]
+49 123 456789 -> [REDACTED:phone]
sk-abc123 -> [REDACTED:secret]
```

### 8.4 Confidence

Each detection should include a confidence score:

```json
{
  "category": "email",
  "valueHash": "sha256:...",
  "confidence": 0.99,
  "action": "redact"
}
```

Only detections above `minConfidence` should trigger an action.

### 8.5 False positives

The service should allow:

- allowlists,
- context-aware suppression,
- category-specific thresholds,
- manual override by trusted operators.

---

## 9. Error handling and fallback

### 9.1 HTTP errors

| HTTP status | QOC behavior |
|---|---|
| `200` | Apply response |
| `400` | Log and apply fail mode |
| `401` / `403` | Log security error; apply fail mode |
| `404` | Log and apply fail mode |
| `408` / `429` | Apply fail mode; respect `Retry-After` if present |
| `5xx` | Apply fail mode |

### 9.2 Network errors

Network errors before execution:

- apply fail mode,
- log as `webhook_unreachable`,
- do not retry synchronously more than once.

Network errors after execution:

- log the error,
- keep the original result,
- do not block the user.

### 9.3 Malformed responses

If the response is invalid JSON or violates the schema:

- log `invalid_webhook_response`,
- apply fail mode,
- never execute with an unvalidated rewritten input.

---

## 10. Idempotency and retries

### 10.1 Idempotency

Every request includes:

- `eventId`
- `idempotencyKey`
- timestamp

The service should treat repeated requests with the same `eventId` as the same
event.

### 10.2 Retries

QOC should not retry guardrail decisions synchronously. If the service fails,
QOC applies the configured fail mode immediately.

Asynchronous retries may be used only for logging or audit delivery, never for
changing an already applied decision.

---

## 11. Performance and limits

Recommended defaults:

| Setting | Default |
|---|---|
| `timeoutMs` | 1500 |
| `maxPayloadBytes` | 65536 |
| `maxResponseBytes` | 65536 |
| `maxConcurrency` | 32 |
| `circuitBreakerThreshold` | 5 consecutive failures |
| `circuitBreakerCooldownSeconds` | 30 |

Performance requirements:

- The webhook must not block the event loop.
- Calls should run on worker threads.
- Large tool results should be truncated before being sent.
- The service should support connection reuse.

---

## 12. Observability

The service and QOC should expose:

- request count
- decision count by type
- block count
- redaction count
- PII category counts
- latency percentiles
- timeout count
- circuit breaker state
- error count by type

Metrics should be labeled by:

- event type
- tool name
- agent
- decision
- PII category

---

## 13. Examples

### 13.1 Block a secret in tool input

Request:

```json
{
  "eventType": "tool.before",
  "toolName": "write",
  "input": {
    "path": "config.yml",
    "content": "api_key: sk-abc123"
  }
}
```

Response:

```json
{
  "decision": "block",
  "reason": "Secret detected in tool input",
  "pii": {
    "detected": true,
    "categories": ["secret"],
    "action": "block"
  }
}
```

### 13.2 Redact PII in tool result

Request:

```json
{
  "eventType": "tool.after",
  "toolName": "read",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Contact: user@example.com"
      }
    ]
  }
}
```

Response:

```json
{
  "decision": "redact",
  "content": [
    {
      "type": "text",
      "text": "Contact: [REDACTED:email]"
    }
  ],
  "pii": {
    "detected": true,
    "categories": ["email"],
    "action": "redact"
  }
}
```

### 13.3 Permission decision

Request:

```json
{
  "eventType": "permission.evaluate",
  "action": "shell",
  "resources": ["rm -rf /tmp/*"],
  "effect": "ask"
}
```

Response:

```json
{
  "decision": "deny",
  "effect": "deny",
  "message": "Destructive shell command denied by policy",
  "pii": {
    "detected": false,
    "categories": [],
    "action": "none"
  }
}
```

---

## 14. Compliance and data minimization

The service must follow data-minimization principles:

- Send only the data required for the decision.
- Truncate large inputs and results.
- Avoid sending full file contents unless explicitly configured.
- Avoid sending secrets.
- Redact PII before logging.
- Retain logs only for the configured period.
- Support deletion of audit logs where required by policy.

The service should document:

- which fields are sent,
- which fields are logged,
- retention periods,
- access controls for logs.

---

## 15. QOC integration

### 15.1 Integration points

QOC calls the webhook at:

1. `tool.before`
2. `tool.after`
3. `permission.evaluate`

### 15.2 Hook composition

The webhook is one implementation of QOC's guardrail SPI. It can be combined
with:

- declarative rules,
- Java SPI guardrails,
- built-in permission rules.

Ordering:

1. built-in rules,
2. declarative guardrails,
3. in-process SPI guardrails,
4. webhook guardrail.

### 15.3 Fail mode integration

Before execution:

- webhook timeout or error applies `failMode`,
- default is `block`.

After execution:

- webhook timeout or error keeps the original result,
- redaction is not applied unless the response was received.

Permission evaluation:

- webhook timeout or error applies `failMode`,
- default is `deny`.

### 15.4 Configuration reload

When `/api/config/reload` is called:

- QOC reloads `settings.json`,
- rebuilds the guardrail registry,
- applies new webhook settings without process restart.

---

## 16. Acceptance criteria

The webhook service is acceptable when:

- [ ] It can be disabled without affecting QOC.
- [ ] PII detection can be enabled or disabled.
- [ ] PII categories can be configured independently.
- [ ] Security settings are enforced.
- [ ] HMAC signatures are verified.
- [ ] Timeouts are respected.
- [ ] Fail modes work as specified.
- [ ] Responses are validated before use.
- [ ] Logs are redacted.
- [ ] Idempotency keys are preserved.
- [ ] Metrics are exposed.
- [ ] Native Quarkus builds remain supported.

---

## 17. Resolved design decisions

Based on the open decisions of this specification, the following design
decisions are fixed:

1. **PII redaction happens outside QOC.** Redaction is performed by the
   webhook service, not by QOC. QOC only transports and receives redacted
   results.
2. **Payload granularity is configurable.** For `tool.after`, QOC can send
   either full tool results or metadata only, controlled by `payloadMode`.
3. **Webhook decisions are not cached.** Each event is evaluated when it
   occurs.
4. **Multiple webhooks are supported in sequence.** Webhooks are executed in
   configured order; the first blocking or denying decision stops the chain.
5. **PII categories are not freely extensible by deployment.** The category
   set is fixed by this specification.
6. **No correlation hashes are used in the current version.** Redacted values
   are replaced by placeholders only.

### 17.1 Resulting configuration defaults

| Decision | Resulting default |
|---|---|
| PII redaction location | webhook service |
| `tool.after` payload | configurable via `payloadMode` |
| Decision caching | disabled |
| Multiple webhooks | sequential execution |
| PII category extensibility | fixed categories |
| Correlation hashes | disabled |
