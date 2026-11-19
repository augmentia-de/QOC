# CI/CD and Operations Structure

> **Status:** Draft · 2026-11-04
> **Goal:** One codebase, five operating modes, reproducible builds and
> deployments – locally, on a GCP VM, on GCP Serverless and on AWS
> Fargate.

---

## 1. Principles

1. **One codebase, multiple artifacts.** There is no GCP and no AWS branch
   in the code. Differences lie in build parameters, configuration and
   deployment targets.
2. **Build once, deploy many.** Tests and builds run once in the pipeline.
   Deployments use the same immutable artifacts.
3. **Separate build time from runtime.** Some Quarkus settings are fixed at
   build time (database kind, driver, Flyway locations, native reflection).
   They must not be changed at runtime.
4. **Single instance for the stateful engine.** The engine holds sessions,
   running prompts and background tasks in memory. Until state is externalized:
   one instance per deployment.
5. **Secrets never in the repository.** Runtime secrets come from Secret Manager,
   AWS Secrets Manager or a protected env file.
6. **Immutable artifacts.** Production uses SHA/version tags, never `latest`.
7. **Native binary for production.** Faster cold starts, lower memory footprint.
   JVM remains for development and debugging.
8. **Health check as a vital sign.** `/api/health` is the common health endpoint
   for all platforms.

---

## 2. Operating Modes at a Glance

There are **five operating modes**; mode 2 has two variants (JVM and Native).

| # | Mode | Artifact | Database | Auth | Workspace | Compute |
|---|---|---|---|---|---|---|
| 1 | **Local (Host)** | Quarkus Dev / Fast-Jar | SQLite or local PostgreSQL | none / Basic | local directories | Host process |
| 2 | **Local Docker** | Container image | PostgreSQL (Host/Neon) | Basic | Bind mounts + Volume | Docker |
| 2a | of which JVM | `qoc-engine:local` | PostgreSQL | Basic | Bind mounts | Docker |
| 2b | of which Native | `qoc-engine:native` | PostgreSQL | Basic | Bind mounts | Docker |
| 3 | **GCP VM Native** | Native binary + systemd | Neon/Cloud SQL | Basic + Nginx | `/data` on the VM | e2-micro VM |
| 4 | **GCP Serverless** | Native container | Cloud SQL (PostgreSQL) | IAP / Trusted Proxy | GCS FUSE or image | Cloud Run |
| 5 | **AWS Fargate** | Native container | RDS (PostgreSQL) | ALB + Cognito / Proxy | EFS or image | ECS Fargate |

### 2.1 Common Runtime Env Variables

These variables apply in all cloud/container modes:

```properties
ENGINE_WORKSPACE=/data/workspace
QUARKUS_HTTP_HOST=0.0.0.0
QUARKUS_HTTP_PORT=8080
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://<host>/<db>?sslmode=require
QUARKUS_DATASOURCE_USERNAME=<user>
QUARKUS_DATASOURCE_PASSWORD=<secret>
ENGINE_PROVIDER_URL=https://<provider>/v1/chat/completions
ENGINE_MODEL_ID=<model>
ENGINE_PROVIDER_API_KEY=<secret>
OPENCODE_SERVER_PASSWORD=<secret>          # only with Basic Auth
HOME=/data/home
LOG_DIR=/data/log
```

### 2.2 Build-Time Env Variables

These values are baked into the artifact and must match the target:

```bash
ENGINE_DB_KIND=postgresql
ENGINE_DB_DRIVER=org.postgresql.Driver
QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration
```

For SQLite (only local/E2E) the following applies instead:

```bash
ENGINE_DB_KIND=sqlite
ENGINE_DB_DRIVER=org.sqlite.JDBC
QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration-sqlite
```

> **Important:** A native binary or image built for SQLite does not work with
> PostgreSQL – and vice versa. Cloud deployments must be built with PostgreSQL.

---

## 3. Build Artifacts

| Artifact | Production | Usage |
|---|---|---|
| **JVM Fast-Jar** | `mvn -pl quarkus -am package -DskipTests` | Local, Docker-JVM, Debugging |
| **Native Binary** | `mvn -pl quarkus -am package -Dnative ...` | Local native, GCP VM, Cloud Run, Fargate |
| **JVM Image** | `deploy/build-image.sh` | Local Docker JVM |
| **Native Image** | `deploy/Dockerfile.native` | Local Docker native, Cloud Run, Fargate |
| **Agent-/Workspace files** | `deploy/AGENTS.native.md`, `.opencode/` | Bake into image or mount |

