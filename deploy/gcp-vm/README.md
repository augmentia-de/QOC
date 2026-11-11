# GCP VM Deployment

Dieser Ordner bündelt die Konfiguration und Runbooks für den Betrieb von QOC
auf einer GCP-VM (native Binary, systemd, nginx, Auth0/OIDC).

## Dateien

| Datei | Zweck |
|---|---|
| `vm.md` | Vollständiges VM-Runbook (Build, systemd, nginx, Verifikation, Rollback) |
| `gcp-auth0.sh` | Richtet Auth0/OIDC-Werte ein, schreibt `/etc/qoc/env` und `/etc/qoc/ui-config.js`, ruft `gcp-ssl.sh` auf |
| `gcp-auth0.env.example` | Vorlage für `gcp-auth0.env` (gitignored) |
| `gcp-ssl.sh` | DNS-/TLS-/nginx-Setup (Certbot, Reverse Proxy, Basic/OIDC-Modus) |
| `nginx.conf` | Beispiel für einen nginx-Reverse-Proxy mit `X-QOC-User` |
| `qoc-native.service` | systemd-Unit für die native Binary |
| `qoc.service` | systemd-Unit für das JVM-Fat-Jar (Legacy) |
| `qoc-prepare.sh` | Legt Daten-/Workspace-Verzeichnisse an und setzt Ownership |
| `gcp-sandbox-setup.sh` | Einmaliges Setup der Shell-Sandbox (bubblewrap) auf der VM |

## Reihenfolge

```bash
cd qoc

# 1. Auth0-Konfiguration vorbereiten
cp deploy/gcp-vm/gcp-auth0.env.example deploy/gcp-vm/gcp-auth0.env
# Werte in deploy/gcp-vm/gcp-auth0.env anpassen

set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh check
./deploy/gcp-vm/gcp-auth0.sh apply
```

Das vollständige Vorgehen inklusive Binary-Build, systemd-Installation,
Verifikation und Rollback steht in `vm.md`.

## Hinweise

- `deploy/gcp-vm/gcp-auth0.env` und `deploy/gcp-vm/gcp-ssl.sh` sind
  gitignored (lokale Zugangsdaten bzw. umgebungsspezifisches TLS-Setup).
- `vm.md` ist als Runbook versioniert; lokale Anpassungen sollten klar
  markiert werden.
- Die Skripte verwenden `SCRIPT_DIR`, können also aus diesem Ordner heraus
  aufgerufen werden.
- Allgemeine Docker-/Image-Builds liegen weiterhin direkt unter `deploy/`.
