# 03 – MCP scoping

## 1. Server configuration

MCP servers are configured globally or per project in `settings.json`:

```json
{
  "mcp": {
    "playwright": { "type": "remote", "url": "http://localhost:3005" },
    "filesystem": { "type": "local", "command": ["mcp-fs", "--root", "."] }
  }
}
```

## 2. Agent scoping

An agent may restrict which MCP servers it uses:

```json
{
  "name": "build",
  "mcpServers": ["playwright"]
}
```

- Empty list: no MCP tools for this agent.
- `["*"]`: all configured MCP servers.

## 3. Discovery and status

- `McpServerManager` registers all configured servers at startup.
- Status is available via `/api/mcp/{server}/status`.
- Tool discovery happens lazily on first use.
- Agent scoping filters the discovered tool list, not the server list.

## 4. Invariants

- **MCP-01:** MCP tools are prefixed with `{server}_{tool}`.
- **MCP-02:** A server not listed in `agent.mcpServers` contributes no tools to that agent.
- **MCP-03:** Server status is independent of agent scoping.
- **MCP-04:** OAuth and credentials remain server-global.
EOF