### 3.1 Native Build (Reference)

```bash
cd qoc
export JAVA_HOME=/tmp/graalvm25/graalvm-community-25.4.4.1.1+1.1
export PATH="$JAVA_HOME/bin:/opt/maven/apache-maven-3.9.10/bin:$PATH"
export ENGINE_DB_KIND=postgresql
export ENGINE_DB_DRIVER=org.postgresql.Driver
export QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration

mvn -pl quarkus -am clean package \
  -Dquarkus.package.type=native \
  -Dquarkus.profile=prod,native \
  -Dquarkus.native.additional-build-args="--initialize-at-run-time=io.quarkiverse.langchain4j.QuarkusJsonCodecFactory\$SnakeCaseObjectMapperHolder,--initialize-at-run-time=io.quarkiverse.langchain4j.openai.common.OpenAiRestApi\$ObjectMapperHolder" \
  -DskipTests -Djacoco.skip=true
```

Result: `quarkus/target/quarkus-1.0.0-SNAPSHOT-runner`.

### 3.2 Versioning

- **Git SHA** as primary tag: `qoc:<sha>`
- **SemVer** on releases: `qoc:1.2.3`
- `latest` only for local/dev images, never in production.
- Archive the native binary with SHA/version in the name as well.

---

## 4. Repository Structure (Target State)

```
qoc/
├── .github/
│   └── workflows/
│       ├── ci.yml                     # Test + Build + Image
│       ├── deploy-gcp-vm.yml          # Native Binary per SSH/systemd
│       ├── deploy-gcp-cloudrun.yml    # Cloud Run
│       └── deploy-aws-fargate.yml     # ECS Fargate
├── deploy/
│   ├── Dockerfile                     # JVM image (present)
│   ├── Dockerfile.native              # Native image (present)
│   ├── docker-compose.yml             # All variants as profile services
│   ├── docker-compose.oidc.yml        # OIDC overlay (qoc-local/qoc-dev)
│   ├── docker-compose.dev-jvm.yml     # JVM mode overlay (qoc-dev)
│   ├── docker-compose.docker-socket.yml # Testcontainers overlay (qoc-dev)
│   ├── seccomp-qoc-shell.json         # Seccomp profile qoc-secure-shell
│   ├── .env.example                   # common environment of all variants
│   ├── .env.oidc.example              # OIDC overlay template
│   ├── docker-dev/                    # Dev image, entrypoint, devcontainer
│   ├── docker-secure-shell/           # Shell-isolated variant (+ home/)
│   ├── docker-<variant>/              # README per variant, otherwise no compose
│   ├── build-image.sh                 # JVM image (present)
│   ├── build-image-sqlite.sh          # SQLite/E2E (present)
│   ├── gcp-vm/                        # GCP VM deployment (systemd/nginx/Auth0)
│   │   ├── gcp-auth0.sh
│   │   ├── gcp-auth0.env.example
│   │   ├── gcp-ssl.sh
│   │   ├── nginx.conf
│   │   ├── qoc-native.service         # systemd for GCP VM
│   │   ├── qoc.service                # systemd JVM (Legacy)
│   │   ├── qoc-prepare.sh
│   │   └── vm.md                      # VM runbook
│   ├── gcp/
│   │   ├── cloudrun-service.yaml      # Cloud Run service (new)
│   │   ├── cloudsql.md                # Cloud SQL connection (new)
│   │   └── iap.md                     # IAP/trusted proxy (new)
│   └── aws/
│       ├── task-definition.json       # ECS task (new)
│       ├── service.json               # ECS service (new)
│       └── alb-cognito.md             # ALB/Cognito (new)
├── scripts/
│   ├── build-native.sh                # encapsulate native build (new)
│   ├── build-images.sh                # build both images (new)
│   ├── deploy-gcp-vm.sh               # scp + systemd (new)
│   └── deploy-cloudrun.sh             # gcloud run deploy (new)
├── start.sh                           # local dev start (present)
├── start-native.sh                    # local native start (present)
└── docs/
    └── cicd.md                        # this document
```

