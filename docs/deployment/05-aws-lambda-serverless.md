# AWS Lambda – Serverless Mode (geplant)

> **Status:** spec-only
> **Quelle:** `guides/deployment-modes.md`

## Einschränkungen

- Max. 15 Minuten pro Invocation
- Keine dauerhaften Hintergrund-Subagents
- Keine PTY-Shell-Sessions
- EFS für persistenten Cache/Workspace nötig
- Concurrency idealerweise 1 (Sessions/Event-Feed prozesslokal)
- SSE nur eingeschränkt nutzbar

## Konfiguration

```properties
ENGINE_MODE=serverless
ENGINE_SECURITY_MODE=oidc
ENGINE_AGENT_SOURCE_URL=https://gcp-vm.example.com
ENGINE_AGENT_SYNC_INTERVAL_SECONDS=300
ENGINE_AGENT_CACHE_DIR=/mnt/workspace/.cache/agents
ENGINE_AGENT_AUTHORING_ENABLED=false
HOME=/mnt/workspace/.home
ENGINE_WORKSPACE=/mnt/workspace
ENGINE_SHELL_SANDBOX_ENABLED=false
ENGINE_MAX_CONCURRENT_PROMPTS=1
ENGINE_TURN_MAX_WALL_MINUTES=10
```

## Agent-Synchronisation

Serverless lädt Agenten dynamisch vom Server (GCP VM) über eine
OIDC-geschützte API. Siehe `guides/deployment-modes.md` §2.

## MCP-Zugriff

Serverless soll über MCP erreichbar sein mit einem zusätzlichen Tool
`qoc_run_agent_with_config` (stateless Subagent-Run mit Agent-Konfiguration).
Siehe `guides/deployment-modes.md` §3.3.
