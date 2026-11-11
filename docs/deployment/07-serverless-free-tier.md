# Serverless Free Tier – Vergleich und Konfiguration

> **Status:** spec-only (Skripte vorhanden, Implementierung ausstehend)
> **Quelle:** `deploy/aws-lambda-serverless/deploy.sh`, `deploy/gcp-cloudrun-serverless/deploy.sh`

## 1. Free-Tier-Übersicht

| Eigenschaft | AWS Lambda | GCP Cloud Run |
|---|---|---|
| Requests/Monat | 1.000.000 | 2.000.000 |
| Compute/Monat | 400.000 GB-Sekunden | 360.000 vCPU-Sekunden + 180.000 GiB-Sekunden |
| Dauer | Immer kostenlos (seit 12/2023) | Immer kostenlos |
| Timeout | 15 Min. (900 s) | 60 Min. (3600 s) |
| Max. RAM | 10.240 MB | 32.768 MiB |
| Max. Ephemeral Storage | 10.240 MB | N/A (Volume Mounts) |
| Scale-to-Zero | Ja | Ja |
| Cold Start (Native) | ~1-3 s | ~1-3 s |

## 2. Auswirkung der RAM-Konfiguration

### AWS Lambda (400.000 GB-Sekunden/Monat)

| RAM | Sekunden/Monat | Stunden/Monat | Empfohlen für |
|---|---|---|---|
| 128 MB | ~3.125.000 | ~868 | Minimal (nur Text-Prompts) |
| 256 MB | ~1.562.500 | ~434 | Leicht (kleine Agenten) |
| 512 MB | ~781.250 | ~217 | **Standard** (empfohlen) |
| 1.024 MB | ~390.625 | ~108 | Mittel (größere Workspaces) |
| 2.048 MB | ~195.312 | ~54 | Schwer (viele Tools) |

### GCP Cloud Run (180.000 GiB-Sekunden/Monat, memory-bound)

| Memory | Sekunden/Monat | Stunden/Monat | Empfohlen für |
|---|---|---|---|
| 128 Mi | ~1.440.000 | ~400 | Minimal |
| 256 Mi | ~720.000 | ~200 | Leicht |
| 512 Mi | ~360.000 | ~100 | **Standard** (empfohlen) |
| 1.024 Gi | ~180.000 | ~50 | Mittel |
| 2.048 Gi | ~90.000 | ~25 | Schwer |

## 3. Empfohlene Konfigurationen

### Minimal (Free Tier optimal)

```bash
# AWS
MEMORY_MB=256 EPHEMERAL_STORAGE_MB=512 ./deploy.sh

# GCP
MEMORY=256Mi CPU=1 MIN_INSTANCES=0 MAX_INSTANCES=1 ./deploy.sh
```

### Standard (empfohlen)

```bash
# AWS
MEMORY_MB=512 EPHEMERAL_STORAGE_MB=1024 ./deploy.sh

# GCP
MEMORY=512Mi CPU=1 MIN_INSTANCES=0 MAX_INSTANCES=2 ./deploy.sh
```

### Produktiv (mehr Ressourcen)

```bash
# AWS
MEMORY_MB=1024 EPHEMERAL_STORAGE_MB=2048 TIMEOUT_SEC=900 ./deploy.sh

# GCP
MEMORY=1Gi CPU=2 MIN_INSTANCES=1 MAX_INSTANCES=4 ./deploy.sh
```

## 4. Persistenz

| Speicher | AWS Lambda | GCP Cloud Run |
|---|---|---|
| Ephemeral (/tmp) | 512 MB–10 GB (konfigurierbar) | N/A (container-internal) |
| Persistent | EFS (Mount, ~$0.30/GB/Monat) | Cloud Storage FUSE oder Volume Mounts |
| Agent-Cache | Geht bei Cold Start verloren (ohne EFS) | Geht bei Scale-to-Zero verloren (ohne Volume) |

**Empfehlung:** Agent-Sync-Intervall niedrig halten (z. B. 60 s), damit bei
Cold Start schnell neu synchronisiert wird. EFS/Volume nur wenn Agent-Cache
über Cold Starts hinweg persistieren soll.

## 5. Cold-Start-Verhalten

- **Native Binary:** ~1-3 s Cold Start (Quarkus Native)
- **JVM:** ~5-15 s Cold Start
- **Agent-Sync bei Cold Start:** +1-3 s (je nach Anzahl der Agenten)
- **Empfehlung:** Native Image verwenden; `MIN_INSTANCES=1` bei GCP für
  latenzkritische Workloads (kostet dann aber Free-Tier-Budget)

## 6. Kosten außerhalb des Free Tiers

| Komponente | AWS | GCP |
|---|---|---|
| EFS / Volume | ~$0.30/GB/Monat | ~$0.026/GB/Monat (Cloud Storage) |
| Datenübertragung | ~$0.09/GB (ausgehend) | ~$0.12/GB (ausgehend) |
| Secrets Manager | ~$0.40/Secret/Monat | Secret Manager: ~$0.06/10k Zugriffe |
| Container Registry | ECR: ~$0.10/GB/Monat | Artifact Registry: ~$0.10/GB/Monat |

## 7. Einschränkungen (beide Plattformen)

- Max. 15 Min. (Lambda) / 60 Min. (Cloud Run) pro Invocation
- Keine dauerhaften Hintergrundprozesse (kein SSE-Streaming)
- Concurrency = 1 empfohlen (Sessions/Event-Feed prozesslokal)
- Kein bwrap (keine User Namespaces)
- Shell-Sandbox deaktiviert (`ENGINE_SHELL_SANDBOX_ENABLED=false`)
- Agent-Cache flüchtig (ohne EFS/Volume)

## 8. MCP-Nutzung

Beide Varianten exponieren den MCP-Server unter:

```
https://<function-url>/mcp
```

Tool: `qoc_run_agent` (Standard-Modus). `qoc_run_agent_with_config` ist
spec-only (geplant) und erfordert die Implementierung des Serverless-Modus.
