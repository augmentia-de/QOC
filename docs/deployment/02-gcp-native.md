# GCP VM (Native, systemd)

> **Status:** implemented
> **Quelle:** `deploy/gcp-vm/vm.md`, `deploy/gcp-vm/qoc.service`, `deploy/gcp-vm/qoc-prepare.sh`, `docs/diagrams/10-deployment.md`

## Infrastruktur

| Eigenschaft | Wert |
|---|---|
| Instance | `quad-e2micro`, Zone `us-central1-a` |
| Maschinentyp | `e2-micro` (Free Tier) |
| Public IP | `34.72.145.97` |
| Service | `qoc.service` (systemd) |
| Binary | `/opt/qoc/qoc-runner` |
| Environment | `/etc/qoc/env` |
| Datenbank | Neon PostgreSQL |
| Workspace | `/data/workspace` |
| Reverse Proxy | nginx (80 -> 443 TLS) -> 127.0.0.1:8081 |

## Regeln

- Kein Build-Environment auf der VM (kein JDK/Maven).
- Kein Git; Worktree-Tools deaktiviert.
- Engine und MCP nur über nginx/HTTPS erreichbar.
- Basic Auth für legacy/admin; MCP nutzt OAuth 2.1 + PKCE + Bearer.

## Betrieb

```bash
# Status
systemctl status qoc
journalctl -u qoc -f

# Neustart
systemctl restart qoc

# Umgebung bearbeiten
nano /etc/qoc/env
systemctl restart qoc
```

## Native Binary deployen

```bash
# Auf Build-Host
export QUARKUS_OIDC_ENABLED=true  # wenn OIDC gewünscht
mvn -o -pl quarkus -am clean package -Dnative -DskipTests
scp quarkus/target/*-runner user@vm:/opt/qoc/qoc-runner
ssh user@vm systemctl restart qoc
```
