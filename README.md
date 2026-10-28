# QOC – AI Agent Runtime

![Java](https://img.shields.io/badge/Java-21-orange)
![Quarkus](https://img.shields.io/badge/Quarkus-3.40.1.1-blue)
![Maven](https://img.shields.io/badge/Build-Maven-red)
![Status](https://img.shields.io/badge/Status-M0--M14%20implemented-brightgreen)

QOC is a **Java runtime for AI coding agents**. It accepts prompts, assembles
context, calls models, executes tools, streams results, persists sessions, and
enforces permissions. It is not a new agent UI or product surface; it is the
engine that makes an agent runnable.

It is built as a library (`engine`) plus a Quarkus outer skin (`quarkus`,
HTTP/SSE) and runs as a JVM application, a native image, or a container.

The authoritative source of truth is [`docs/spec/`](./docs/spec/README.md), not
the code.

---

## Highlights

- **Pure Java core:** `engine.core` without Quarkus, CDI, Jackson, or HTTP.
- **Three-layer security:** OS hardening, engine policy (deny by default), and
  a shell scanner.
- **Complete session engine:** inbox with `STEER`/`QUEUE`, snapshots/revert,
  automatic and manual compaction, subagents, parallel tool calls.
- **Tool parity:** files, shell (optional PTY), search, web, skills, subagents,
  todos, questions, worktrees, MCP tools and resources.
- **Context files:** automatic `AGENTS.md`/`CLAUDE.md` discovery plus
  `SYSTEM.md`/`APPEND_SYSTEM.md`.
- **Skills:** progressive disclosure, 8 KiB body limit, `allow`/`deny`/`max`,
  hidden skills via `disable-model-invocation`.
- **Operations:** SQLite or PostgreSQL, Docker (JVM/native), systemd,
  OpenTelemetry, rotating LLM trace.
- **Guardrails:** declarative rules, CDI SPI, [external webhook guardrail](docs/webhook.md) – [provider](webhook-guardrail/README.md).
- **A2A:** Agent-to-Agent client (external agents as tools, `engine.a2a.urls`), discovery/UI at `/api/a2a/agents` with cached agent cards, and an opt-in JSON-RPC server (`engine.a2a.server.enabled`, agent card at `/.well-known/agent-card.json`).
- **Quality gates:** ArchUnit, JaCoCo ≥ 80 %, `mvn verify`.

---

## Architecture

```
qoc/
├── engine/     Pure-Java core and adapters
│   ├── core/       models, ports, contracts (no framework dependency)
│   ├── runtime/    session, execution, compaction, tools, skills, provider
│   └── adapter/    SQL, SQLite, HTTP, processes, filesystem, logging
├── quarkus/    outer skin: REST/SSE, CDI wiring, configuration, web UI
├── deploy/     Docker, Compose, systemd, runtime AGENTS
└── docs/       specification, guides, analyses, diagrams
```

Dependency direction: `quarkus` → `engine` → `engine.core`. The core knows no
Quarkus, Jackson, or HTTP types.

---

## Requirements

| Tool | Version | Required |
|---|---|---|
| JDK | 21 | yes |
| Maven | 3.9+ | yes |
| Docker | current | for Dev Services/containers |
| GraalVM | 21+ | native images only |

---

## Quick start

### 1. Configuration

```bash
cd qoc
cp deploy/.env.example .env
# edit .env: OPENAI_API_KEY, model, DB backend
```

Without `OPENAI_API_KEY` the application starts with the placeholder
`demo-key`; real model calls will then fail.

### 2. Start

```bash
./start.sh
```

`start.sh` loads `.env`, maps `OPENAI_*` to the engine variables, and runs
`mvn -pl quarkus quarkus:dev`. Alternatively:

```bash
export OPENAI_API_KEY="sk-..."
mvn -pl quarkus quarkus:dev
```

- Dev mode: <http://127.0.0.1:8080>
- PostgreSQL via Quarkus Dev Services by default (Docker required).
- SQLite instead of PostgreSQL: set `ENGINE_DB_KIND=sqlite` in `.env`.

### 3. First steps

```bash
# create a session
SID=$(curl -s -X POST http://127.0.0.1:8080/api/session \
  -H 'Content-Type: application/json' \
  -d '{"agent":"build"}' | jq -r .id)

# run a prompt
curl -s -X POST "http://127.0.0.1:8080/api/session/$SID/prompt" \
  -H 'Content-Type: application/json' \
  -d '{"text":"Explain Quarkus in one sentence."}'

# stream events live (SSE)
curl -N "http://127.0.0.1:8080/api/event?session=$SID&after=0&live=true"
```

---

## HTTP API (excerpt)

All endpoints live under `/api`. The full surface is defined in
[`HttpApi.java`](./quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java).

### Sessions

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/session` | create a session (`{"agent": "..."}`) |
| `GET` | `/api/session` | list sessions |
| `GET` | `/api/session/{id}` | session details (tokens, cost, agent) |
| `DELETE` | `/api/session/{id}` | delete a session |
| `POST` | `/api/session/{id}/agent` | switch agent |
| `POST` | `/api/session/{id}/prompt` | run a prompt (`{"text": "..."}`) |
| `POST` | `/api/session/{id}/command` | run a slash command |
| `POST` | `/api/session/{id}/title` | generate a title |
| `POST` | `/api/session/{id}/revert` | revert the last turn |
| `POST` | `/api/session/{id}/compact` | compact the session |
| `POST` | `/api/session/{id}/interrupt` | cancel a running execution |
| `GET` | `/api/session/{id}/messages` | message history |
| `GET` | `/api/session/{id}/message` | message page (`limit`, `cursor`) |
| `GET` | `/api/session/{id}/todo` | todos |

### Streaming, permissions, questions

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/event` | SSE stream (`session`, `after`, `live`) |
| `GET` | `/api/session/{id}/permission` | pending permission requests |
| `POST` | `/api/session/{id}/permission/{requestId}/reply` | answer a permission request |
| `GET` | `/api/permission/request` | all pending requests |
| `GET` | `/api/session/{id}/question` | pending question |
| `POST` | `/api/session/{id}/question/{questionId}/reply` | answer a question |

### Catalog, MCP, files

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/agent` | agents |
| `GET` | `/api/skill?all=true` | skills (including hidden ones) |
| `GET` | `/api/model` | active model |
| `GET` | `/api/provider` | provider |
| `GET` | `/api/config` | configuration |
| `GET` | `/api/health` | health check |
| `GET` | `/api/location` | workspace/location |
| `GET` | `/api/mcp` | MCP servers |
| `GET` | `/api/mcp/{server}/status` | MCP status |
| `GET` | `/api/mcp/{server}/authorize` | start OAuth |
| `GET` | `/api/mcp/{server}/callback` | OAuth callback |
| `GET` | `/api/files?path=...` | list a directory |
| `GET` | `/api/file?path=...` | read a text file |
| `GET` | `/api/file/download?path=...` | download a file (raw bytes) |
| `GET` | `/api/dir/download?path=...` | download a directory as ZIP |
| `POST` | `/api/file` | write a text file |
| `POST` | `/api/file/upload?path=...` | upload a file (octet-stream) |
| `DELETE` | `/api/file?path=...` | delete a file or directory (recursive) |

---

## Configuration

The most important variables (full list in
[`quarkus/src/main/resources/application.properties`](./quarkus/src/main/resources/application.properties)
and [`deploy/.env.example`](./deploy/.env.example)):

| Variable | Default | Meaning |
|---|---|---|
| `OPENAI_API_KEY` | `demo-key` | LLM API key |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible base URL |
| `OPENAI_MODEL` | `gpt-4o-mini` | model ID |
| `ENGINE_PROVIDER_SCRIPTED` | `true` | deterministic test provider |
| `ENGINE_DB_KIND` | `postgresql` | `postgresql` or `sqlite` |
| `ENGINE_DB_PATH` | `./data/qoc.db` | SQLite path |
| `ENGINE_WORKSPACE` | `.` | agent workspace |
| `ENGINE_INSTRUCTIONS_ENABLED` | `true` | load AGENTS.md/CLAUDE.md |
| `ENGINE_REVERT_SNAPSHOT` | `true` | file snapshots for revert |
| `SESSION_RETENTION_DAYS` | `14` | session retention |
| `ENGINE_LLM_LOG_ENABLED` | `true` | write LLM trace |
| `ENGINE_LLM_LOG_PATH` | `./log/llm.log` | trace file (2 MiB × 20) |
| `FIRECRAWL_API_KEY` | – | web search (optional) |
| `OPENCODE_SERVER_PASSWORD` | – | HTTP Basic auth (empty = off) |
| `OTEL_ENABLED` | `false` | OpenTelemetry |

---

## Build and tests

```bash
# full gate: tests, ArchUnit, JaCoCo
mvn verify

# build a fast-jar
mvn package

# start
java -jar quarkus/target/quarkus-app/quarkus-run.jar
```

Quality rules:

- JaCoCo instruction coverage **≥ 80 %**.
- ArchUnit checks layers, visibility, and adapter isolation.
- `./check-no-catch-exception.sh` checks tool boundaries for `catch (Exception)`.
- Tests use a dynamic HTTP test port (`%test.quarkus.http.test-port=0`).

---

## Deployment

### Docker (JVM, SQLite)

```bash
cd qoc
cp deploy/.env.example deploy/.env
./deploy/build-image.sh
cd deploy && docker compose up -d
```

`build-image.sh` builds the fast-jar with **SQLite as a build-time setting**.
A plain `mvn package` produces a PostgreSQL build; the container would fail
with `Driver does not support the provided URL: jdbc:sqlite:...`.

- UI/API: <http://127.0.0.1:8333>
- Persistence: Docker volume `qoc-data`
- Plan files: `/data/home/.opencode/plan` (`HOME=/data/home`)

### Native image

```bash
mvn -DskipTests package -Dnative     # requires GraalVM
cd deploy && docker compose -f docker-compose.native.yml up -d --build
```

The native image intentionally contains **no Git**; worktree tools are not
available there.

### systemd

Prerequisites: user `qoc`, directory `/var/lib/qoc`, fast-jar at
`/opt/qoc/quarkus-run.jar`, and environment file `/etc/qoc/env`.

```bash
sudo install -m 644 deploy/qoc.service /etc/systemd/system/qoc.service
sudo install -m 755 deploy/qoc-prepare.sh /usr/local/bin/qoc-prepare.sh
sudo systemctl daemon-reload
sudo systemctl enable --now qoc
```

The unit runs as user `qoc` with `ProtectSystem=strict`, `NoNewPrivileges`,
and `ReadWritePaths=/var/lib/qoc`.

---

## Project structure

```
qoc/
├── engine/                      pure-Java engine
│   ├── src/main/java/de/augmentia/oc/engine/
│   │   ├── core/                models, ports, contracts
│   │   ├── runtime/             session, execution, compaction, tools
│   │   └── adapter/             SQL, HTTP, processes, filesystem
│   └── src/test/java/           unit, contract, and ArchUnit tests
├── quarkus/                     Quarkus outer skin
│   └── src/main/java/de/augmentia/oc/engine/http/   REST/SSE, wiring
├── deploy/                      Docker, Compose, systemd, AGENTS
├── docs/                        spec, guides, analyses, diagrams
├── start.sh                     local dev start with .env
└── check-no-catch-exception.sh  quality check
```

---

## Documentation

| Goal | Entry point |
|---|---|
| Overview and roles | [`docs/INDEX.md`](./docs/INDEX.md) |
| Implementation status | [`docs/STATUS.md`](./docs/STATUS.md) |
| Implementation order | [`docs/ROADMAP.md`](./docs/ROADMAP.md) |
| Operative specification | [`docs/spec/README.md`](./docs/spec/README.md) |
| Architecture and data flow | [`docs/analysis/overview.md`](./docs/analysis/overview.md) |
| Diagrams | [`docs/diagrams/README.md`](./docs/diagrams/README.md) |
| Decisions | [`docs/open-questions.md`](./docs/open-questions.md) |
| Ideas (Kafka, MCP server) | [`docs/guides/ideas.md`](./docs/guides/ideas.md) |

---

## Contributing

1. Read `docs/ROADMAP.md` and identify the current milestone.
2. Implement only the current milestone — sequentially, not in parallel.
3. Add tests and architecture rules that protect the milestone.
4. `mvn verify` must be green (tests, ArchUnit, JaCoCo ≥ 80 %).
5. Do not change `docs/` or reference checkouts without a task.
6. Never commit secrets, API keys, or local environment data.

Conventions: Java 21, records for values, `List.copyOf`/`Map.copyOf`, typed
errors instead of `null` sentinels, public interfaces with package-private
implementations.

---

## Documentation map

| If you want to… | Start here |
|---|---|
| understand the architecture | [`docs/overview.md`](./docs/analysis/overview.md) → [`docs/diagrams/`](./docs/diagrams/README.md) |
| implement a milestone | [`docs/ROADMAP.md`](./docs/ROADMAP.md) → [`docs/spec/README.md`](./docs/spec/README.md) |
| read the tool contract | [`docs/spec/tools/README.md`](./docs/spec/tools/README.md) |
| check permissions | [`docs/spec/permissions/README.md`](./docs/spec/permissions/README.md) |
| connect a provider | [`docs/spec/provider/README.md`](./docs/spec/provider/README.md) |
| operate QOC | [`docs/spec/betrieb/README.md`](./docs/spec/betrieb/README.md) |
| review quality gates | [`docs/spec/qualitaet/README.md`](./docs/spec/qualitaet/README.md) |
| see implementation status | [`docs/STATUS.md`](./docs/STATUS.md) |
| optimize context or cost | [`docs/improve.md`](./docs/improve.md) |

## Typical request flow

1. A client creates a session and sends a prompt.
2. The engine admits the prompt to a durable inbox.
3. Context is assembled from history, instructions, skills, and tool definitions.
4. The provider streams a model response.
5. Tool calls are authorized, executed, bounded, and published as events.
6. Projections update messages, cost, tokens, and session state.
7. The client receives the final text and usage through the HTTP response or SSE.

## Deployment options

- **Development:** `./start.sh` or `mvn -pl quarkus quarkus:dev`
- **JVM container:** `deploy/Dockerfile` with `deploy/docker-compose.yml`
- **Native image:** `deploy/Dockerfile.native` with `deploy/docker-compose.native.yml`
- **Bare metal:** `java -jar quarkus/target/quarkus-app/quarkus-run.jar`
- **systemd:** `deploy/qoc.service` with the preparation script `deploy/qoc-prepare.sh`

Persistent data lives under `/data` in containers: SQLite database, workspace,
logs, and plan files.

---

## License

There is currently **no license file**. A license must be chosen and added as
`LICENSE` before redistribution or publication.
