# OS-based security

> **Purpose:** How to run the QOC engine under a dedicated OS user with
> directory-scoped filesystem permissions, set dynamically via configuration
> and enforced both by the OS (hard boundary) and by the engine (policy).
>
> **Companion:** `spec/tools/03-bash-hitl.md` (shell tool, scanner, HITL),
> `spec/permissions/03-effect-matrix.md` (`external_directory`),
> `spec/betrieb/03-credentials.md`, `spec/betrieb/README.md`,
> `l4j-use.md`.
>
> **Status:** 2026-10-22.

## 1. Design: two layers

| Layer | Mechanism | Role |
|---|---|---|
| **OS layer** (hard boundary) | dedicated user, file ownership, `chmod`/`chown`, systemd hardening | Even if the engine has a bug, the process cannot write outside its data directory. |
| **Engine layer** (dynamic policy) | `external_directory` permission, shell scanner, config-driven allow-list | Decides which directories/commands are allowed, changeable without restart. |

Both layers together give defense in depth. OS-only is too coarse; engine-only is
unsafe (a bug in the engine = full access).

## 2. Dedicated OS user

```bash
# Run once as root
useradd --system --home /var/lib/qoc --shell /usr/sbin/nologin qoc
mkdir -p /var/lib/qoc/{data,workspaces,log}
chown -R qoc:qoc /var/lib/qoc
chmod 750 /var/lib/qoc
```

- No login shell → no interactive access.
- Home = engine data directory (SQLite DB, snapshots, tool output).
- All files under the data directory are owned by `qoc:qoc`.
- `HOME` must be set explicitly (systemd, Docker, or `-Duser.home`) so
  `~/.opencode/plan` resolves into the writable data directory and not into
  the container root or the workspace.

## 3. Directory model (dynamically configured)

```
/var/lib/qoc/
├── data/                  # SQLite DB, session_inbox, snapshots, tool-output
├── workspaces/            # project directories (one folder per workspace)
│   ├── project-a/         # allowed work area (read + write)
│   └── project-b/
├── log/                   # log files (append-only for qoc)
└── .opencode/plan/        # Plan mode files (~/.opencode/plan)
```

`HOME` points at `/var/lib/qoc`, so the Plan directory from the agent
instruction (`~/.opencode/plan/<name>.md`) lives in the data directory.
Never let `user.home` fall back to `/` or to the workspace: the plan files
would then be created outside the persistent, writable area.

Allowed directories are set **dynamically** via configuration (L-2):

- `location.directory` = global working directory (default: current directory).
- `permissions` contains `external_directory` rules with read/write flags
  (O-07 decision: global working directory + allow-list).
- Additional allowed directories via the allow-list in `settings.json`
  (project or global level).

## 4. systemd unit

```ini
# /etc/systemd/system/qoc.service
[Unit]
Description=QOC Engine
After=network.target

[Service]
Type=simple
User=qoc
Group=qoc
WorkingDirectory=/var/lib/qoc
Environment=HOME=/var/lib/qoc
EnvironmentFile=/etc/qoc/env
ExecStartPre=/usr/local/bin/qoc-prepare.sh
ExecStart=/usr/bin/java -Xmx256m -jar /opt/quarkus-run.jar
Restart=on-failure
RestartSec=5

# Hardening
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/qoc
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

## 5. Preparation script (`qoc-prepare.sh`)

```bash
#!/bin/bash
set -euo pipefail
DATA_DIR="${DATA_DIR:-/var/lib/qoc}"
WORKSPACE_DIR="${WORKSPACE_DIR:-/var/lib/qoc/workspaces}"
QOC_HOME="${QOC_HOME:-$DATA_DIR}"

# Create directories and set ownership
mkdir -p "$DATA_DIR"/{data,workspaces,log} "$QOC_HOME/.opencode/plan"
chown -R qoc:qoc "$DATA_DIR" "$QOC_HOME/.opencode"
chmod 750 "$DATA_DIR"
chmod 700 "$DATA_DIR/data"        # DB + snapshots: qoc only
chmod 750 "$DATA_DIR/workspaces"  # projects: readable/writable by qoc
chmod 750 "$DATA_DIR/log"
chmod 750 "$QOC_HOME/.opencode" "$QOC_HOME/.opencode/plan"

