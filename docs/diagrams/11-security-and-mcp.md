# 11 – Security, MCP Server, and Access Control

## Purpose

This diagram summarizes the current and planned security architecture of QOC,
including HTTP authentication, engine policy, MCP client/server modes, and
user-based access control.

```mermaid
flowchart TB
  User["User / Browser / API Client"]:::ext
  Edge["nginx<br/>TLS · Basic Auth"]:::ext
  Api["Quarkus HttpApi<br/>REST · SSE"]:::mixed
  Auth["BasicAuthFilter<br/>single user today"]:::det
  Engine["Engine<br/>Prompt Loop · Sessions"]:::mixed
  Principal["Principal<br/>planned"]:::mixed
  Perm["PermissionEngine<br/>Rules · Ask · Deny"]:::det
  Tools["ToolRuntime"]:::det
  McpClient["MCP Client<br/>remote servers"]:::mixed
  McpServer["QOC MCP Server<br/>/mcp · qoc_run_agent<br/>+ qoc_run_agent_with_config"]:::mixed
  TokenStore["Credential / Token Store<br/>per integration today"]:::store
  Guardrails["Guardrails<br/>rules · SPI · webhook"]:::det
  Os["OS Hardening<br/>qoc user · systemd · ACLs"]:::ext

  User --> Edge
  Edge --> Auth
  Auth --> Api
  Api --> Engine
  Engine --> Principal
  Principal --> Perm
  Engine --> Tools
  Tools --> Guardrails
  Tools --> McpClient
  McpClient --> TokenStore
  User -->|"MCP"| McpServer
  McpServer --> Engine
  Os -.->|"hard boundary"| Engine

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef llm fill:#ffe0b2,stroke:#e65100,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
  classDef store fill:#e3f2fd,stroke:#1565c0,stroke-width:1px,color:#111;
```

## Current state

| Area | Current state |
|---|---|
| HTTP auth | Basic, OIDC (Keycloak/Auth0), Trusted Proxy, MCP-OAuth, NONE |
| User identity | `Principal` with roles/groups (BasicAuthFilter, OidcPrincipalProducer, TrustedProxyFilter) |
| Role-based authorization | `requireRole("admin")` at 10 admin-only endpoints; `@RolesAllowed` via custom filter |
| Session ownership | `isOwner` check on session access; admin sees all sessions |
| MCP client OAuth | Partial: discovery, code exchange, refresh helper |
| MCP server | `qoc_run_agent` + `qoc_run_agent_with_config` (stateless subagent) |
| MCP server OAuth | Prepared but inactive by default (503) |
| Token storage | Plaintext, per integration, not per user |
| Guardrails | `GuardrailRegistry` + `GuardrailHooks` + webhook service |
| OS security | Documented and deployed (Docker hardening, bwrap, non-root) |

## Planned state

| Area | Planned improvement |
|---|---|
| HTTP auth | OIDC/Keycloak or trusted proxy headers |
| User identity | `Principal` from auth layer |
| MCP client OAuth | RFC 9728 discovery, PKCE, `resource`, state, refresh |
| MCP server OAuth | OAuth 2.1 resource server, audience and scope validation |
| Token storage | Per-user encrypted tokens |
| Session ownership | `ownerId` on `SessionInfo` and DB |
| Guardrails | Declarative, SPI and webhook guardrails |
| OS security | Keep as hard boundary |

## Rules

1. **Authentication is optional.** Without auth, all callers are `opencode`.
2. **Basic Auth is single-user.** It is not a multi-user identity system.
3. **Hooks must not replace authentication.** They authorize; auth identifies.
4. **MCP tokens must not be passed through.** Downstream calls need their own tokens.
5. **OS security remains the hard boundary.** Engine policy is not a sandbox.

## Sources

- `quarkus/src/main/java/de/augmentia/oc/engine/http/BasicAuthFilter.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/McpServerResource.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/mcp/McpDiscoveryClient.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/mcp/McpOAuth.java`
- `docs/guides/OS-based-security.md`
- `docs/security.md`
- `docs/webhook.md`