> The files marked "neu" are suggestions for the next expansion stage. The
> existing files already work.

---

## 5. CI/CD Pipeline

### 5.1 Triggers

| Trigger | Purpose |
|---|---|
| Pull Request | Tests, build check, no deploy |
| Push to `main` | Tests, build, images, deploy to staging |
| Git tag `v*` | Release build, deploy to production |
| `workflow_dispatch` | Manual deploy/rollback |

### 5.2 Stages

```
test ──► build-jvm ──► build-native ──► image ──► deploy-*
                                  │
                                  └──► archive binary
```

| Stage | Content | Gate |
|---|---|---|
| **test** | `mvn test`, JaCoCo report | ≥ 80 % instructions |
| **build-jvm** | Fast-Jar with PostgreSQL build-time values | Tests green |
| **build-native** | GraalVM native binary | Tests green |
| **image** | Build, tag and push JVM and native images | Binary present |
| **deploy-gcp-vm** | Binary via `gcloud compute scp`, systemd restart | manual approval |
| **deploy-gcp-cloudrun** | `gcloud run deploy` | manual approval |
| **deploy-aws-fargate** | `aws ecs update-service` | manual approval |

### 5.3 Example: GitHub Actions (Skeleton)

```yaml
name: ci
on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: mvn -B test
      - uses: actions/upload-artifact@v4
        with:
          name: jacoco
          path: engine/target/site/jacoco/

  build-native:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: graalvm
      - run: |
          export ENGINE_DB_KIND=postgresql
          export ENGINE_DB_DRIVER=org.postgresql.Driver
          export QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration
          mvn -B -pl quarkus -am package -Dnative -DskipTests -Djacoco.skip=true
      - uses: actions/upload-artifact@v4
        with:
          name: qoc-native
          path: quarkus/target/*-runner
```

### 5.4 Pipeline Auth Against GCP/AWS

- **GCP:** Workload Identity Federation (OIDC), no service account key.
  `google-github-actions/auth@v2`.
- **AWS:** OIDC provider + IAM role, no static keys.
  `aws-actions/configure-aws-credentials@v4`.
- **Registry:** GCP Artifact Registry and AWS ECR; optionally additionally GHCR
  as a shared store.

### 5.5 Caching

- Maven repository (`~/.m2`) cache.
- GraalVM/native build cache, if available.
- Docker layer cache (`docker/build-push-action` with `cache-from`/`cache-to`).

---

## 6. Operating Modes in Detail

### 6.1 Mode 1 – Local (Host)

**Purpose:** Development, debugging, fast iteration.

```bash
cd qoc
cp .env.example .env        # adjust
./start.sh                  # Quarkus Dev, port 8007
```

- DB: SQLite or local PostgreSQL (`ENGINE_DB_KIND`).
- Auth: none (loopback) or Basic via `OPENCODE_SERVER_PASSWORD`.
- Workspace: local directory (`ENGINE_WORKSPACE`).
- Health: `curl http://127.0.0.1:8007/api/health`.

**Native locally:**

```bash
./start-native.sh
```

Prerequisite: Native binary was built with the matching DB kind.

### 6.2 Mode 2a – Local Docker JVM

```bash
cd qoc
cp deploy/.env.example deploy/.env
./deploy/build-image.sh
cd deploy
docker compose up -d qoc-local
```

- Image: `qoc-engine:local`.
- Port: `8071` (fixed).
- DB: PostgreSQL (Host/Neon), build-time value PostgreSQL.
- Workspace/Logs: targeted bind mounts, no broad `/data`.
- Health: `curl http://127.0.0.1:8071/api/health/ready`.

### 6.3 Mode 2b – Local Docker Native

```bash
cd qoc
# Build native binary with PostgreSQL (see 3.1)
cd deploy
docker compose --profile native up -d --build
```

- Image: `qoc-engine:native`.
- Port: `8077` (fixed).
- Same mounts and hardening options as the JVM variant.
- Health: `curl http://127.0.0.1:8077/api/health/ready`.

### 6.4 Mode 3 – GCP VM Native (without Docker)

**Goal:** Native binary directly on a GCP VM, operated via systemd.

**Prerequisites:**

