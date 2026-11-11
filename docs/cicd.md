# CI/CD und Betriebsstruktur

> **Status:** Entwurf · 2026-11-04
> **Ziel:** Eine Codebase, fünf Betriebsmodi, reproduzierbare Builds und
> Deployments – lokal, auf einer GCP-VM, auf GCP Serverless und auf AWS
> Fargate.

---

## 1. Grundsätze

1. **Eine Codebase, mehrere Artefakte.** Es gibt keinen GCP- und keinen
   AWS-Zweig im Code. Unterschiede liegen in Build-Parametern, Konfiguration
   und Deployment-Zielen.
2. **Build once, deploy many.** Tests und Builds laufen einmal in der Pipeline.
   Deployments verwenden dieselben, unveränderlichen Artefakte.
3. **Build-Zeit von Laufzeit trennen.** Einige Quarkus-Einstellungen sind
   build-time fixiert (Datenbank-Art, Treiber, Flyway-Locations, Native-
   Reflection). Sie dürfen nicht zur Laufzeit geändert werden.
4. **Single-Instance für die stateful Engine.** Die Engine hält Sessions,
   laufende Prompts und Background-Tasks im Speicher. Bis State externalisiert
   ist, gilt: eine Instanz pro Deployment.
5. **Secrets nie im Repository.** Laufzeit-Secrets kommen aus Secret Manager,
   AWS Secrets Manager oder einer geschützten Env-Datei.
6. **Unveränderliche Artefakte.** Produktion verwendet SHA-/Versions-Tags,
   nie `latest`.
7. **Native Binary für Produktion.** Schnellere Cold Starts, geringerer
   Speicherbedarf. JVM bleibt für Entwicklung und Debugging.
8. **Health-Check als Lebenszeichen.** `/api/health` ist der gemeinsame
   Health-Endpunkt für alle Plattformen.

---

## 2. Betriebsmodi im Überblick

Es gibt **fünf Betriebsmodi**; Modus 2 hat zwei Varianten (JVM und Native).

| # | Modus | Artefakt | Datenbank | Auth | Workspace | Compute |
|---|---|---|---|---|---|---|
| 1 | **Lokal (Host)** | Quarkus Dev / Fast-Jar | SQLite oder lokales PostgreSQL | keine / Basic | lokale Verzeichnisse | Host-Prozess |
| 2 | **Lokal Docker** | Container-Image | PostgreSQL (Host/Neon) | Basic | Bind-Mounts + Volume | Docker |
| 2a | davon JVM | `qoc-engine:local` | PostgreSQL | Basic | Bind-Mounts | Docker |
| 2b | davon Native | `qoc-engine:native` | PostgreSQL | Basic | Bind-Mounts | Docker |
| 3 | **GCP VM Native** | Native Binary + systemd | Neon/Cloud SQL | Basic + Nginx | `/data` auf der VM | e2-micro VM |
| 4 | **GCP Serverless** | Native Container | Cloud SQL (PostgreSQL) | IAP / Trusted Proxy | GCS FUSE oder Image | Cloud Run |
| 5 | **AWS Fargate** | Native Container | RDS (PostgreSQL) | ALB + Cognito / Proxy | EFS oder Image | ECS Fargate |

### 2.1 Gemeinsame Laufzeit-Env-Variablen

