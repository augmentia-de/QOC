# 10 – Deployment: Native and GCP

## Purpose

QOC can be operated as a JVM application or as a Native Image. The
Native Image deliberately contains **no Git** and no build environment. On the
GCP VM the Native binary runs directly under systemd, not in Docker.

> **Rule:** Never expose QOC or the MCP server directly. All public access
> goes through nginx with TLS. The engine listens on loopback only.

```mermaid
flowchart TB
  subgraph Gcp["GCP VM quad-e2micro"]
    User["User"]:::ext
    Nginx["nginx :80<br/>Reverse Proxy"]:::ext
    Auth["TLS only at nginx<br/>OAuth 2.1 + PKCE + Bearer for MCP"]:::ext
    Systemd["systemd<br/>qoc.service"]:::det
    Binary["/opt/qoc/qoc-runner<br/>Native-Binary"]:::ext
    Env["/etc/qoc/env<br/>OPENCODE_SERVER_PASSWORD"]:::ext
    Db[("Neon PostgreSQL<br/>(JDBC)")]:::store
    Workspace[("/data/workspace")]:::store
    Agents["AGENTS.md<br/>Native rules"]:::ext

    User -->|"https://test.augmentia.de/"| Nginx
    Nginx --> Auth
    Auth -->|"proxy / and /api/"| Systemd
    Direct["Direct public access<br/>FORBIDDEN"]:::ext
    Direct -.->|"blocked"| Binary
    Systemd --> Binary
    Binary --> Env
    Binary --> Db
    Binary --> Workspace
    Binary --> Agents
  end

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
  classDef store fill:#e3f2fd,stroke:#1565c0,stroke-width:1px,color:#111;
```

## Variants

| Variant | Build environment | Git | Worktree tools | Typical use |
|---|---|---|---|---|
| **Native/GCP** | no JDK/Maven on the VM | no | disabled | productive single-node on GCP |
| **AWS ECS Fargate** | Docker (JVM or Native) | optional | enabled (native) | test/production with EFS persistence |
| **AWS Lambda** | Container (Native) | no | disabled | serverless, on-demand, scale-to-zero |
| **GCP Cloud Run** | Container (Native) | no | disabled | serverless, on-demand, scale-to-zero |

> Local Docker/JVM deployments are intentionally not part of this diagram.

## Serverless Deployment (AWS Lambda / GCP Cloud Run)

```mermaid
flowchart TB
  subgraph Serverless["Serverless (Lambda / Cloud Run)"]
    Client["MCP Client"]:::ext
    Fn["Function / Service<br/>Native Binary"]:::ext
    Cache["Agent Cache<br/>(EFS / Volume)"]:::store
    Server["Server (GCP VM)<br/>Agent Source"]:::ext

    Client -->|"HTTPS /mcp"| Fn
    Fn -->|"fetch agent config<br/>(OIDC)"| Server
    Fn --> Cache
    Fn -->|"stateless run"| Fn
  end

  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
  classDef store fill:#e3f2fd,stroke:#1565c0,stroke-width:1px,color:#111;
```

### Serverless Rules

- Scale-to-Zero: keine Kosten bei Inaktivität.
- Agent-Cache flüchtig (ohne persistenten Speicher).
- Shell-Sandbox deaktiviert (keine User Namespaces).
- Concurrency = 1 (Sessions/Event-Feed prozesslokal).
- Agent-Authoring deaktiviert (`ENGINE_AGENT_AUTHORING_ENABLED=false`).
- Agenten werden vom Server (GCP VM) geladen (OIDC-geschützt).

## Native rules

- No build environment in the image.
- No Git, not even optionally.
- `WorktreeTool.available(...)` checks `git --version` and
  `git -C <workspace> rev-parse --is-inside-work-tree`.
- If the check fails, the worktree tools are not registered.
- `AGENTS.native.md` lies in the image or in the workspace and documents the
  limitations.

## GCP operation

| Component | Value |
|---|---|
| Instance | `quad-e2micro`, Zone `us-central1-a` |
| Public IP | `34.72.145.97` |
| Service | `qoc.service` (systemd) |
| Binary | `/opt/qoc/qoc-runner` |
| Environment | `/etc/qoc/env` |
| Database | Neon PostgreSQL (`QUARKUS_DATASOURCE_JDBC_URL`) |
| Workspace | `/data/workspace` |
| Reverse proxy | nginx on `80` → `443` (TLS), proxy to `127.0.0.1:8081`; static `/sites/` from `/data/workspace/sites` |
| Auth | OAuth 2.1 + PKCE + Bearer for MCP; Basic Auth only for legacy/admin, never for MCP |
| Direct access | None. Engine and MCP are reachable only through nginx over HTTPS. |

The password is deliberately not in the repository. It lives in
`/etc/qoc/env` and in the nginx password file. When changed, both must be
updated and the services reloaded.

## Sources

- `qoc/deploy/Dockerfile`
- `qoc/deploy/Dockerfile.native`
- `qoc/deploy/docker-compose.yml` (alle Varianten als Profil-Services)
- `qoc/deploy/seccomp-qoc-shell.json` (Shell-Isolation)
- `qoc/deploy/AGENTS.jvm.md`
- `qoc/deploy/AGENTS.native.md`
- `qoc/deploy/gcp-vm/vm.md` (gitignored, local operations doc)
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/meta/WorktreeTool.java`
