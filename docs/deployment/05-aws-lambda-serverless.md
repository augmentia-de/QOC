# AWS Lambda – Serverless Mode (planned)

> **Status:** spec-only
> **Source:** `guides/deployment-modes.md`

## Limitations

- Max. 15 minutes per invocation
- No persistent background subagents
- No PTY shell sessions
- EFS required for persistent cache/workspace
- Concurrency ideally 1 (sessions/event feed are process-local)
- SSE only limited use

## Configuration

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

## Agent Synchronization

Serverless loads agents dynamically from the server (GCP VM) via an
OIDC-protected API. See `guides/deployment-modes.md` §2.

## MCP Access

Serverless should be reachable via MCP with an additional tool
`qoc_run_agent_with_config` (stateless subagent run with agent configuration).
See `guides/deployment-modes.md` §3.3.