- Debian/Ubuntu VM, `qoc` user, `/opt/qoc`, `/data`, `/etc/qoc/env`.
- Native binary built for PostgreSQL.
- Nginx as reverse proxy and Basic Auth (optional).

**Build:**

```bash
# See 3.1; result: quarkus/target/quarkus-1.0.0-SNAPSHOT-runner
```

**Deploy:**

```bash
gcloud compute scp quarkus/target/quarkus-1.0.0-SNAPSHOT-runner \
  <vm>:/tmp/qoc-runner-new --zone=<zone>
gcloud compute scp deploy/AGENTS.native.md \
  <vm>:/tmp/AGENTS.native.md --zone=<zone>

gcloud compute ssh <vm> --zone=<zone> --command '
  sudo systemctl stop qoc
  sudo cp -a /opt/qoc/qoc-runner /opt/qoc/qoc-runner.bak-$(date +%Y%m%d-%H%M%S)
  sudo install -o qoc -g qoc -m 755 /tmp/qoc-runner-new /opt/qoc/qoc-runner
  sudo install -o qoc -g qoc -m 644 /tmp/AGENTS.native.md /data/workspace/AGENTS.md
  sudo systemctl start qoc
  sleep 5
  sudo systemctl is-active qoc
  curl -fsS --max-time 8 -u "opencode:$(sudo grep ^OPENCODE_SERVER_PASSWORD= /etc/qoc/env | cut -d= -f2-)" \
    http://127.0.0.1:8081/api/health
'
```

**systemd unit:** `deploy/gcp-vm/qoc-native.service`.

**Health:**

```bash
curl -fsS -u "opencode:$PASS" https://<domain>/api/health
```

**Rollback:** Copy back the backup binary and `systemctl restart qoc`.

### 6.5 Mode 4 – GCP Serverless (Cloud Run)

**Goal:** Native container on Cloud Run, single instance, external identity via IAP.

**Prerequisites:**

- Artifact Registry repository.
- Cloud SQL PostgreSQL instance and connection (Unix socket or VPC).
- Secret Manager for LLM/DB secrets.
- Cloud Run service with `min-instances=1`, `max-instances=1`.
- CPU "always allocated" for background tasks.

**Build & Push** (assumes the native binary built with PostgreSQL from 3.1):

```bash
docker build -f deploy/Dockerfile.native \
  -t <region>-docker.pkg.dev/<project>/qoc/qoc:<sha> .
docker push <region>-docker.pkg.dev/<project>/qoc/qoc:<sha>
```

**Deploy:**

```bash
gcloud run deploy qoc \
  --image=<region>-docker.pkg.dev/<project>/qoc/qoc:<sha> \
  --region=<region> \
  --min-instances=1 --max-instances=1 \
  --cpu=2 --memory=1Gi \
  --no-cpu-throttling \
  --port=8080 \
  --set-env-vars=ENGINE_DB_KIND=postgresql,ENGINE_WORKSPACE=/data/workspace \
  --set-secrets=QUARKUS_DATASOURCE_PASSWORD=db-password:latest,ENGINE_PROVIDER_API_KEY=llm-key:latest \
  --add-cloudsql-instances=<project>:<region>:<instance>
```

**Workspace:**

- Option A: Bake configuration (agents, settings) into the image; session dirs on
  tmpfs.
- Option B: Mount a GCS bucket via Cloud Storage FUSE (no file locking, mind
  eventual consistency).

**Auth:**

- IAP in front of Cloud Run; QOC in `AuthMode.PROXY`.
- GCP headers (`X-Goog-Authenticated-User-Email`) must be added in the
  `TrustedProxyFilter` (see section 8).

**Health:** Cloud Run health check on `/api/health`.

**Rollback:** `gcloud run services update-traffic qoc --to-revisions=<prev>=100`.

### 6.6 Mode 5 – AWS Fargate

**Goal:** Native container on ECS Fargate, single task, external identity via
ALB/Cognito.

**Prerequisites:**

- ECR repository.
- RDS PostgreSQL.
- Secrets Manager.
- ECS cluster, task definition, service with `desired-count=1`.
- ALB + Cognito (or Trusted Proxy) for auth.
- EFS for workspace (optional; alternatively config in image).

**Build & Push** (assumes native binary built with PostgreSQL from 3.1):

