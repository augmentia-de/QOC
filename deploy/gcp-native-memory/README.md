# GCP Native Deployment (Memory-Modus, keine DB)

Dieser Ordner bündelt ein Deployment von QOC als **native Binary** auf einer
GCP-VM, bei dem der Engine-Runner **komplett in-memory** läuft und
**Workflow-Daten als Dateien** persistiert werden. Es wird **keine Datenbank**
genutzt.

## Eigenschaften

| Aspekt | Wert |
|---|---|
| Engine-Storage | `memory` (Sessions/Journal im Heap) |
| Workflow-Storage | `file` (`/data/wf`) |
| Recovery | deaktiviert (stateless by design) |
| Datenbank | keine (keine JDBC-URL, kein Flyway) |
| Binary | GraalVM native image |

## Voraussetzungen

- GCP-VM (z. B. `e2-micro`), Zone `us-central1-a`, laufend.
- gcloud CLI konfiguriert (`gcloud auth login`, `gcloud config set project`).
- GraalVM 21+ mit `native-image`, Maven 3.9+.
- SSH/sudo-Zugriff auf die VM.

## Dateien

| Datei | Zweck |
|---|---|
| `env.memory.example` | Vorlage für `/etc/qoc/env` |
| `qoc-memory.service` | systemd-Unit (native, memory) |
| `qoc-prepare-memory.sh` | Legt `/data`, `/data/wf`, `/data/workspace`, `/data/home` an |
| `build-memory.sh` | Native Build im Memory-Modus |
| `deploy.sh` | Provisioniert die VM (Binary, Env, Unit, Prepare) |

## Ablauf

### 1. Native Binary bauen
```bash
cd qoc
./deploy/gcp-native-memory/build-memory.sh
```

### 2. Env-Datei vorbereiten (optional)
```bash
cp deploy/gcp-native-memory/env.memory.example deploy/gcp-native-memory/env.memory
# Werte anpassen (Security, Workspace, ...), dann ENV_FILE setzen:
# export ENV_FILE=/pfad/zur/env.memory
```

### 3. Auf die VM deployen
```bash
cd qoc
export GCP_PROJECT=gen-lang-client-0960862878
export GCP_ZONE=us-central1-a
export GCP_VM=quad-e2micro
./deploy/gcp-native-memory/deploy.sh
```

### 4. Verifikation
```bash
gcloud compute ssh quad-e2micro --zone=us-central1-a -- \
  'sudo systemctl is-active qoc && curl -fsS http://127.0.0.1:8081/api/health/live'
```

Workflow-Definitionen werden unter `/data/wf` als Dateien abgelegt und
überleben Neustarts. Engine-Sessions und Workflow-Runs leben im Heap und gehen
bei einem Neustart verloren (Memory-Modus by design).

## Hinweise

- **Keine Datenbank**: `QUARKUS_DATASOURCE_JDBC_URL` ist leer, Flyway deaktiviert.
  `DatabaseMigration` kehrt im Memory-Modus früh zurück.
- **Kein Auto-Resume**: `WorkflowRecovery` überspringt den Memory-Modus.
- **Nicht multi-node-fähig**: File-Store + Memory-Engine sind für eine
  einzelne Instanz gedacht.
- Für Produktion mit Persistenz von Sessions/Runs stattdessen den DB-Modus
  (`deploy/gcp-vm`) nutzen.

## Rollback

```bash
gcloud compute ssh quad-e2micro --zone=us-central1-a -- \
  'sudo systemctl stop qoc; sudo systemctl disable qoc'
```

Binary/Env bleiben erhalten; für einen Wechsel in den DB-Modus die
`deploy/gcp-vm`-Variante nutzen.