# Dynamically set project directories from env
if [ -n "${WORKSPACE_DIRS:-}" ]; then
  IFS=':' read -ra DIRS <<< "$WORKSPACE_DIRS"
  for d in "${DIRS[@]}"; do
    if [ -d "$d" ]; then
      chown -R qoc:qoc "$d" 2>/dev/null || true
      chmod -R u+rwX,go-rwx "$d"  # qoc can read/write, others cannot
    fi
  done
fi

echo "Preparation done."
```

## 6. Bash tool: dynamic directory permissions (engine side)

Before every shell execution the engine checks:

1. **Working directory:** `location.directory` (global, from config).
2. **Command scan:** `shellscan` extracts commands + referenced directories.
3. **Permission check:** `external_directory` rule (O-07 decision: global
   working directory + allow-list with read/write flags).
4. **Shell scanner** (`tools/03-bash-hitl.md`): extracts directories from the
   command (e.g. `cd /etc`, `cat /etc/passwd`, `> /tmp/x`).
5. **Deny/Ask/Allow:** `deny` overrides saved approvals; `ask` prompts the
   user; `allow` permits.

**Dynamic configuration** (in `settings.json`):

```json
{
  "location": { "directory": "/var/lib/qoc/workspaces/project-a" },
  "permissions": [
    { "action": "*",                  "resource": "*", "effect": "allow" },
    { "action": "external_directory", "resource": "/var/lib/qoc/workspaces/project-a/*", "effect": "allow" },
    { "action": "external_directory", "resource": "/tmp/*", "effect": "ask" },
    { "action": "external_directory", "resource": "*", "effect": "deny" },
    { "action": "shell",              "resource": "*", "effect": "ask" }
  ]
}
```

## 7. OS-level hardening (additional)

- **`NoNewPrivileges=true`** – the process cannot escalate privileges.
- **`ProtectSystem=strict`** – `/usr`, `/boot`, `/etc` are read-only.
- **`ReadWritePaths=/var/lib/qoc`** – only the data directory is writable.
- **`PrivateTmp=true`** – isolated `/tmp`.
- Optional: **chroot** or **systemd-nspawn** for stronger isolation (more
  complex, not required for single-user setups).

### Docker specifics

The image runs as an unprivileged user and needs an explicit, writable
`HOME`. If the compose file overrides the user (`user: "${UID}:${GID}"`),
the UID has no `/etc/passwd` entry inside the container, so Java reports
`user.home = /`. Plan writes then fail or land in the wrong place.

Set both in the image and in the compose environment, and create the
directory at startup because the `/data` bind mount hides the image layer:

```dockerfile
ENV HOME=/data/home
ENTRYPOINT ["sh", "-c", "mkdir -p \"$HOME/.opencode/plan\" && exec java -jar /app/quarkus-run.jar"]
```

```yaml
environment:
  - HOME=/data/home
```

`/data/home` is inside the persistent `/data` volume, so plan files survive
container recreation. For the native image (`Dockerfile.native`), set
`ENV HOME=/data/home` and create the same directory in the build step.

## 8. What the spec already covers vs. what is OS-side

| Requirement | Spec (engine) | OS (start script) |
|---|---|---|
| Only certain directories | `external_directory` permission (O-07) | File permissions (`chmod`/`chown`) |
| Dynamic configuration | Config (`settings.json`, L-2) | Env vars + `qoc-prepare.sh` |
| Dedicated user | – (engine knows no OS user) | `useradd qoc`, systemd `User=qoc` |
| Permission enforcement | Policy engine (`Permission.assert`) | `chown`/`chmod` in start script |
| Shell scanning | `shellscan` (S0) extracts commands + directories | – |

## 9. Security note

The OS layer is the **hard boundary** (even if the engine has a bug, the
process cannot write outside `/var/lib/qoc`). The engine policy is the
**dynamic control** (which directories/commands are allowed, changeable via
config without restart). Together: defense in depth.

**Not recommended:** OS-only (too coarse) or engine-only (a bug in the engine
= full access). Both layers are needed.

## 10. References

- Spec: `spec/tools/03-bash-hitl.md` (shell tool, scanner, HITL, PTY)
- Spec: `spec/tools/02-filesystem.md` (path resolution, `external_directory`)
- Spec: `spec/permissions/02-evaluation.md` (rule chain, wildcard)
- Spec: `spec/permissions/03-effect-matrix.md` (`external_directory` matrix)
- Spec: `spec/betrieb/03-credentials.md` (credentials, env resolution)
- Spec: `spec/betrieb/README.md` (baseline scope)
- Decisions: `open-questions.md` (O-07, L-2, P-1)