```bash
aws ecr get-login-password --region <region> | \
  docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker build -f deploy/Dockerfile.native \
  -t <account>.dkr.ecr.<region>.amazonaws.com/qoc:<sha> .
docker push <account>.dkr.ecr.<region>.amazonaws.com/qoc:<sha>
```

**Deploy:**

```bash
aws ecs update-service \
  --cluster qoc \
  --service qoc \
  --force-new-deployment \
  --task-definition qoc:<revision>
```

**Task definition (excerpt):**

```json
{
  "family": "qoc",
  "cpu": "1024",
  "memory": "2048",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "containerDefinitions": [
    {
      "name": "qoc",
      "image": "<account>.dkr.ecr.<region>.amazonaws.com/qoc:<sha>",
      "portMappings": [{"containerPort": 8080}],
      "environment": [
        {"name": "ENGINE_DB_KIND", "value": "postgresql"},
        {"name": "ENGINE_WORKSPACE", "value": "/data/workspace"}
      ],
      "secrets": [
        {"name": "QUARKUS_DATASOURCE_PASSWORD", "valueFrom": "arn:aws:secretsmanager:..."},
        {"name": "ENGINE_PROVIDER_API_KEY", "valueFrom": "arn:aws:secretsmanager:..."}
      ],
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -fsS http://127.0.0.1:8080/api/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3
      }
    }
  ]
}
```

> **Note:** The current `Dockerfile.native` contains no `curl`. For the ECS
> health check either include `curl` in the image or move the check to a
> TCP/HTTP check of the ALB.

**Auth:**

- ALB with OIDC/Cognito; header `x-amzn-oidc-identity` in
  `TrustedProxyFilter`.
- Alternatively: QOC Basic Auth and ALB only as TLS terminator.

**Health:** ALB target group health check on `/api/health`.

**Rollback:** Activate previous task definition revision.

---

## 7. Platform Configuration

### 7.1 GCP

| Component | Recommendation |
|---|---|
| Registry | Artifact Registry |
| Compute (Serverless) | Cloud Run, `min=max=1`, CPU always allocated |
| DB | Cloud SQL PostgreSQL (Unix socket) |
| Secrets | Secret Manager |
| Storage | GCS FUSE or config in image |
| Auth | IAP + `AuthMode.PROXY` |
| Logs/Metrics | Cloud Logging / Cloud Monitoring / OpenTelemetry |
| Deploy auth | Workload Identity Federation |

### 7.2 AWS

| Component | Recommendation |
|---|---|
| Registry | ECR |
| Compute | ECS Fargate, `desired-count=1` |
| DB | RDS PostgreSQL |
| Secrets | Secrets Manager |
| Storage | EFS or config in image |
| Auth | ALB + Cognito / Trusted Proxy |
| Logs/Metrics | CloudWatch / OpenTelemetry |
| Deploy auth | OIDC + IAM role |

### 7.3 Common Requirements

- PostgreSQL with `sslmode=require`.
- Flyway migrations on startup (`QUARKUS_FLYWAY_LOCATIONS`).
- Health check `/api/health`.
- Outbound network access only to LLM/DB/tool endpoints.
- Persistent DB backups (Cloud SQL automated backups / RDS snapshots).

---

## 8. Auth and Identity

### 8.1 Modes

| Environment | AuthMode | Identity |
|---|---|---|
| Local | `NONE` or `BASIC` | none / `opencode` |
| Local Docker | `BASIC` | `opencode` |
| GCP VM | `BASIC` (Nginx) | `opencode` |
| GCP Serverless | `PROXY` (IAP) | GCP user |
| AWS Fargate | `PROXY` (ALB/Cognito) | Cognito user |

### 8.2 Header Mapping

The `TrustedProxyFilter` currently reads:

- `X-Forwarded-User`
- `X-Remote-User`
- `Remote-User`

For GCP/AWS the following must additionally be recognized:

| Platform | Header |
|---|---|
| GCP IAP | `X-Goog-Authenticated-User-Email`, `X-Goog-Authenticated-User-Id` |
| AWS ALB OIDC | `x-amzn-oidc-identity`, `x-amzn-oidc-data` (JWT) |

**Recommendation:** A small header mapper in the `TrustedProxyFilter` that
converts all sources into a `Principal`. This is a one-time, small extension
and makes the app platform-independent.

