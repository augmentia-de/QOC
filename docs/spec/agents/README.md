# agents – Agent profiles, tool restrictions, MCP scoping and workflows

> **Purpose:** Define how agents are configured, which tools and MCP servers they
> may use, how permissions are scoped per agent, and how multi-agent workflows
> hand off work. This spec extends `session-engine`, `tools` and `permissions`
> without changing their core semantics.

| File | Topic |
|---|---|
| [`01-agent-config.md`](./01-agent-config.md) | Agent definition schema and precedence |
| [`02-tool-restrictions.md`](./02-tool-restrictions.md) | Tool allow/deny lists and snapshot filtering |
| [`03-mcp-scoping.md`](./03-mcp-scoping.md) | MCP server subsets per agent |
| [`04-workflows.md`](./04-workflows.md) | Multi-agent handoff and workflow state |

**Dependencies:** `session-engine` (agent id, session), `tools` (tool catalog),
`permissions` (rules, approvals), `provider` (model selection).

**Must not know:** tool implementations, provider protocol details, persistence internals.
EOF