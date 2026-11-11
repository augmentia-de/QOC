# Open Issues & Follow-Ups

Tracking document for known gaps, open questions, and planned improvements.
Updated: 2026-11-11.

## Security / OIDC

### Resolved
- ✅ OIDC principal propagation to `ToolContext` (Phase 1).
- ✅ ToolAuthorizer principal metadata + audit context (Phase 2).
- ✅ `HmacToolTokenService` (short-lived, origin-bearing tokens) + config allowlist (Phase 3).
- ✅ `SecureRestTool` + protected admin endpoint `/api/admin/ping` (Phase 4).
- ✅ Subagent principal propagation (Phase 5).
- ✅ MCP token forwarding (`McpDiscoveryClient`).
- ✅ A2A token forwarding (`A2ATool` – Bearer header from principal/issued token).

### Open
- [ ] **A2A downstream token validation**: Remote A2A agents must validate the forwarded token (issuer/audience). Currently only forwarded, not verified end-to-end.
- [ ] **Per-service allowlist**: Token issuance is gated globally (`engine.security.tool-token.enabled`). Per-tool/per-service allowlist (`engine.security.tool-token.allowed-services`) not yet enforced in `SecureRestTool`/`McpTool`.
- [ ] **Token refresh in long-running sessions**: Subagent/Task principal propagation does not refresh expired OIDC tokens.
- [ ] **Audit retention with principal filter**: Audit queries by principal not yet implemented.
- [ ] **Per-user rate limiting** on tool calls.
- [ ] **Resource-level ACL** (workspace files per user) beyond directory scoping.

## Tooling

- [ ] **SecureRestTool wiring in tool registry**: registered, but not exposed via MCP (only internal agent tools). Decide if it should be an MCP-exposed tool.
- [ ] **FirecrawlTool** token forwarding (low priority – uses own API key).
- [ ] **Shell/File tools** principal-aware audit (currently no downstream auth needed).

## Workflow / UI

- [ ] **Workflow-runner in Quarkus native**: port `/api/workflows*` endpoints into Quarkus (see `Quarkus-workflow.md` plan). Currently standalone on port 8097.
- [ ] **SPA fallback** for workflow-ui and temporal-ui in Quarkus native (Hash-Router or JAX-RS catch-all).
- [ ] **Both UIs served** from single native binary.
- [ ] **React vs. Vanilla HTML**: decide on lighter UI variant for small VMs.

## Deployment

- [ ] **Native build RAM**: GraalVM needs 4–8 GB at build time; CI pipeline not yet set up.
- [ ] **VM deployment docs** for native + React assets.
- [ ] **OIDC in native builds**: `QUARKUS_OIDC_ENABLED` is build-time; ensure CI sets it.

## Testing

- [ ] **End-to-end OIDC prompt test** in container (blocked by preflight/env config).
- [ ] **A2A OIDC integration test** (requires remote A2A agent with token validation).
- [ ] **MCP token forwarding integration test** against a real MCP server.

## Docs

- [ ] Update `docs/security.md` with token pass-through flow.
- [ ] Document `securerest` tool in spec/tools.
