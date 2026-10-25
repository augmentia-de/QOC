# 09 – MCP Server (QOC as MCP provider)

> **Subject:** Exposing QOC as an MCP server with exactly one external tool:
> `qoc_run_agent`. OAuth is prepared but inactive by default.

## 1. Purpose

QOC can act as an MCP server for external orchestrators. Unlike the MCP client
integration, only one tool is exposed:

```text
qoc_run_agent
```

This keeps the external surface minimal and prevents direct access to internal
tools such as filesystem, shell, web, or MCP client tools.

## 2. Tool contract

Input:

```json
{
  "agent": "reviewer",
  "prompt": "Explain OrderService.totalFor",
  "workspace": "java-sample",
  "timeoutSeconds": 300,
  "sessionMode": "new",
  "sessionId": null,
  "structuredOutput": {
    "type": "object",
    "properties": {
      "summary": { "type": "string" }
    },
    "required": ["summary"]
  },
  "maxSteps": 8
}
```

Output:

```json
{
  "content": [{ "type": "text", "text": "..." }],
  "structuredContent": { "summary": "..." },
  "isError": false,
  "_meta": {
    "sessionId": "ses_...",
    "finish": "NORMAL",
    "inputTokens": 123,
    "outputTokens": 45
  }
}
```

## 3. Structured output

If `structuredOutput` is provided, QOC attempts to return a JSON value matching
the supplied schema. The result is validated before it is returned.

- Missing schema: normal text response.
- Valid schema: `structuredContent` is included.
- Invalid schema: the call fails with a clear error.

## 4. Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/mcp` | JSON-RPC endpoint (`initialize`, `tools/list`, `tools/call`) |
| `GET` | `/mcp/authorize` | OAuth authorization (inactive by default) |
| `GET` | `/mcp/callback` | OAuth callback (inactive by default) |

## 5. Configuration

```env
ENGINE_MCP_SERVER_ENABLED=true
ENGINE_MCP_SERVER_OAUTH_ENABLED=false
```

## 6. OAuth

OAuth is prepared but inactive by default. When disabled, the endpoints return
`503 Service Unavailable`. When enabled later, authorization and callback
handling can be implemented without changing the tool contract.

## 7. Security

- Only `qoc_run_agent` is exposed.
- No internal tools are exposed directly.
- Workspace and permission rules remain enforced inside QOC.
- External callers cannot bypass agent restrictions.
- Timeouts prevent unbounded runs.

## 8. Invariants

| ID | Rule |
|---|---|
| M-01 | The MCP server exposes exactly one tool: `qoc_run_agent`. |
| M-02 | No internal tool is directly exposed through the MCP server. |
| M-03 | Structured output is validated before it is returned. |
| M-04 | OAuth remains inactive unless explicitly enabled. |
| M-05 | The server delegates execution to the existing QOC engine. |