### 8.3 Identity Propagation (Expansion Stage)

Currently identity ends at the HTTP layer. For full separation the following
points must be extended:

1. `ToolContext` with `principalId`/`Principal`.
2. `Engine`/`ToolRuntime` pass the principal to tools.
3. `PermissionEngine` receives role-/group-based rules.
4. Webhook payload with `principalId`, `roles`, `groups`.
5. MCP credential lookup per (agent, server, principal).

This is a separate refactor and not part of the CI/CD base structure.

---

## 9. Database and Storage

### 9.1 Database

| Environment | DB | Build-time value |
|---|---|---|
| Local Dev | SQLite or PostgreSQL | `sqlite` or `postgresql` |
| Local Docker | PostgreSQL | `postgresql` |
| GCP VM | Neon/Cloud SQL | `postgresql` |
| GCP Serverless | Cloud SQL | `postgresql` |
| AWS Fargate | RDS | `postgresql` |

- Migrations: Flyway, `classpath:db/migration` (PostgreSQL) resp.
  `classpath:db/migration-sqlite` (SQLite).
- Migrations must be backward compatible (rolling deploy).
- Backups: Cloud SQL automated backups, RDS snapshots, Neon PITR.

### 9.2 Workspace

| Environment | Persistence |
|---|---|
| Local | local directory |
| Docker | Bind mount + volume |
| GCP VM | `/data` on the VM (persistent disk) |
| GCP Serverless | GCS FUSE or image |
| AWS Fargate | EFS or image |

Recommendation: Bake agent/settings configuration into the image (immutable,
reproducible). Only persist mutable session/tool data.

---

## 10. Stability Checklist

- [ ] `mvn test` green, JaCoCo ≥ 80 % instructions.
- [ ] Native build reproducible (GraalVM version pinned).
- [ ] Build-time DB values match the target.
- [ ] Health check `/api/health` reachable in every environment.
- [ ] Single instance (`min=max=1` or `desired-count=1`).
- [ ] CPU always allocated (Cloud Run) for background tasks.
- [ ] Secrets from Secret Manager / Secrets Manager, not in the image.
- [ ] Image tags immutable (SHA/SemVer), no `latest` in prod.
- [ ] Rollback path documented and tested.
- [ ] DB backups enabled.
- [ ] Flyway migrations backward compatible.
- [ ] Logs centralized (Cloud Logging / CloudWatch / OTel).
- [ ] Outbound network access restricted.
- [ ] Basic Auth passwords rotatable.
- [ ] Native image contains `git`, `util-linux`, `bubblewrap` (if needed).

---

## 11. Rollback

| Mode | Rollback |
|---|---|
| Local | check out previous commit |
| Docker | start previous image tag |
| GCP VM | copy back backup binary from `/opt/qoc/qoc-runner.bak-*`, `systemctl restart qoc` |
| GCP Cloud Run | traffic to previous revision |
| AWS Fargate | activate previous task definition revision |

---

## 12. Open Points / Next Steps

1. **Create CI workflows:** `.github/workflows/ci.yml`,
   `deploy-gcp-vm.yml`, `deploy-gcp-cloudrun.yml`, `deploy-aws-fargate.yml`.
2. **Encapsulate build scripts:** `scripts/build-native.sh`,
   `scripts/build-images.sh`, `scripts/deploy-*.sh`.
3. **Extend TrustedProxyFilter:** GCP/AWS header mapping.
4. **Cloud Run/ECS templates:** `deploy/gcp/`, `deploy/aws/`.
5. **Optional IaC:** Terraform modules for GCP and AWS so infrastructure
   becomes reproducible.
6. **Optional state externalization:** Redis/DB for sessions if true
   multi-instance scaling is needed.

---

## 13. Quick Reference

```bash
# Tests
cd qoc && mvn test

# Local Dev
./start.sh

# Local Native (binary present)
./start-native.sh

# Docker JVM
./deploy/build-image.sh && (cd deploy && docker compose up -d qoc-local)

# Docker Native
(cd deploy && docker compose --profile native up -d --build)

# GCP VM Native
#   Build native binary, copy via gcloud scp, systemctl restart qoc

# Cloud Run
#   Build/push native image, gcloud run deploy ...

# AWS Fargate
#   Build/push native image, aws ecs update-service ...
```