Diese Variablen gelten in allen Cloud-/Container-Modi:

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
OPENCODE_SERVER_PASSWORD=<secret>          # nur bei Basic Auth
HOME=/data/home
LOG_DIR=/data/log
```

### 2.2 Build-Zeit-Env-Variablen

Diese Werte werden in das Artefakt eingebacken und müssen zum Ziel passen:

```bash
ENGINE_DB_KIND=postgresql
ENGINE_DB_DRIVER=org.postgresql.Driver
QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration
```

Für SQLite (nur lokal/E2E) gilt stattdessen:

```bash
ENGINE_DB_KIND=sqlite
ENGINE_DB_DRIVER=org.sqlite.JDBC
QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration-sqlite
```

> **Wichtig:** Ein für SQLite gebautes Native-Binary oder Image funktioniert
> nicht mit PostgreSQL – und umgekehrt. Cloud-Deployments müssen mit
> PostgreSQL gebaut werden.

---

## 3. Build-Artefakte

| Artefakt | Erzeugung | Verwendung |
|---|---|---|
| **JVM Fast-Jar** | `mvn -pl quarkus -am package -DskipTests` | Lokal, Docker-JVM, Debugging |
| **Native Binary** | `mvn -pl quarkus -am package -Dnative ...` | Lokal native, GCP VM, Cloud Run, Fargate |
| **JVM-Image** | `deploy/build-image.sh` | Lokal Docker JVM |
| **Native-Image** | `deploy/Dockerfile.native` | Lokal Docker native, Cloud Run, Fargate |
| **Agent-/Workspace-Dateien** | `deploy/AGENTS.native.md`, `.opencode/` | In Image backen oder mounten |

### 3.1 Native Build (Referenz)

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

Ergebnis: `quarkus/target/quarkus-1.0.0-SNAPSHOT-runner`.

### 3.2 Versionierung

- **Git-SHA** als primärer Tag: `qoc:<sha>`
- **SemVer** bei Releases: `qoc:1.2.3`
- `latest` nur für lokale/Dev-Images, nie in Produktion.
- Native Binary ebenfalls mit SHA/Version im Namen archivieren.

---

## 4. Repository-Struktur (Zielbild)

```
qoc/
├── .github/
│   └── workflows/
│       ├── ci.yml                     # Test + Build + Image
│       ├── deploy-gcp-vm.yml          # Native Binary per SSH/systemd
│       ├── deploy-gcp-cloudrun.yml    # Cloud Run
│       └── deploy-aws-fargate.yml     # ECS Fargate
├── deploy/
│   ├── Dockerfile                     # JVM-Image (vorhanden)
│   ├── Dockerfile.native              # Native-Image (vorhanden)
│   ├── docker-compose.yml             # Alle Varianten als Profil-Services
│   ├── docker-compose.oidc.yml        # OIDC-Overlay (qoc-local/qoc-dev)
│   ├── docker-compose.dev-jvm.yml     # JVM-Mode-Overlay (qoc-dev)
│   ├── docker-compose.docker-socket.yml # Testcontainers-Overlay (qoc-dev)
│   ├── seccomp-qoc-shell.json         # Seccomp-Profil qoc-secure-shell
│   ├── .env.example                   # gemeinsame Umgebung aller Varianten
│   ├── .env.oidc.example              # OIDC-Overlay-Vorlage
│   ├── docker-dev/                    # Dev-Image, Entrypoint, Devcontainer
│   ├── docker-secure-shell/           # Shell-isolierte Variante (+ home/)
│   ├── docker-<variante>/             # README je Variante, sonst keine Compose
│   ├── build-image.sh                 # JVM-Image (vorhanden)
│   ├── build-image-sqlite.sh          # SQLite/E2E (vorhanden)
│   ├── gcp-vm/                        # GCP-VM-Deployment (systemd/nginx/Auth0)
│   │   ├── gcp-auth0.sh
│   │   ├── gcp-auth0.env.example
│   │   ├── gcp-ssl.sh
│   │   ├── nginx.conf
│   │   ├── qoc-native.service         # systemd für GCP VM
│   │   ├── qoc.service                # systemd JVM (Legacy)
│   │   ├── qoc-prepare.sh
│   │   └── vm.md                      # VM-Runbook
│   ├── gcp/
│   │   ├── cloudrun-service.yaml      # Cloud-Run-Service (neu)
│   │   ├── cloudsql.md                # Cloud-SQL-Anbindung (neu)
│   │   └── iap.md                     # IAP/Trusted-Proxy (neu)
│   └── aws/
│       ├── task-definition.json       # ECS-Task (neu)
│       ├── service.json               # ECS-Service (neu)
│       └── alb-cognito.md             # ALB/Cognito (neu)
├── scripts/
│   ├── build-native.sh                # Native Build kapseln (neu)
│   ├── build-images.sh                # Beide Images bauen (neu)
│   ├── deploy-gcp-vm.sh               # scp + systemd (neu)
│   └── deploy-cloudrun.sh             # gcloud run deploy (neu)
├── start.sh                           # Lokaler Dev-Start (vorhanden)
├── start-native.sh                    # Lokaler Native-Start (vorhanden)
└── docs/
    └── cicd.md                        # dieses Dokument
