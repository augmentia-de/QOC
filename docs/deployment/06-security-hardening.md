# Security Hardening – Mounts, Sandbox, Egress, Secrets

> **Status:** implemented (Docker), partial (GCP/AWS)
> **Quelle:** `deploy/README.md`, `deploy/docker-secure-shell/README.md`,
> `docs/security.md`

## Varianten

Alle Docker-Varianten liegen in `deploy/docker-compose.yml`. Zwei davon sind
gehärtet:

| Service | Port | Grenze |
|---|---|---|
| `qoc-secure` | 8075 | Least-Privilege, nur `/data/workspace` + `/data/home` |
| `qoc-secure-shell` | 8076 | Shell sieht ausschließlich `/data/home` (read-only), Workspace `/data/home/work` |

## Container-Hardening

- Read-only Root-Dateisystem (`read_only: true`)
- Targeted Mounts (kein ganzes `/data`)
- tmpfs für `/data` und `/tmp`
- `cap_drop ALL`
- `no-new-privileges: true`
- `pids_limit: 256`
- Non-root User (UID/GID 1000)

## Shell-Isolation

`qoc-secure-shell` mountet nur `deploy/docker-secure-shell/home` nach
`/data/home` (read-only) und `.../home/work` beschreibbar nach
`/data/home/work`. `/data/workspace`, `/data/qoc.db`, `../log`, das
Quell-Repository und der Docker-Socket existieren im Container nicht. Die
Grenze ist damit der Mount-Namespace und greift auch für Skripte, `cd ..`,
Variablen oder Symlinks.

Zusätzlich:

- `ENGINE_PERMISSION_DEFAULT=least-privilege`, `ENGINE_PERMISSION_ASK_MODE=deny`
- `ENGINE_AGENT_AUTHORING_ENABLED=false`,
  `ENGINE_SETTINGS_WORKSPACE_ENABLED=false`
- `ENGINE_EMAIL_ENABLED=false`, keine Log-/LLM-Trace-Dateien
- Seccomp-Deny-Liste `deploy/seccomp-qoc-shell.json` gegen `ptrace`,
  `process_vm_readv/writev`, `mount`, `pivot_root`, `chroot`, `setns`,
  `unshare`, `bpf`, `perf_event_open`, `keyctl`

## Shell-Sandbox (bwrap)

- bwrap ist in den Images vorhanden, in Docker aber deaktiviert
  (`ENGINE_SHELL_SANDBOX_ENABLED=false`): unprivilegierte Container können
  hier keine Namespaces erzeugen (`kernel.apparmor_restrict_unprivileged_userns=1`).
- Native Hosts (GCP) können die Sandbox aktivieren.
- In Docker kommt die Grenze aus den Mounts, nicht aus dem Sandbox-Runner.

### Grenzen

- Der Container sieht sein eigenes OS (`/usr`, `/etc`, `/proc`).
- `/proc/1/environ` ist für denselben UID lesbar. Secrets aus `env_file`
  (API-Key, DB-Passwort) sind damit für einen Shell-Prozess lesbar. Für
  vollständige Geheimnis-Isolation müsste die Engine unter einer anderen UID
  laufen oder der Host unprivilegierte Namespaces erlauben.
- Netzwerkzugriffe des Shell-Tools sind nicht pro Prozess begrenzt; der
  Container braucht LLM- und DB-Zugriff.

## Secrets

- Über `.env` (mode 0600) oder Secrets Manager
- Nie im Image
- Rotation nach Tests empfohlen (OpenAI, DB, `OPENCODE_SERVER_PASSWORD`)

## Egress

- In Docker: Outbound über Host/Firewall einschränken
- In AWS: Security Groups (Port 8080 nur von bestimmter IP)
- EFS: NFS (2049) nur vom ECS-SG

## Netzwerk

- Engine lauscht nur auf `127.0.0.1`
- Öffentlicher Zugang nur über nginx/TLS
- Kein direkter Zugriff auf Engine oder MCP
