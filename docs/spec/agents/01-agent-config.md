# 01 – Agent configuration

## 1. Agent definition

An agent is a named profile that determines how a session behaves. The
configuration is part of `settings.json` (global then project; project wins).

```json
{
  "agents": [
    {
      "name": "plan",
      "system": "You are a planning agent. Do not modify files.",
      "hidden": false,
      "model": null,
      "steps": 5,
      "tools": { "allow": ["read", "glob", "grep"], "deny": ["write", "edit", "patch", "shell"] },
      "permissions": [{ "action": "*", "resource": "*", "effect": "deny" }],
      "mcpServers": []
    },
    {
      "name": "build",
      "system": "You are a build agent.",
      "hidden": false,
      "model": null,
      "tools": { "allow": ["*"], "deny": [] },
      "permissions": [{ "action": "shell", "resource": "*", "effect": "ask" }],
      "mcpServers": ["playwright"]
    }
  ]
}
```

## 2. Fields

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Unique agent name. `build` is the default. |
| `system` | string \| null | Replaces the base system prompt. |
| `hidden` | boolean | Hides the agent from the UI selector. |
| `model` | string \| null | Overrides the session model for this agent. |
| `steps` | int \| null | Optional maximum model rounds per prompt. Absent or `0` means no step limit. |
| `maxSteps` | int \| null | Deprecated alias for `steps`; `steps` wins when both are set. |
| `tools.allow` | string[] | Tool names or `*`. Empty means none. |
| `tools.deny` | string[] | Tool names removed after `allow`. |
| `permissions` | Rule[] | Agent-specific permission rules. |
| `mcpServers` | string[] | MCP server names or `*`. Empty means none. |

`steps` follows OpenCode: when it is absent, the agent has no step limit.
The turn is still bounded by the tool-call, token and wall-time budgets of the
turn policy. `maxSteps` remains accepted for older configurations, but is
deprecated.

## 3. Precedence

1. Global `~/.config/opencode/settings.json`
2. Project `settings.json`
3. Environment (`ENGINE_*`)
4. Agent-specific overrides inside the agent definition

Project values override global values field by field. Lists (`agents`, `mcp`)
are appended. Agent-specific `tools` and `permissions` override global defaults.

## 4. Invariants

- **AG-01:** An agent name is unique within the merged configuration.
- **AG-02:** `tools.deny` always wins over `tools.allow`.
- **AG-03:** An empty `tools.allow` advertises no tools to the model.
- **AG-04:** `mcpServers` filters MCP tools, but does not disable servers globally.
- **AG-05:** Permission rules are evaluated in the order `agent rules → session rules → stored approvals`.
- **AG-06:** A missing or non-positive `steps` value means no step limit; `steps` takes precedence over the deprecated `maxSteps` alias.
