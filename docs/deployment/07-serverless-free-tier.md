# Serverless Free Tier – Comparison and Configuration

> **Status:** spec-only (scripts present, implementation pending)
> **Source:** `deploy/aws-lambda-serverless/deploy.sh`, `deploy/gcp-cloudrun-serverless/deploy.sh`

## 1. Free-Tier Overview

| Property | AWS Lambda | GCP Cloud Run |
|---|---|---|
| Requests/month | 1,000,000 | 2,000,000 |
| Compute/month | 400,000 GB-seconds | 360,000 vCPU-seconds + 180,000 GiB-seconds |
| Duration | Always free (since 12/2023) | Always free |
| Timeout | 15 min (900 s) | 60 min (3600 s) |
| Max. RAM | 10,240 MB | 32,768 MiB |
| Max. Ephemeral Storage | 10,240 MB | N/A (Volume Mounts) |
| Scale-to-Zero | Yes | Yes |
| Cold Start (Native) | ~1-3 s | ~1-3 s |

## 2. Impact of RAM Configuration

### AWS Lambda (400,000 GB-seconds/month)

| RAM | Seconds/month | Hours/month | Recommended for |
|---|---|---|---|
| 128 MB | ~3,125,000 | ~868 | Minimal (text prompts only) |
| 256 MB | ~1,562,500 | ~434 | Light (small agents) |
| 512 MB | ~781,250 | ~217 | **Standard** (recommended) |
| 1,024 MB | ~390,625 | ~108 | Medium (larger workspaces) |
| 2,048 MB | ~195,312 | ~54 | Heavy (many tools) |

### GCP Cloud Run (180,000 GiB-seconds/month, memory-bound)

| Memory | Seconds/month | Hours/month | Recommended for |
|---|---|---|---|
| 128 Mi | ~1,440,000 | ~400 | Minimal |
| 256 Mi | ~720,000 | ~200 | Light |
| 512 Mi | ~360,000 | ~100 | **Standard** (recommended) |
| 1,024 Gi | ~180,000 | ~50 | Medium |
| 2,048 Gi | ~90,000 | ~25 | Heavy |

## 3. Recommended Configurations

### Minimal (Free Tier optimal)

```bash
# AWS
MEMORY_MB=256 EPHEMERAL_STORAGE_MB=512 ./deploy.sh

# GCP
MEMORY=256Mi CPU=1 MIN_INSTANCES=0 MAX_INSTANCES=1 ./deploy.sh
```

### Standard (recommended)

```bash
# AWS
MEMORY_MB=512 EPHEMERAL_STORAGE_MB=1024 ./deploy.sh

# GCP
MEMORY=512Mi CPU=1 MIN_INSTANCES=0 MAX_INSTANCES=2 ./deploy.sh
```

### Production (more resources)

```bash
# AWS
MEMORY_MB=1024 EPHEMERAL_STORAGE_MB=2048 TIMEOUT_SEC=900 ./deploy.sh

# GCP
MEMORY=1Gi CPU=2 MIN_INSTANCES=1 MAX_INSTANCES=4 ./deploy.sh
```

## 4. Persistence

| Storage | AWS Lambda | GCP Cloud Run |
|---|---|---|
| Ephemeral (/tmp) | 512 MB–10 GB (configurable) | N/A (container-internal) |
| Persistent | EFS (mount, ~$0.30/GB/month) | Cloud Storage FUSE or Volume Mounts |
| Agent cache | Lost on cold start (without EFS) | Lost on scale-to-zero (without volume) |

**Recommendation:** Keep the agent sync interval low (e.g. 60 s), so it
resynchronizes quickly on a cold start. Use EFS/volume only if the agent
cache should persist across cold starts.

## 5. Cold-Start Behavior

- **Native Binary:** ~1-3 s cold start (Quarkus Native)
- **JVM:** ~5-15 s cold start
- **Agent sync on cold start:** +1-3 s (depending on the number of agents)
- **Recommendation:** Use the native image; `MIN_INSTANCES=1` on GCP for
  latency-critical workloads (but this consumes Free-Tier budget)

## 6. Costs Outside the Free Tier

| Component | AWS | GCP |
|---|---|---|
| EFS / Volume | ~$0.30/GB/month | ~$0.026/GB/month (Cloud Storage) |
| Data transfer | ~$0.09/GB (egress) | ~$0.12/GB (egress) |
| Secrets Manager | ~$0.40/secret/month | Secret Manager: ~$0.06/10k accesses |
| Container Registry | ECR: ~$0.10/GB/month | Artifact Registry: ~$0.10/GB/month |

## 7. Limitations (both platforms)

- Max. 15 min (Lambda) / 60 min (Cloud Run) per invocation
- No persistent background processes (no SSE streaming)
- Concurrency = 1 recommended (sessions/event feed are process-local)
- No bwrap (no user namespaces)
- Shell sandbox disabled (`ENGINE_SHELL_SANDBOX_ENABLED=false`)
- Agent cache is volatile (without EFS/volume)

## 8. MCP Usage

Both variants expose the MCP server under:

```
https://<function-url>/mcp
```

Tool: `qoc_run_agent` (standard mode). `qoc_run_agent_with_config` is
spec-only (planned) and requires the implementation of the serverless mode.
