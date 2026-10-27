# E2E Security Test Plan

## Ziel
End-to-End-Verifikation aller Security-Pfade von QOC:
Authentifizierung, Principal-Propagation, Trusted Proxy, OIDC (optional),
Webhook-Guardrail, Egress-Schutz, Session-Ownership und MCP-OAuth.

## Voraussetzungen
- QOC im Test-Profil mit PostgreSQL/SQLite
- Optionaler Keycloak-Container für OIDC
- Webhook-Guardrail-Service (lokal oder Testcontainer)
- Testbenutzer: `opencode` / Passwort aus Env
- Trusted-Proxy-CIDR: `127.0.0.1/32`

## Szenarien

### 1. Basic Auth
1. Request ohne Credentials -> 401 + `WWW-Authenticate`
2. Falsches Passwort -> 401
3. Korrekte Credentials -> 200, Principal `opencode`

### 2. Principal-Propagation
1. Session-Erstellung mit Basic Auth -> Session-Verzeichnis/Owner entspricht Principal
2. `ToolContext.metadata()` enthält `principalId`
3. Webhook-Payload enthält `principalId`/`principalName`

### 3. Trusted Proxy
1. Request von nicht vertrauenswürdiger IP mit `X-Forwarded-User` -> Header ignoriert
2. Request von vertrauenswürdiger IP (127.0.0.1) mit `X-Forwarded-User: alice` -> Principal `alice`
3. CIDR-Match für `10.0.0.0/8`

### 4. OIDC (optional, Profil `oidc`)
1. Ungültiger/abgelaufener Token -> 401
2. Gültiger Token -> Principal aus Claims (`sub`, `preferred_username`, `realm_access.roles`)
3. Issuer/Audience-Mismatch -> 401

### 5. Webhook-Guardrail
1. `guardrails.webhook` deaktiviert -> keine HTTP-Calls, normale Ausführung
2. `tool.before` mit Secret -> Block, Tool wird nicht ausgeführt
3. `permission.evaluate` mit Secret -> deny
4. `tool.after` mit PII -> redact/replace
5. Fail-Mode `closed-before-open-after`: Webhook down -> before block, after allow
6. Fail-Mode `open`: Webhook down -> allow
7. Egress: nicht erlaubter Host -> Block/Fail-Closed
8. Egress: nicht erlaubte IP -> Block
9. TLS erforderlich: HTTP-URL bei `tlsRequired=true` -> Block

### 6. Session Ownership
1. User A kann eigene Sessions listen/lesen
2. User B erhält 403 auf Sessions von User A

### 7. MCP OAuth
1. `/mcp` mit `security.mode=mcp-oauth` ohne Token -> 401
2. Mit gültigem Token -> Tool-Ausführung

## Automatisierung
- Quarkus-Integrationstests mit `@QuarkusTest` und Testprofilen
- Testcontainers für PostgreSQL + Keycloak + Webhook-Guardrail
- RestAssured für HTTP-Assertions
- HMAC-Signaturen in Tests berechnen (Test-Secret)

## Abnahmekriterien
- Alle Szenarien grün
- Keine Secrets/PII in Logs
- Webhook deaktiviert => keine Netzwerkaufrufe
- Egress-Verletzungen fail-closed
