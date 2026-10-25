# 10 – Deployment: JVM, Native, Docker, and GCP

## Purpose

QOC can be operated as a JVM application or as a Native Image. The
Native Image deliberately contains **no Git** and no build environment. On the
GCP VM the Native binary runs directly under systemd, not in Docker.

```mermaid
flowchart TB
  subgraph Local["Local / Docker"]
    Jvm["JVM-Image<br/>Dockerfile<br/>JRE + git + util-linux"]:::ext
    JvmPort["Port 8099 / 8333"]:::ext
    Native["Native-Image<br/>Dockerfile.native<br/>Binary + util-linux<br/>no git"]:::ext
    NativePort["Port 8099 / 8334"]:::ext
    Jvm --> JvmPort
    Native --> NativePort
  end

  subgraph Gcp["GCP VM quad-e2micro"]
    User["User"]:::ext
    Nginx["nginx :80<br/>Reverse Proxy"]:::ext
    Auth["HTTP Basic Auth<br/>/etc/nginx/.htpasswd_qoc"]:::ext
    Systemd["systemd<br/>qoc.service"]:::det
    Binary["/opt/qoc/qoc-runner<br/>Native-Binary"]:::ext
    Env["/etc/qoc/env<br/>OPENCODE_SERVER_PASSWORD"]:::ext
    Db[("/data/qoc.db<br/>SQLite")]:::store
    Workspace[("/data/workspace")]:::store
    Agents["AGENTS.md<br/>Native rules"]:::ext

    User -->|"http://34.72.145.97/"| Nginx
    Nginx --> Auth
    Auth -->|"proxy / and /api/"| Systemd
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
| **JVM** | JRE, Maven build outside | yes | available if workspace is a Git repo | local development, CI, debugging |
| **Native** | no JDK/Maven in image | no | disabled | lean runtime, GCP |
| **GCP** | no Docker, no Git | no | disabled | productive single-node |

> **Open:** A separate JVM **build** image with JDK, Maven, and
> Maven cache is not yet implemented. The current `deploy/Dockerfile` is a
> JVM **runtime** image based on a JRE.

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
| Database | `/data/qoc.db` |
| Workspace | `/data/workspace` |
| Reverse proxy | nginx on port 80 → `127.0.0.1:8080` |
| Auth | HTTP Basic, user `opencode` |

The password is deliberately not in the repository. It lives in
`/etc/qoc/env` and in the nginx password file. When changed, both must be
updated and the services reloaded.

## Sources

- `qoc/deploy/Dockerfile`
- `qoc/deploy/Dockerfile.native`
- `qoc/deploy/docker-compose.yml`
- `qoc/deploy/docker-compose.native.yml`
- `qoc/deploy/AGENTS.jvm.md`
- `qoc/deploy/AGENTS.native.md`
- `qoc/deploy/vm.md` (gitignored, local operations doc)
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/meta/WorktreeTool.java`
