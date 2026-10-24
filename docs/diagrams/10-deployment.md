# 10 – Deployment: JVM, Native, Docker und GCP

## Zweck

QOC kann als JVM-Anwendung oder als Native-Image betrieben werden. Das
Native-Image enthält bewusst **kein Git** und keine Build-Umgebung. Auf der
GCP-VM läuft das Native-Binary direkt unter systemd, nicht in Docker.

```mermaid
flowchart TB
  subgraph Local["Lokal / Docker"]
    Jvm["JVM-Image<br/>Dockerfile<br/>JRE + git + util-linux"]:::ext
    JvmPort["Port 8099 / 8333"]:::ext
    Native["Native-Image<br/>Dockerfile.native<br/>Binary + util-linux<br/>kein git"]:::ext
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
    Agents["AGENTS.md<br/>Native-Regeln"]:::ext

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

## Varianten

| Variante | Build-Umgebung | Git | Worktree-Tools | Typischer Einsatz |
|---|---|---|---|---|
| **JVM** | JRE, Maven-Build außerhalb | ja | verfügbar, wenn Workspace ein Git-Repo ist | lokale Entwicklung, CI, Debugging |
| **Native** | kein JDK/Maven im Image | nein | deaktiviert | schlanke Runtime, GCP |
| **GCP** | kein Docker, kein Git | nein | deaktiviert | produktiver Single-Node |

> **Offen:** Ein separates JVM-**Build**-Image mit JDK, Maven und
> Maven-Cache ist noch nicht umgesetzt. Das aktuelle `deploy/Dockerfile` ist ein
> JVM-**Runtime**-Image auf Basis einer JRE.

## Native-Regeln

- Keine Build-Umgebung im Image.
- Kein Git, auch nicht optional.
- `WorktreeTool.available(...)` prüft `git --version` und
  `git -C <workspace> rev-parse --is-inside-work-tree`.
- Wenn die Prüfung fehlschlägt, werden die Worktree-Tools nicht registriert.
- `AGENTS.native.md` liegt im Image bzw. im Workspace und dokumentiert die
  Einschränkungen.

## GCP-Betrieb

| Komponente | Wert |
|---|---|
| Instanz | `quad-e2micro`, Zone `us-central1-a` |
| Öffentliche IP | `34.72.145.97` |
| Dienst | `qoc.service` (systemd) |
| Binary | `/opt/qoc/qoc-runner` |
| Umgebung | `/etc/qoc/env` |
| Datenbank | `/data/qoc.db` |
| Workspace | `/data/workspace` |
| Reverse Proxy | nginx auf Port 80 → `127.0.0.1:8080` |
| Auth | HTTP Basic, Benutzer `opencode` |

Das Passwort steht bewusst nicht im Repository. Es liegt in
`/etc/qoc/env` und in der nginx-Passwortdatei. Bei einer Änderung müssen beide
aktualisiert und die Dienste neu geladen werden.

## Quellen

- `qoc/deploy/Dockerfile`
- `qoc/deploy/Dockerfile.native`
- `qoc/deploy/docker-compose.yml`
- `qoc/deploy/docker-compose.native.yml`
- `qoc/deploy/AGENTS.jvm.md`
- `qoc/deploy/AGENTS.native.md`
- `qoc/deploy/vm.md` (gitignored, lokale Betriebsdoku)
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/meta/WorktreeTool.java`
