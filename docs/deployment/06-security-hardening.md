# Security Hardening – Mounts, Sandbox, Egress, Secrets

> **Status:** implemented (Docker), partial (GCP/AWS)
> **Source:** `deploy/README.md`, `deploy/docker-secure-shell/README.md`,
> `docs/security.md`

## Variants

All Docker variants are defined in `deploy/docker-compose.yml`. Two of them are
hardened:

| Service | Port | Boundary |
|---|---|---|
| `qoc-secure` | 8075 | Least-privilege, only `/data/workspace` + `/data/home` |
| `qoc-secure-shell` | 8076 | Shell sees only `/data/home` (read-only), workspace `/data/home/work` |

## Container Hardening

- Read-only root filesystem (`read_only: true`)
- Targeted mounts (no entire `/data`)
- tmpfs for `/data` and `/tmp`
- `cap_drop ALL`
- `no-new-privileges: true`
- `pids_limit: 256`
- Non-root user (UID/GID 1000)

## Shell Isolation

`qoc-secure-shell` mounts only `deploy/docker-secure-shell/home` to
`/data/home` (read-only) and `.../home/work` writable to `/data/home/work`.
`/data/workspace`, `/data/qoc.db`, `../log`, the source repository, and the
Docker socket do not exist inside the container. The boundary is therefore the
mount namespace and applies even to scripts, `cd ..`, variables, or symlinks.

Additionally:

- `ENGINE_PERMISSION_DEFAULT=least-privilege`, `ENGINE_PERMISSION_ASK_MODE=deny`
- `ENGINE_AGENT_AUTHORING_ENABLED=false`,
  `ENGINE_SETTINGS_WORKSPACE_ENABLED=false`
- `ENGINE_EMAIL_ENABLED=false`, no log/LLM trace files
- Seccomp deny list `deploy/seccomp-qoc-shell.json` against `ptrace`,
  `process_vm_readv/writev`, `mount`, `pivot_root`, `chroot`, `setns`,
  `unshare`, `bpf`, `perf_event_open`, `keyctl`

## Shell Sandbox (bwrap)

- bwrap is present in the images but disabled in Docker
  (`ENGINE_SHELL_SANDBOX_ENABLED=false`): unprivileged containers cannot create
  namespaces here (`kernel.apparmor_restrict_unprivileged_userns=1`).
- Native hosts (GCP) can enable the sandbox.
- In Docker the boundary comes from the mounts, not from the sandbox runner.

### Limits

- The container sees its own OS (`/usr`, `/etc`, `/proc`).
- `/proc/1/environ` is readable by the same UID. Secrets from `env_file`
  (API key, DB password) are therefore readable by a shell process. For full
  secret isolation the engine would have to run under a different UID or the
  host would have to allow unprivileged namespaces.
- Network access by the shell tool is not limited per process; the container
  needs LLM and DB access.

## Secrets

- Via `.env` (mode 0600) or Secrets Manager
- Never in the image
- Rotation recommended after tests (OpenAI, DB, `OPENCODE_SERVER_PASSWORD`)

## Egress

- In Docker: restrict outbound via host/firewall
- In AWS: Security Groups (port 8080 only from a specific IP)
- EFS: NFS (2049) only from the ECS SG

## Network

- Engine listens only on `127.0.0.1`
- Public access only via nginx/TLS
- No direct access to engine or MCP
