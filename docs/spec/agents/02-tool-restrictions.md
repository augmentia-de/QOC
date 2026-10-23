# 02 – Tool restrictions

## 1. Two enforcement layers

Tool access is enforced in two layers:

1. **Catalog filtering (soft):** The model only sees tools that are in the
   agent's `tools.allow` and not in `tools.deny`.
2. **Permission gate (hard):** Every tool call is checked against the
   agent's permission rules before execution.

## 2. Snapshot filtering

The global `ToolSnapshot` contains all registered tools. For each prompt,
`Engine` builds an agent-specific snapshot:

```
allowed = global.tools filtered by agent.tools.allow
allowed = allowed minus agent.tools.deny
allowed = allowed filtered by agent.mcpServers for MCP tools
```

Only `allowed` is passed to `ContextBuilder.prepare`.

## 3. Permission rules

Agent rules are concatenated with session rules:

```
rules = agent.permissions + session.permissions
```

Stored approvals are appended at evaluation time. Deny rules are checked first.
If no rule matches, the default is `ask`.

## 4. Tool families

- `read`, `write`, `edit`, `patch`, `glob`, `grep`
- `shell`
- `skill`
- MCP tools (`{server}_{tool}`)

## 5. Invariants

- **TR-01:** A denied tool is never advertised to the model.
- **TR-02:** A denied tool call is rejected before execution.
- **TR-03:** Tool filtering does not change the global registry.
- **TR-04:** MCP tool filtering follows `agent.mcpServers`.
- **TR-05:** Permission rules are evaluated in deterministic order.
EOF