```

> Die mit „neu" markierten Dateien sind Vorschläge für die nächste
> Ausbaustufe. Die vorhandenen Dateien funktionieren bereits.

---

## 5. CI/CD-Pipeline

### 5.1 Trigger

| Trigger | Zweck |
|---|---|
| Pull Request | Tests, Build-Prüfung, kein Deploy |
| Push auf `main` | Tests, Build, Images, Deploy in Staging |
| Git-Tag `v*` | Release-Build, Deploy in Produktion |
| `workflow_dispatch` | Manuelles Deploy/Rollback |

### 5.2 Stages

```
test ──► build-jvm ──► build-native ──► image ──► deploy-*
                                  │
                                  └──► archive binary
```

| Stage | Inhalt | Gate |
|---|---|---|
| **test** | `mvn test`, JaCoCo-Report | ≥ 80 % Instructions |
| **build-jvm** | Fast-Jar mit PostgreSQL-Buildzeitwerten | Tests grün |
| **build-native** | GraalVM-Native-Binary | Tests grün |
| **image** | JVM- und Native-Image bauen, taggen, pushen | Binary vorhanden |
| **deploy-gcp-vm** | Binary per `gcloud compute scp`, systemd-Restart | manuelle Freigabe |
| **deploy-gcp-cloudrun** | `gcloud run deploy` | manuelle Freigabe |
| **deploy-aws-fargate** | `aws ecs update-service` | manuelle Freigabe |

### 5.3 Beispiel: GitHub Actions (Skelett)

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

### 5.4 Auth der Pipeline gegenüber GCP/AWS

- **GCP:** Workload Identity Federation (OIDC), kein Service-Account-Key.
  `google-github-actions/auth@v2`.
- **AWS:** OIDC-Provider + IAM-Role, keine statischen Keys.
  `aws-actions/configure-aws-credentials@v4`.
- **Registry:** GCP Artifact Registry und AWS ECR; optional zusätzlich GHCR
  als gemeinsame Ablage.

### 5.5 Caching

- Maven-Repository (`~/.m2`) cachen.
- GraalVM-/Native-Build-Cache, falls verfügbar.
- Docker-Layer-Cache (`docker/build-push-action` mit `cache-from`/`cache-to`).

---

## 6. Betriebsmodi im Detail

### 6.1 Modus 1 – Lokal (Host)

**Zweck:** Entwicklung, Debugging, schnelle Iteration.

```bash
cd qoc
cp .env.example .env        # anpassen
./start.sh                  # Quarkus Dev, Port 8007
```

- DB: SQLite oder lokales PostgreSQL (`ENGINE_DB_KIND`).
- Auth: keine (Loopback) oder Basic über `OPENCODE_SERVER_PASSWORD`.
- Workspace: lokales Verzeichnis (`ENGINE_WORKSPACE`).
- Health: `curl http://127.0.0.1:8007/api/health`.

**Native lokal:**

```bash
./start-native.sh
```

Voraussetzung: Native Binary wurde mit passender DB-Art gebaut.

### 6.2 Modus 2a – Lokal Docker JVM

```bash
cd qoc
cp deploy/.env.example deploy/.env
./deploy/build-image.sh
cd deploy
docker compose up -d qoc-local
```

