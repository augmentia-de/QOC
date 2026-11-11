# QOC TUI (TypeScript client)

Minimal TypeScript client for the QOC engine REST API. Runs with Bun or Node.

## Run

```bash
cd tui
bun install   # optional, no runtime deps
QOC_URL=http://localhost:8080 bun run src/index.ts health
QOC_URL=http://localhost:8080 bun run src/index.ts run "sage hallo"
QOC_URL=http://localhost:8080 bun run src/index.ts chat
```

## Commands

- `health` – server health
- `agents` – list agents
- `sessions` – list sessions
- `run <prompt>` – one-shot prompt (streams live events)
- `chat` – interactive REPL (streams live events)

Default server URL: `http://localhost:8080` (override with `QOC_URL`).

`run`/`chat` subscribe to `GET /api/event` over SSE: text deltas are written to
stdout, tool and subagent events to stderr.

This is a scaffold (M20). A full TUI (panels, approvals, questions) is future work.
