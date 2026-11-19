# GCP VM (Native, systemd)

> **Status:** implemented
> **Source:** `deploy/gcp-vm/vm.md`, `deploy/gcp-vm/qoc.service`, `deploy/gcp-vm/qoc-prepare.sh`, `docs/diagrams/10-deployment.md`

## Infrastructure

| Property | Value |
|---|---|
| Instance | `quad-e2micro`, Zone `us-central1-a` |
| Machine type | `e2-micro` (Free Tier) |
| Public IP | `34.72.145.97` |
| Service | `qoc.service` (systemd) |
| Binary | `/opt/qoc/qoc-runner` |
| Environment | `/etc/qoc/env` |
| Database | Neon PostgreSQL |
| Workspace | `/data/workspace` |
| Reverse proxy | nginx (80 -> 443 TLS) -> 127.0.0.1:8081 |

## Rules

- No build environment on the VM (no JDK/Maven).
- No Git; Worktree tools disabled.
- Engine and MCP reachable only via nginx/HTTPS.
- Basic Auth for legacy/admin; MCP uses OAuth 2.1 + PKCE + Bearer.

## Operations

```bash
# Status
systemctl status qoc
journalctl -u qoc -f

# Restart
systemctl restart qoc

# Edit environment
nano /etc/qoc/env
systemctl restart qoc
```

## Deploy native binary

```bash
# On build host
export QUARKUS_OIDC_ENABLED=true  # if OIDC desired
mvn -o -pl quarkus -am clean package -Dnative -DskipTests
scp quarkus/target/*-runner user@vm:/opt/qoc/qoc-runner
ssh user@vm systemctl restart qoc
```