- Image: `qoc-engine:local`.
- Port: `8071` (fest).
- DB: PostgreSQL (Host/Neon), Buildzeitwert PostgreSQL.
- Workspace/Logs: gezielte Bind-Mounts, kein breites `/data`.
- Health: `curl http://127.0.0.1:8071/api/health/ready`.

### 6.3 Modus 2b – Lokal Docker Native

```bash
cd qoc
# Native Binary mit PostgreSQL bauen (siehe 3.1)
cd deploy
docker compose --profile native up -d --build
```

- Image: `qoc-engine:native`.
- Port: `8077` (fest).
- Gleiche Mounts und Hardening-Optionen wie JVM-Variante.
- Health: `curl http://127.0.0.1:8077/api/health/ready`.

### 6.4 Modus 3 – GCP VM Native (ohne Docker)

**Ziel:** Native Binary direkt auf einer GCP-VM, betrieben über systemd.

**Voraussetzungen:**

- Debian/Ubuntu VM, `qoc`-User, `/opt/qoc`, `/data`, `/etc/qoc/env`.
- Native Binary für PostgreSQL gebaut.
- Nginx als Reverse-Proxy und Basic-Auth (optional).

**Build:**

```bash
# Siehe 3.1; Ergebnis: quarkus/target/quarkus-1.0.0-SNAPSHOT-runner
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

**systemd-Unit:** `deploy/gcp-vm/qoc-native.service`.

**Health:**

```bash
curl -fsS -u "opencode:$PASS" https://<domain>/api/health
```

**Rollback:** Backup-Binary zurückkopieren und `systemctl restart qoc`.

### 6.5 Modus 4 – GCP Serverless (Cloud Run)

**Ziel:** Native Container auf Cloud Run, Single-Instance, externe Identität
über IAP.

**Voraussetzungen:**

- Artifact Registry-Repository.
- Cloud SQL PostgreSQL-Instanz und Verbindung (Unix-Socket oder VPC).
- Secret Manager für LLM-/DB-Secrets.
- Cloud Run Service mit `min-instances=1`, `max-instances=1`.
- CPU „always allocated" für Background-Tasks.

**Build & Push** (setzt das mit PostgreSQL gebaute Native Binary aus 3.1
voraus):

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

- Option A: Konfiguration (Agents, Settings) ins Image backen; Session-Dirs auf
  tmpfs.
- Option B: GCS-Bucket per Cloud Storage FUSE mounten (kein File-Locking,
  eventual consistency beachten).

**Auth:**

- IAP vor Cloud Run; QOC in `AuthMode.PROXY`.
- GCP-Header (`X-Goog-Authenticated-User-Email`) müssen im
  `TrustedProxyFilter` ergänzt werden (siehe Abschnitt 8).

**Health:** Cloud Run Health-Check auf `/api/health`.

**Rollback:** `gcloud run services update-traffic qoc --to-revisions=<prev>=100`.

### 6.6 Modus 5 – AWS Fargate

**Ziel:** Native Container auf ECS Fargate, Single-Task, externe Identität über
ALB/Cognito.

**Voraussetzungen:**

- ECR-Repository.
- RDS PostgreSQL.
- Secrets Manager.
- ECS-Cluster, Task-Definition, Service mit `desired-count=1`.
- ALB + Cognito (oder Trusted Proxy) für Auth.
- EFS für Workspace (optional; alternativ Config im Image).

**Build & Push** (setzt das mit PostgreSQL gebaute Native Binary aus 3.1
voraus):

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

**Task-Definition (Auszug):**

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

> **Hinweis:** Das aktuelle `Dockerfile.native` enthält kein `curl`. Für den
> ECS-Health-Check entweder `curl` ins Image aufnehmen oder den Check auf
> einen TCP-/HTTP-Check des ALB verlagern.

**Auth:**

- ALB mit OIDC/Cognito; Header `x-amzn-oidc-identity` im
  `TrustedProxyFilter` ergänzen.
- Alternativ: QOC Basic Auth und ALB nur als TLS-Terminator.

**Health:** ALB Target-Group-Health-Check auf `/api/health`.

**Rollback:** Vorherige Task-Definition-Revision aktivieren.

---

## 7. Plattform-Konfiguration

### 7.1 GCP

| Baustein | Empfehlung |
|---|---|
| Registry | Artifact Registry |
| Compute (Serverless) | Cloud Run, `min=max=1`, CPU always allocated |
| DB | Cloud SQL PostgreSQL (Unix-Socket) |
| Secrets | Secret Manager |
| Storage | GCS FUSE oder Config im Image |
| Auth | IAP + `AuthMode.PROXY` |
| Logs/Metrics | Cloud Logging / Cloud Monitoring / OpenTelemetry |
| Deploy-Auth | Workload Identity Federation |

### 7.2 AWS

| Baustein | Empfehlung |
|---|---|
| Registry | ECR |
| Compute | ECS Fargate, `desired-count=1` |
| DB | RDS PostgreSQL |
| Secrets | Secrets Manager |
| Storage | EFS oder Config im Image |
| Auth | ALB + Cognito / Trusted Proxy |
| Logs/Metrics | CloudWatch / OpenTelemetry |
| Deploy-Auth | OIDC + IAM-Role |

### 7.3 Gemeinsame Anforderungen

- PostgreSQL mit `sslmode=require`.
- Flyway-Migrationen beim Start (`QUARKUS_FLYWAY_LOCATIONS`).
- Health-Check `/api/health`.
- Ausgehender Netzwerkzugriff nur zu LLM-/DB-/Tool-Endpunkten.
- Persistente DB-Backups (Cloud SQL automated backups / RDS snapshots).

---

## 8. Auth und Identität

### 8.1 Modi

| Umgebung | AuthMode | Identität |
|---|---|---|
| Lokal | `NONE` oder `BASIC` | keine / `opencode` |
| Docker lokal | `BASIC` | `opencode` |
| GCP VM | `BASIC` (Nginx) | `opencode` |
| GCP Serverless | `PROXY` (IAP) | GCP-User |
| AWS Fargate | `PROXY` (ALB/Cognito) | Cognito-User |

### 8.2 Header-Mapping

Der `TrustedProxyFilter` liest aktuell:

- `X-Forwarded-User`
- `X-Remote-User`
- `Remote-User`

Für GCP/AWS müssen zusätzlich erkannt werden:

| Plattform | Header |
|---|---|
| GCP IAP | `X-Goog-Authenticated-User-Email`, `X-Goog-Authenticated-User-Id` |
| AWS ALB OIDC | `x-amzn-oidc-identity`, `x-amzn-oidc-data` (JWT) |

**Empfehlung:** Ein kleiner Header-Mapper im `TrustedProxyFilter`, der alle
Quellen in einen `Principal` überführt. Das ist eine einmalige, kleine
Erweiterung und macht die App plattformunabhängig.

### 8.3 Identitäts-Propagation (Ausbaustufe)

Aktuell endet die Identität an der HTTP-Schicht. Für vollständige Trennung
müssen folgende Stellen erweitert werden:

1. `ToolContext` um `principalId`/`Principal`.
2. `Engine`/`ToolRuntime` reichen den Principal an Tools weiter.
3. `PermissionEngine` erhält rollen-/gruppenbasierte Regeln.
4. Webhook-Payload um `principalId`, `roles`, `groups`.
5. MCP-Credential-Lookup pro (Agent, Server, Principal).

Das ist ein separater Refactor und nicht Teil der CI/CD-Grundstruktur.

---

## 9. Datenbank und Storage

### 9.1 Datenbank

| Umgebung | DB | Build-Zeitwert |
|---|---|---|
| Lokal Dev | SQLite oder PostgreSQL | `sqlite` oder `postgresql` |
| Docker lokal | PostgreSQL | `postgresql` |
| GCP VM | Neon/Cloud SQL | `postgresql` |
| GCP Serverless | Cloud SQL | `postgresql` |
| AWS Fargate | RDS | `postgresql` |

- Migrationen: Flyway, `classpath:db/migration` (PostgreSQL) bzw.
  `classpath:db/migration-sqlite` (SQLite).
- Migrationen müssen rückwärtskompatibel sein (Rolling Deploy).
- Backups: Cloud SQL automated backups, RDS snapshots, Neon-PITR.

### 9.2 Workspace

| Umgebung | Persistenz |
|---|---|
| Lokal | lokales Verzeichnis |
| Docker | Bind-Mount + Volume |
| GCP VM | `/data` auf der VM (persistente Disk) |
| GCP Serverless | GCS FUSE oder Image |
| AWS Fargate | EFS oder Image |

Empfehlung: Agent-/Settings-Konfiguration ins Image backen (immutable,
reproduzierbar). Nur veränderliche Session-/Tool-Daten persistieren.

---

## 10. Stabilitäts-Checkliste

- [ ] `mvn test` grün, JaCoCo ≥ 80 % Instructions.
- [ ] Native Build reproduzierbar (GraalVM-Version gepinnt).
- [ ] Build-Zeit-DB-Werte passen zum Ziel.
- [ ] Health-Check `/api/health` in jeder Umgebung erreichbar.
- [ ] Single-Instance (`min=max=1` bzw. `desired-count=1`).
- [ ] CPU always allocated (Cloud Run) für Background-Tasks.
- [ ] Secrets aus Secret Manager / Secrets Manager, nicht im Image.
- [ ] Image-Tags unveränderlich (SHA/SemVer), kein `latest` in Prod.
- [ ] Rollback-Pfad dokumentiert und getestet.
- [ ] DB-Backups aktiviert.
- [ ] Flyway-Migrationen rückwärtskompatibel.
- [ ] Logs zentral (Cloud Logging / CloudWatch / OTel).
- [ ] Ausgehender Netzwerkzugriff restringiert.
- [ ] Basic-Auth-Passwörter rotierbar.
- [ ] Native-Image enthält `git`, `util-linux`, `bubblewrap` (falls benötigt).

---

## 11. Rollback

| Modus | Rollback |
|---|---|
| Lokal | vorherigen Commit auschecken |
| Docker | vorheriges Image-Tag starten |
| GCP VM | Backup-Binary aus `/opt/qoc/qoc-runner.bak-*` zurückkopieren, `systemctl restart qoc` |
| GCP Cloud Run | Traffic auf vorherige Revision |
| AWS Fargate | vorherige Task-Definition-Revision aktivieren |

---

## 12. Offene Punkte / nächste Schritte

1. **CI-Workflows anlegen:** `.github/workflows/ci.yml`,
   `deploy-gcp-vm.yml`, `deploy-gcp-cloudrun.yml`, `deploy-aws-fargate.yml`.
2. **Build-Skripte kapseln:** `scripts/build-native.sh`,
   `scripts/build-images.sh`, `scripts/deploy-*.sh`.
3. **TrustedProxyFilter erweitern:** GCP-/AWS-Header-Mapping.
4. **Cloud-Run-/ECS-Vorlagen:** `deploy/gcp/`, `deploy/aws/`.
5. **Optional IaC:** Terraform-Module für GCP und AWS, damit Infrastruktur
   reproduzierbar wird.
6. **Optional State-Externalisierung:** Redis/DB für Sessions, falls echtes
   Multi-Instance-Scaling benötigt wird.

---

## 13. Kurzreferenz

```bash
# Tests
cd qoc && mvn test

# Lokal Dev
./start.sh

# Lokal Native (Binary vorhanden)
./start-native.sh

# Docker JVM
./deploy/build-image.sh && (cd deploy && docker compose up -d qoc-local)

# Docker Native
(cd deploy && docker compose --profile native up -d --build)

# GCP VM Native
#   Native Binary bauen, per gcloud scp kopieren, systemctl restart qoc

# Cloud Run
#   Native Image bauen/pushen, gcloud run deploy ...

# AWS Fargate
#   Native Image bauen/pushen, aws ecs update-service ...
```
